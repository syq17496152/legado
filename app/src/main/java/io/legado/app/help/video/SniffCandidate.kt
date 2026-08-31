package io.legado.app.help.video

import android.webkit.CookieManager

/**
 * 嗅探候选（video-sniff-403-and-rss-classic-fix R-P1-1 / AD-01）
 *
 * 核心修复：嗅探结果不再只是裸 URL，携带命中现场上下文（Referer/UA/Cookie）端到端流转到播放/下载引擎，
 * 对标成熟嗅探器"上下文随资源流转"机制，根治"嗅探成功但直连 403"。
 *
 * @property url 嗅探到的视频地址
 * @property headers 命中现场上下文头（Referer=嗅探页面 URL / User-Agent=WebView 实际 UA / Cookie=实时域内 cookie）；
 *   静态解析路径（MacCMS/DOM/快速路径）无 WebView 上下文时为空 map，消费端退化为"源配置兜底"，行为与历史一致
 * @property source 命中层级（fast/direct 快速直连、maccms 静态解析、dom 页面解析、webview 抓包四路、regex 正则兜底）
 * @property timestamp 命中时序（多候选评分选优用，Phase 4 消费；Phase 1 仅记录）
 */
data class SniffCandidate(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val contentType: String? = null,
    val source: String = SOURCE_UNKNOWN,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val SOURCE_UNKNOWN = "unknown"
        const val SOURCE_FAST = "fast"
        const val SOURCE_MACCMS = "maccms"
        const val SOURCE_DOM = "dom"
        const val SOURCE_WEBVIEW_INTERCEPT = "webview-intercept"
        const val SOURCE_WEBVIEW_OVERRIDE = "webview-override"
        const val SOURCE_WEBVIEW_RESOURCE = "webview-resource"
        const val SOURCE_WEBVIEW_RUNTIME = "webview-runtime"
        const val SOURCE_REGEX = "regex"

        /**
         * 构造 WebView 命中现场上下文头（四路命中点统一入口）。
         * Cookie 由 CookieManager 按视频地址域过滤（自动剔除跨域 cookie）；
         * requestHeaders 在 Android WebView 中不含 Cookie（由 chromium 网络栈注入），故统一走 CookieManager 实时读取。
         */
        fun fromWebViewHit(
            url: String,
            pageUrl: String?,
            userAgent: String?,
            source: String,
            requestHeaders: Map<String, String>? = null,
            mimeType: String? = null,
            contentType: String? = null
        ): SniffCandidate {
            val headers = linkedMapOf<String, String>()
            val referer = pageUrl?.takeIf { it.isNotBlank() && it != "about:blank" }
            if (referer != null) {
                headers["Referer"] = referer
            }
            if (!userAgent.isNullOrBlank()) {
                headers["User-Agent"] = userAgent
            }
            // Cookie 严格按视频地址域读取（design AD-01：不跨域泄露——禁止用嗅探页域 cookie 回退）
            val cookie = runCatching {
                CookieManager.getInstance().getCookie(url)
            }.getOrNull()
            if (!cookie.isNullOrBlank()) {
                headers["Cookie"] = cookie
            }
            requestHeaders?.let { extra ->
                extra.forEach { (k, v) ->
                    if (headers[k] == null && !v.isNullOrBlank() &&
                        k !in FORBIDDEN_CAPTURE_HEADERS
                    ) {
                        headers[k] = v
                    }
                }
            }
            return SniffCandidate(
                url = url,
                headers = headers,
                mimeType = mimeType,
                contentType = contentType,
                source = source
            )
        }

        private val FORBIDDEN_CAPTURE_HEADERS = setOf(
            "host", "content-length", "connection", "accept-encoding", "range"
        )
    }
}
