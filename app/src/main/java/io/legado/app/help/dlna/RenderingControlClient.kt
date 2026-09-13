package io.legado.app.help.dlna

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * add-dlna-cast：RenderingControl 服务客户端（音量）。
 *
 * 只做音量：`GetVolume` / `SetVolume`（`Channel=Master`）。
 *
 * **设备可能根本没有这个服务** —— 描述文档里缺 RenderingControl 时
 * [DlnaDevice.controlUrlRenderingControl] 为 null，此时本客户端所有方法直接返回失败，
 * 由 UI 层据此**隐藏音量控件**（design REQ-05 Scenario「音量控制」）。
 */
object RenderingControlClient {

    const val CHANNEL_MASTER = "Master"

    private val XML_MEDIA_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()

    /** 设备是否支持音量控制 */
    fun isSupported(device: DlnaDevice): Boolean =
        !device.controlUrlRenderingControl.isNullOrBlank()

    /**
     * 读音量。
     *
     * @return 0~100 的整数；不支持或读取失败返回 null
     */
    fun getVolume(device: DlnaDevice): Int? {
        val url = device.controlUrlRenderingControl?.takeIf { it.isNotBlank() } ?: return null
        val result = call(
            url = url,
            device = device,
            action = "GetVolume",
            body = buildString {
                append("<InstanceID>${DlnaConstants.INSTANCE_ID}</InstanceID>")
                append("<Channel>$CHANNEL_MASTER</Channel>")
            }
        )
        if (!result.success || result.raw == null) return null
        return SoapEnvelope.extractTag(result.raw, "CurrentVolume")?.toIntOrNull()
    }

    /** 设置音量（0~100，越界自动钳制） */
    fun setVolume(device: DlnaDevice, volume: Int): SoapResult {
        val url = device.controlUrlRenderingControl?.takeIf { it.isNotBlank() }
            ?: return SoapResult(success = false, httpCode = -1, fault = null, raw = null)
        val clamped = volume.coerceIn(0, 100)
        return call(
            url = url,
            device = device,
            action = "SetVolume",
            body = buildString {
                append("<InstanceID>${DlnaConstants.INSTANCE_ID}</InstanceID>")
                append("<Channel>$CHANNEL_MASTER</Channel>")
                append("<DesiredVolume>$clamped</DesiredVolume>")
            }
        )
    }

    private fun call(url: String, device: DlnaDevice, action: String, body: String): SoapResult {
        val envelope = SoapEnvelope.build(DlnaConstants.SERVICE_RENDERING_CONTROL, action, body)
        val request = Request.Builder()
            .url(url)
            .post(envelope.toRequestBody(XML_MEDIA_TYPE))
            .header(
                "SOAPACTION",
                SoapEnvelope.soapActionHeader(DlnaConstants.SERVICE_RENDERING_CONTROL, action)
            )
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .build()
        return DlnaSoapExecutor.execute(request, device.displayName, action)
    }
}
