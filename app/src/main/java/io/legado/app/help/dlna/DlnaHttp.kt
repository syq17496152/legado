package io.legado.app.help.dlna

import io.legado.app.constant.AppLog
import okhttp3.ConnectionPool
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
            // dlna-cast-xiaomi-fix（2026-09-14 真机铁证 logs23 20:51 会话）：小米电视 DLNA 服务
            // 响应后立即关闭 TCP 连接，OkHttp 默认连接池复用死连接 → "unexpected end of stream"
            // （重试 2-3ms 即败：池内全是死连接 + 禁重试成终局）。SOAP 指令低频（建立会话 4-6 条
            // + 控制指令），每次新建连接仅多一次 LAN 握手（1-3ms），彻底根除死连接复用。
            // 双保险：① 各请求显式带 `Connection: close`（SOAP 与设备描述均已是）；
            //          ② 客户端不保留空闲连接（maxIdleConnections = 0）。
            // ⚠️ keepAliveDuration 必须 > 0 ——OkHttp 硬约束（okhttp 5.4.0 RealConnectionPool
            //    构造即 require(keepAliveDuration > 0)），传 0 会抛 IllegalArgumentException
            //    "keepAliveDuration <= 0: 0"。2026-09-16 回归铁证：早前此处写 ConnectionPool(0, 0,
            //    NANOSECONDS)，soapClient 懒初始化即抛异常 → 设备发现与投屏全链路 100% 失败。
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            // AD-02 禁重试仅针对防盗链 CDN 上游（streamClient）；soapClient 打局域网电视无封 IP 风险，
            // 允许连接失败换新连接重试（与禁池双保险，部分电视仍会中途断流）
            .retryOnConnectionFailure(true)
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
        return kotlin.runCatching {
            // 客户端构建必须落在保护范围内：构建异常一旦逃逸会冒泡到 SsdpDiscovery.discover 的
            // 顶层 catch，把整次设备发现降级成"0 台设备"（2026-09-16 回归铁证：非法连接池参数
            // 使 UI 表现为"连设备都搜不到"）。
            val client = soapClient.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            val builder = Request.Builder()
                .url(url)
                // 渲染端常在响应后立即断连，显式声明关闭，避免连接进池被后续请求复用
                .header("Connection", "close")
                .get()
            headers?.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.putDebugWithTag(
                        DlnaConstants.TAG,
                        "设备描述获取失败: code=${response.code} url=$url",
                        level = AppLog.Level.WARN
                    )
                    return@use null
                }
                response.body?.string()
            }
        }.onFailure {
            AppLog.putDebugWithTag(
                DlnaConstants.TAG,
                "设备描述获取异常: ${it.message} url=$url",
                it,
                level = AppLog.Level.WARN
            )
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
                    val ct = if (response.isSuccessful) response.header("Content-Type") else null
                    // 诊断日志：403=防盗链拒 HEAD（MIME 将降级扩展名推断），是投屏失败排查的关键线索
                    AppLog.putDebugWithTag(
                        DlnaConstants.TAG,
                        "HEAD 探测: code=${response.code} mime=$ct url=$url",
                        level = if (response.isSuccessful) AppLog.Level.INFO else AppLog.Level.WARN
                    )
                    ct
                }
        }.onFailure {
            AppLog.putDebugWithTag(
                DlnaConstants.TAG,
                "HEAD 探测异常: ${it.message} url=$url",
                it,
                level = AppLog.Level.WARN
            )
        }.getOrNull()
    }
}
