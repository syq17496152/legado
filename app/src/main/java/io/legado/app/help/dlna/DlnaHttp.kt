package io.legado.app.help.dlna

import io.legado.app.utils.LogUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * add-dlna-cast：投屏专用 OkHttp 客户端（**独立于项目共享客户端**）。
 *
 * 为什么不复用 `help/http/HttpHelper.kt` 的共享 `okHttpClient`：
 *  1. 共享客户端挂了项目的一堆拦截器（Cookie 存储、DoH、UA 注入、日志脱敏…）。
 *     SOAP 请求打的是**局域网设备**，被注入项目 cookie/UA 只会增加不确定性；
 *     而代理转发上游时要求**精确控制**请求头，更不能被拦截器改写。
 *  2. 两类调用对超时的诉求相反（见下），共享客户端无法同时满足。
 *
 * 两个客户端分工（对应 design AD-11 超时常量表）：
 *  - [soapClient]：控制指令，短超时、快速失败
 *  - [streamClient]：代理转发媒体流，**`callTimeout = 0`（不设总时长上限）**，
 *    否则一部两小时的电影会在中途被 OkHttp 自己掐断
 *
 * 两者均 `retryOnConnectionFailure(false)`：AD-02 明确禁止自动重试，
 * 防盗链源收到重试风暴可能封 IP，失败就让渲染端看到真实错误码。
 */
object DlnaHttp {

    /** SOAP / 设备描述文档：短超时，快速失败 */
    val soapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(DlnaConstants.TIMEOUT_SOAP_MS, TimeUnit.MILLISECONDS)
            .callTimeout(DlnaConstants.TIMEOUT_SOAP_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** 代理转发：读超时用于「首包」判定，总时长不限 */
    val streamClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(DlnaConstants.TIMEOUT_UPSTREAM_FIRST_BYTE_MS, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * 拉取文本内容（用于 device description XML）。
     *
     * 任何失败都返回 null 并只留一条 debug 日志 —— 发现阶段一台设备解析失败
     * 不该影响其它设备。
     */
    fun fetchText(
        url: String,
        headers: Map<String, String>? = null,
        timeoutMs: Long = DlnaConstants.TIMEOUT_DESCRIPTION_MS
    ): String? {
        val client = soapClient.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return kotlin.runCatching {
            val builder = Request.Builder().url(url).get()
            headers?.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    LogUtils.d(DlnaConstants.TAG) { "fetchText ${response.code} -> $url" }
                    return@use null
                }
                response.body?.string()
            }
        }.onFailure {
            LogUtils.d(DlnaConstants.TAG) { "fetchText failed: ${it.message}" }
        }.getOrNull()
    }

    /**
     * `HEAD` 探测上游 `Content-Type`（MIME 三级推断的第二级）。
     *
     * 必须带会话 headers：否则防盗链源会回 403/404，导致 MIME 误判。
     * 失败（403/405/超时）返回 null，由 [MimeSniffer.resolve] 降级到扩展名推断，不阻断投屏。
     */
    fun headContentType(url: String, headers: Map<String, String>?): String? {
        return kotlin.runCatching {
            val builder = Request.Builder().url(url).head()
            headers?.forEach { (k, v) -> builder.header(k, v) }
            soapClient.newBuilder()
                .callTimeout(DlnaConstants.TIMEOUT_HEAD_PROBE_MS, TimeUnit.MILLISECONDS)
                .build()
                .newCall(builder.build())
                .execute()
                .use { response ->
                    if (response.isSuccessful) response.header("Content-Type") else null
                }
        }.onFailure {
            LogUtils.d(DlnaConstants.TAG) { "HEAD probe failed: ${it.message}" }
        }.getOrNull()
    }
}
