package io.legado.app.help.dlna

import io.legado.app.utils.LogUtils
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * add-dlna-cast：AVTransport 服务客户端（design AD-09 / 协议铁律表）。
 *
 * 只实现投屏需要的动作子集：SetAVTransportURI / Play / Pause / Stop / Seek /
 * GetPositionInfo / GetTransportInfo / GetMediaInfo。
 *
 * 所有动作统一走 [call]，保证三件事在**一处**落实，避免每个动作各写一遍出错：
 *  1. `SOAPACTION` 头带双引号（规范要求，老设备敏感）
 *  2. `Content-Type: text/xml; charset="utf-8"`
 *  3. 失败（HTTP 非 2xx 或响应含 `errorCode`）统一转成 [SoapResult]，不抛异常
 */
object AvTransportClient {

    /** 传输状态：与 UPnP AVTransport 的 `CurrentTransportState` 取值一致 */
    const val STATE_STOPPED = "STOPPED"
    const val STATE_PLAYING = "PLAYING"
    const val STATE_PAUSED = "PAUSED_PLAYBACK"
    const val STATE_TRANSITIONING = "TRANSITIONING"
    const val STATE_NO_MEDIA = "NO_MEDIA_PRESENT"
    const val STATE_ERROR = "ERROR_OCCURRED"

    private val XML_MEDIA_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()

    /**
     * 投递媒体地址。
     *
     * @param metadata DIDL-Lite；传 [DidlLiteBuilder.EMPTY_METADATA] 即走"无元数据"兼容路径
     */
    fun setAvTransportUri(
        device: DlnaDevice,
        castUrl: String,
        metadata: String
    ): SoapResult = call(
        device = device,
        action = "SetAVTransportURI",
        body = buildString {
            append("<InstanceID>${DlnaConstants.INSTANCE_ID}</InstanceID>")
            // CurrentURI 是文本节点 → 转义；CurrentURIMetaData 内部已是 XML 片段，
            // 作为文本节点嵌入时必须整体转义（设备会先反解出 XML 再解析）
            append("<CurrentURI>${SoapEnvelope.escapeText(castUrl)}</CurrentURI>")
            append("<CurrentURIMetaData>${SoapEnvelope.escapeText(metadata)}</CurrentURIMetaData>")
        }
    )

    fun play(device: DlnaDevice): SoapResult = call(
        device = device,
        action = "Play",
        body = "<InstanceID>${DlnaConstants.INSTANCE_ID}</InstanceID><Speed>1</Speed>"
    )

    fun pause(device: DlnaDevice): SoapResult = call(
        device = device,
        action = "Pause",
        body = "<InstanceID>${DlnaConstants.INSTANCE_ID}</InstanceID>"
    )

    fun stop(device: DlnaDevice): SoapResult = call(
        device = device,
        action = "Stop",
        body = "<InstanceID>${DlnaConstants.INSTANCE_ID}</InstanceID>"
    )

    /**
     * 按绝对位置 seek。
     *
     * `Unit=REL_TIME` + `Target=HH:MM:SS`；目标值经 [SoapEnvelope.formatHms] **钳制到 23:59:59**
     * —— REL_TIME 表达不了更长的时间，钳制至少不发非法值（design Drawbacks 的已知限制）。
     */
    fun seek(device: DlnaDevice, positionMs: Long): SoapResult = call(
        device = device,
        action = "Seek",
        body = buildString {
            append("<InstanceID>${DlnaConstants.INSTANCE_ID}</InstanceID>")
            append("<Unit>REL_TIME</Unit>")
            append("<Target>${SoapEnvelope.formatHms(positionMs)}</Target>")
        }
    )

    /** 查询位置与时长；解析失败返回 null（由调用方按"失败一次"计数） */
    fun positionInfo(device: DlnaDevice): TransportSnapshot? {
        val result = call(
            device = device,
            action = "GetPositionInfo",
            body = "<InstanceID>${DlnaConstants.INSTANCE_ID}</InstanceID>"
        )
        if (!result.success || result.raw == null) return null
        val position = SoapEnvelope.parseHms(SoapEnvelope.extractTag(result.raw, "RelTime"))
        val duration = SoapEnvelope.parseHms(SoapEnvelope.extractTag(result.raw, "TrackDuration"))
        return TransportSnapshot(
            state = stateFromPositionInfo(result),
            positionMs = position,
            durationMs = duration
        )
    }

    /** 只查状态（暂停中降频轮询用，省一次解析） */
    fun transportState(device: DlnaDevice): String? {
        val result = call(
            device = device,
            action = "GetTransportInfo",
            body = "<InstanceID>${DlnaConstants.INSTANCE_ID}</InstanceID>"
        )
        if (!result.success || result.raw == null) return null
        return SoapEnvelope.extractTag(result.raw, "CurrentTransportState")
    }

    /** 从 GetPositionInfo 响应顺带取状态（部分设备在该响应里不返回状态，此时为空串） */
    private fun stateFromPositionInfo(result: SoapResult): String =
        result.raw?.let { SoapEnvelope.extractTag(it, "CurrentTransportState") }.orEmpty()

    /**
     * 统一调用入口。
     *
     * 不抛异常：网络失败、超时、HTTP 非 2xx、SOAP Fault 全部归一为 [SoapResult]，
     * 调用方（降级链）只按 `success` / `fault.code` 分支，不必写 try/catch。
     */
    private fun call(device: DlnaDevice, action: String, body: String): SoapResult {
        val url = device.controlUrlAvTransport
        val envelope = SoapEnvelope.build(DlnaConstants.SERVICE_AV_TRANSPORT, action, body)
        val request = Request.Builder()
            .url(url)
            .post(envelope.toRequestBody(XML_MEDIA_TYPE))
            .header(
                "SOAPACTION",
                SoapEnvelope.soapActionHeader(DlnaConstants.SERVICE_AV_TRANSPORT, action)
            )
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .build()
        return DlnaSoapExecutor.execute(request, device.displayName, action)
    }
}

/** add-dlna-cast：一次 SOAP 调用的结果（统一的失败表达） */
data class SoapResult(
    val success: Boolean,
    val httpCode: Int,
    val fault: SoapFault? = null,
    val raw: String? = null
) {
    /** 失败原因的可读描述（用于 UI 提示与日志） */
    val errorText: String
        get() = when {
            success -> "ok"
            fault != null -> fault.toString()
            else -> "HTTP $httpCode"
        }
}

/** add-dlna-cast：渲染端的一次状态快照 */
data class TransportSnapshot(
    val state: String,
    val positionMs: Long,
    val durationMs: Long
)

/**
 * add-dlna-cast：SOAP 请求执行器（AVTransport 与 RenderingControl 共用）。
 *
 * 抽出来是为了让两个客户端对"什么叫失败"的判定完全一致。
 */
object DlnaSoapExecutor {

    fun execute(request: Request, deviceName: String, action: String): SoapResult {
        return kotlin.runCatching {
            DlnaHttp.soapClient.newCall(request).execute().use { response ->
                val text = runCatching { response.body?.string() }.getOrNull()
                val fault = text?.let { SoapEnvelope.parseFault(it) }
                val ok = response.isSuccessful && fault == null
                if (!ok) {
                    LogUtils.d(DlnaConstants.TAG) {
                        "$action -> $deviceName 失败: http=${response.code} fault=$fault"
                    }
                }
                SoapResult(
                    success = ok,
                    httpCode = response.code,
                    fault = fault,
                    raw = text
                )
            }
        }.getOrElse { error ->
            LogUtils.d(DlnaConstants.TAG) { "$action -> $deviceName 异常: ${error.message}" }
            SoapResult(success = false, httpCode = -1, fault = null, raw = null)
        }
    }
}
