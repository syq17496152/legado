package io.legado.app.help.http

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AndroidRuntimeException
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.CacheManager
import io.legado.app.help.WebCacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.getInjectionString
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.video.SniffCandidate
import io.legado.app.model.Debug
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.get
import io.legado.app.utils.runOnUI
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.apache.commons.text.StringEscapeUtils
import splitties.init.appCtx
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 后台webView
 */
class BackstageWebView(
    private val url: String? = null,
    private val html: String? = null,
    private val encode: String? = null,
    private val tag: String? = null,
    private val headerMap: HashMap<String, String>? = null,
    private val sourceRegex: String? = null,
    private val overrideUrlRegex: String? = null,
    private val javaScript: String? = null,
    private var delayTime: Long = 0,
    private val cacheFirst: Boolean = false,
    private val timeout: Long? = null,
    private val result: String? = null,
    private val isRule: Boolean = false,
    private val interceptAllRequests: Boolean = false,  // 新增：是否拦截所有请求（fetch/XHR），仅视频抓取场景启用
    private val videoSniffJs: String? = null,            // 新增：页面加载前注入的JS（视频嗅探用）
    // Archive UI 迁移（Phase 0 地基补齐）：WebView 复用池作用域（GLOBAL/DISCOVERY/RSS）
    private val poolScope: WebViewPool.Scope = WebViewPool.Scope.GLOBAL
) {

    private val mHandler = Handler(Looper.getMainLooper())
    private var callback: Callback? = null
    private var pooledWebView: PooledWebView? = null
    private var closed = false

    // video-sniff-403-and-rss-classic-fix R-P1-1/AD-01：四路命中点写入嗅探上下文候选，
    // 调用方在 getStrResponse 返回后读取（池化复用实例，getStrResponse 开始时重置防残留）
    var lastSniffCandidate: SniffCandidate? = null
        private set

    // video-sniff-403-and-rss-classic-fix Phase 4 (5.3)：多候选缓冲（上限 MAX_SNIFF_CANDIDATES，按 URL 去重）
    // 各命中点（拦截路/override/onLoadResource/JS 运行时）命中即入缓冲；首命中写 lastSniffCandidate
    // 并交付回调（保持既有"首命中"消费语义，单字段写入零破坏），后续命中仅入缓冲供评分选优（5.3），
    // 防低质量命中覆盖主清单命中（5.2 纳入 .ts 分片后的顺序保护）
    val lastSniffCandidates: List<SniffCandidate>
        get() = synchronized(sniffHitsLock) { sniffHits.toList() }
    private val sniffHits = ArrayDeque<SniffCandidate>()
    private val sniffHitsLock = Any()
    private val probedUrls = HashSet<String>()

    // video-sniff-403-and-rss-classic-fix Phase 4 (5.2)：内置视频 URL 后缀白名单（拦截路兜底，
    // 覆盖源正则 VIDEO_SOURCE_REGEX 未含的 .ts；.ts 分片命中经 recordSniffHit 仅入缓冲不交付）
    private val VIDEO_URL_PATTERN = Regex(
        """(?i).*https?://[^\s]{12,}\.(?:m3u8|mp4|mkv|flv|ts|mp3|m4a|aac|mpd)(?:\?[^\s]*)?"""
    )

    private fun isVideoUrlPattern(url: String): Boolean = VIDEO_URL_PATTERN.matches(url)

    private fun isTsSegmentUrl(url: String): Boolean =
        url.substringBefore('?').lowercase().endsWith(".ts")

    suspend fun getStrResponse(): StrResponse = withTimeout(timeout ?: 60000L) {
        lastSniffCandidate = null
        // video-sniff-403-and-rss-classic-fix Phase 4 (5.3)：候选缓冲/探测去重集合同步重置防残留
        synchronized(sniffHitsLock) {
            sniffHits.clear()
            probedUrls.clear()
        }
        suspendCancellableCoroutine { block ->
            block.invokeOnCancellation {
                runOnUI {
                    destroy()
                }
            }
            callback = object : Callback() {
                override fun onResult(response: StrResponse) {
                    if (!block.isCompleted) {
                        block.resume(response)
                    }
                }

                override fun onError(error: Throwable) {
                    if (!block.isCompleted)
                        block.resumeWithException(error)
                }
            }
            if (javaScript == null && delayTime == 0L) {
                delayTime = 900L
            }
            runOnUI {
                try {
                    load()
                } catch (error: Throwable) {
                    destroy()
                    block.resumeWithException(error)
                }
            }
        }
    }

    private fun getEncoding(): String {
        return encode ?: "utf-8"
    }

    @Throws(AndroidRuntimeException::class)
    private fun load() {
        val webView = createWebView()
        try {
            when {
                !html.isNullOrEmpty() -> {
                    if (isRule) {
                        webView.addJavascriptInterface(WebCacheManager, nameCache)
                        tag?.let { key ->
                           // B1 修复：先读内存缓存，未命中再 runBlocking 查数据库并写入缓存
                           // 原实现直接 runBlocking 主线程阻塞，长跑下频繁调用影响 UI 流畅度
                           val bookSource = SourceHelp.getCachedBookSource(key)
                               ?: runBlocking(IO) {
                                   appDb.bookSourceDao.getBookSource(key)
                               }?.also { SourceHelp.putBookSourceCache(key, it) }
                           bookSource?.let {
                               webView.webChromeClient = object : WebChromeClient() {
                                   /* 监听网页日志 */
                                   override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                       val messageLevel = consoleMessage.messageLevel().name
                                       val message = consoleMessage.message()
                                       Debug.log(it.bookSourceUrl, "${messageLevel}: $message", true)
                                       return true
                                   }
                               }
                               webView.addJavascriptInterface(it as BaseSource, nameSource)
                               val webJsExtensions = WebJsExtensions(it, null, webView)
                               webView.addJavascriptInterface(webJsExtensions, nameJava)
                            }
                        }
                    }
                    result?.let {
                        CacheManager.put("webview_result", it)
                    }
                    webView.loadDataWithBaseURL(url, html, "text/html", getEncoding(), url)
                }

                else -> if (headerMap == null) {
                    webView.loadUrl(url!!)
                } else {
                    webView.loadUrl(url!!, headerMap)
                }
            }
        } catch (e: Exception) {
            callback?.onError(e)
            destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val pooledWebView = WebViewPool.acquire(appCtx, poolScope)
        this.pooledWebView = pooledWebView
        val webView = pooledWebView.realWebView
        webView.onResume() //缓存库拿的需要激活
        val settings = webView.settings
        settings.blockNetworkImage = true
        settings.userAgentString = headerMap?.get(AppConst.UA_NAME, true) ?: AppConfig.userAgent
        settings.cacheMode = if(cacheFirst) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT
        if (sourceRegex.isNullOrBlank() && overrideUrlRegex.isNullOrBlank()) {
            webView.webViewClient = HtmlWebViewClient()
        } else {
            webView.webViewClient = SnifferWebClient()
        }
        return webView
    }

    private fun destroy() {
        closed = true
        callback = null
        pooledWebView?.let { WebViewPool.release(it) }
        pooledWebView = null
    }

    private fun isActiveWebView(webView: WebView? = null): Boolean {
        if (closed) return false
        val pooled = pooledWebView ?: return false
        return webView == null || pooled.realWebView === webView
    }

    private fun getJs(): String {
        javaScript?.let {
            if (it.isNotEmpty()) {
                return it
            }
        }
        return JS
    }

    private fun setCookie(url: String) {
        tag?.let {
            Coroutine.async(executeContext = IO) {
                val cookie = CookieManager.getInstance().getCookie(url)
                CookieStore.setCookie(it, cookie)
            }
        }
    }

    /**
     * video-sniff-403-and-rss-classic-fix Phase 4 (5.3)：记录嗅探命中并返回是否由本次交付。
     * 全部命中按 URL 去重后入候选缓冲（容量上限淘汰最旧）；仅当单候选字段为空且本次命中
     * 可交付（[deliverable]）时写入 lastSniffCandidate 并返回 true——调用方据此触发回调+destroy。
     * .ts 分片命中（5.2）固定不可交付：ExoPlayer 需 m3u8 主索引无法单独播放分片，
     * 仅入缓冲参与评分（对齐 VIDEO_SOURCE_REGEX 注释"移除 .ts"原始动机，由评分体系消解）。
     */
    private fun recordSniffHit(candidate: SniffCandidate, deliverable: Boolean = true): Boolean {
        synchronized(sniffHitsLock) {
            if (sniffHits.none { it.url == candidate.url }) {
                if (sniffHits.size >= MAX_SNIFF_CANDIDATES) sniffHits.removeFirst()
                sniffHits.addLast(candidate)
            }
            if (deliverable && lastSniffCandidate == null) {
                lastSniffCandidate = candidate
                return true
            }
        }
        return false
    }

    /**
     * video-sniff-403-and-rss-classic-fix Phase 4 (5.1)：响应 Content-Type 白名单（非模糊匹配）。
     * video 与 audio 前缀类型（含 video/mp2t 分片流）、application/vnd.apple.mpegurl 及
     * x-mpegurl 变体、application/dash+xml。注意：注释内禁写 "video/星" 形态（斜杠+星号
     * 会开启嵌套块注释致后续全文被吞，铁证 2026-08-31 编译失败）。
     */
    private fun isVideoContentType(contentType: String?): Boolean {
        if (contentType.isNullOrBlank()) return false
        val ct = contentType.lowercase().substringBefore(';').trim()
        return ct.startsWith("video/") || ct.startsWith("audio/") ||
            ct.contains("mpegurl") || ct.contains("dash+xml")
    }

    /**
     * video-sniff-403-and-rss-classic-fix Phase 4 (5.1)：响应 Content-Type 白名单探测
     * （F-12/R6：.html 形态直链视频流 URL 无任何视频 URL 特征，只能靠响应 Content-Type 判定；
     * shouldInterceptRequest 仅可见请求、拿不到响应头 → 异步 HEAD 探测补齐响应证据）。
     * 不阻塞 WebView 资源加载（探测异步执行，会话窗口内完成即交付）；同 URL 仅探测一次。
     *
     * ⚠️ 线程铁律（2026-08-31 真机 6 连闪退铁证）：本函数由 shouldInterceptRequest 在
     * chromium 工作线程（ThreadPoolForeg）调用——**禁止触碰任何 WebView 实例方法**
     * （view.url/view.settings 会抛 "WebView method was called on wrong thread" RuntimeException）。
     * 页面 URL 用会话字段 [url]，UA 用与 init（L211 主线程装配）同源的 headerMap/AppConfig 推导，
     * 零 WebView 访问。
     */
    private fun probeContentTypeHit(
        request: WebResourceRequest?,
        resUrl: String
    ) {
        val firstProbe = synchronized(sniffHitsLock) { probedUrls.add(resUrl) }
        if (!firstProbe) return
        val ua = headerMap?.get(AppConst.UA_NAME, true) ?: AppConfig.userAgent
        val pageUrl = url
        val requestHeaders = request?.requestHeaders
        Coroutine.async(executeContext = IO) {
            val contentType = withTimeoutOrNull(3000L) {
                kotlin.runCatching {
                    val builder = Request.Builder().url(resUrl).head()
                    ua?.let { builder.header("User-Agent", it) }
                    pageUrl?.let { builder.header("Referer", it) }
                    videoStreamClient.newCall(builder.build()).execute().use { resp ->
                        resp.header("Content-Type")
                    }
                }.getOrNull()
            }
            if (contentType != null && isVideoContentType(contentType)) {
                AppLog.putInfo("R5网络抓包: Content-Type 白名单命中(.html壳直链)")
                try {
                    val candidate = SniffCandidate.fromWebViewHit(
                        url = resUrl,
                        pageUrl = pageUrl,
                        userAgent = ua,
                        source = SniffCandidate.SOURCE_WEBVIEW_INTERCEPT,
                        requestHeaders = requestHeaders,
                        mimeType = contentType,
                        contentType = contentType
                    )
                    if (recordSniffHit(candidate) && !closed && callback != null) {
                        mHandler.post {
                            try {
                                val response = StrResponse(url!!, resUrl)
                                callback?.onResult(response)
                            } catch (e: Exception) {
                                callback?.onError(e)
                            }
                            destroy()
                        }
                    }
                } catch (e: Exception) {
                    AppLog.putWarn("R5网络抓包: Content-Type 命中候选构造失败, ${e.message}")
                }
            }
        }
    }

    private inner class HtmlWebViewClient : WebViewClient() {

        private var runnable: EvalJsRunnable? = null
        private var isRedirect = false

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            isRedirect = isRedirect || if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                request.isRedirect
            } else {
                request.url.toString() != view.url
            }
            return super.shouldOverrideUrlLoading(view, request)
        }

        override fun onPageFinished(view: WebView, url: String) {
            setCookie(url)
            result?.let {
                view.evaluateJavascript("window.result = $nameCache.getFromMemory('webview_result')", null)
            }
            val runnable = runnable ?: EvalJsRunnable(view, url, getJs()).also {
                runnable = it
            }
            mHandler.removeCallbacks(runnable)
            mHandler.postDelayed(runnable, 100L + delayTime)
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?
        ) {
            handler?.proceed()
        }

        private inner class EvalJsRunnable(
            webView: WebView,
            private val url: String,
            mJavaScript: String
        ) : Runnable {
            private var retry = 0
            private val intervals = listOf(200L, 400L, 600L, 800L, 1000L)
            private val mWebView: WeakReference<WebView> = WeakReference(webView)
            private val jsStr = if (isRule) {
                "$getInjectionString\n$mJavaScript"
            } else mJavaScript
            override fun run() {
                mWebView.get()?.evaluateJavascript(jsStr) {
                    if (isActiveWebView(mWebView.get())) {
                        handleResult(it)
                    }
                }
            }

            private fun handleResult(result: String) = Coroutine.async {
                if (result.isNotEmpty() && result != "null") {
                    val content = StringEscapeUtils.unescapeJson(result)
                        .replace(quoteRegex, "")
                    try {
                        val response = buildStrResponse(content)
                        callback?.onResult(response)
                    } catch (e: Exception) {
                        callback?.onError(e)
                    }
                    mHandler.post {
                        destroy()
                    }
                    return@async
                }
                if (retry > 30) {
                    callback?.onError(NoStackTraceException("js执行超时"))
                    mHandler.post {
                        destroy()
                    }
                    return@async
                }
                val nextDelay = if (retry < intervals.size) {
                    intervals[retry]
                } else {
                    intervals.last()
                }
                retry++
                mHandler.postDelayed(this@EvalJsRunnable, nextDelay)
            }

            private fun buildStrResponse(content: String): StrResponse {
                if (!isRedirect) {
                    return StrResponse(url, content)
                }
                val originUrl = this@BackstageWebView.url ?: url
                val originResponse = Response.Builder()
                    .code(302)
                    .request(Request.Builder().url(originUrl).build())
                    .protocol(Protocol.HTTP_1_1)
                    .message("Found")
                    .build()
                val response = Response.Builder()
                    .code(200)
                    .request(Request.Builder().url(url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .message("OK")
                    .priorResponse(originResponse)
                    .build()
                return StrResponse(response, content)
            }
        }

    }

    private inner class SnifferWebClient : WebViewClient() {

        // 新增：拦截所有网络请求（包括 fetch/XHR），这是 onLoadResource 无法捕获的
        // 参考 Fongmi/TV Sniffer.java 的 shouldInterceptRequest + isVideoFormat 多层判断
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            // 防御：destroy 后不再处理（shouldInterceptRequest 在工作线程调用，可能延迟）
            if (closed || callback == null) return null
            if (interceptAllRequests && request != null) {
                val resUrl = request.url?.toString() ?: return null
                // ===== video-sniff-403-and-rss-classic-fix Phase 4 (5.1/F-12/R6)：判定顺序修正 =====
                // 旧逻辑：.html/url=http 嵌套排除前置于正则与 Content-Type 判定，
                //   误杀 HTML 壳内直链视频的源（R6：.html 形态视频流 URL 被直接排除）
                // 新顺序：先视频特征判定（URL 模式/源正则），排除规则后置且仅对
                //   "既无视频 URL 特征又无视频 Content-Type"的请求生效
                val urlPatternHit = isVideoUrlPattern(resUrl)
                val regexHit = sourceRegex?.let { resUrl.matches(it.toRegex()) } == true
                // 嵌套URL排除（后置，参考 Sniffer.java isVideoFormat 第3步）：
                // 避免 ?url=https://cdn.com/video.m3u8 重定向嵌套URL误匹配（无视频特征时保留原排除语义）
                if ((resUrl.contains("url=http") || resUrl.contains("v=http")) &&
                    !urlPatternHit && !regexHit
                ) {
                    return null  // 跳过嵌套URL，不拦截
                }
                // isVideoFormat 第2层：URL 内置模式（5.2：含 .ts 分片，命中仅入缓冲）或 sourceRegex 匹配
                if (urlPatternHit || regexHit) {
                    deliverInterceptHit(view, request, resUrl)
                    return null
                }
                // isVideoFormat 第3层：响应 Content-Type 白名单（5.1：.html 壳内直链视频流 R6 场景，
                // 异步 HEAD 探测补齐响应证据，不阻塞资源加载）
                if (resUrl.contains(".html")) {
                    // 线程铁律：不传 view（shouldInterceptRequest 在工作线程，禁触 WebView 方法——6 连闪退修复 2026-08-31）
                    probeContentTypeHit(request, resUrl)
                }
            }
            return null  // 返回 null 表示不拦截，让请求正常发出
        }

        /**
         * 5.3：拦截命中交付——候选构造需读 CookieManager，保持在 UI 线程 post 内执行。
         * 首命中执行 resume+destroy（sniff-result-pipeline-fix FR-2 切 UI 线程同步 resume 原方案保留：
         * shouldInterceptRequest 在 chromium 工作线程调用，resume 与 destroy 顺序由同一 post 保证）；
         * 非首命中（含 .ts 分片）仅入候选缓冲，不交付不 destroy（会话继续等待更优命中）。
         */
        private fun deliverInterceptHit(
            view: WebView?,
            request: WebResourceRequest,
            resUrl: String
        ) {
            AppLog.putInfo("R5网络抓包命中(切UI线程), post到UI线程执行resume+destroy")
            mHandler.post {
                try {
                    // R-P1-1/AD-01：记录命中现场上下文（requestHeaders 由 chromium 注入 Cookie 前的原始头，Cookie 统一 CookieManager 读取）
                    val candidate = SniffCandidate.fromWebViewHit(
                        url = resUrl,
                        pageUrl = view?.url,
                        userAgent = view?.settings?.userAgentString,
                        source = SniffCandidate.SOURCE_WEBVIEW_INTERCEPT,
                        requestHeaders = request.requestHeaders
                    )
                    if (recordSniffHit(candidate, deliverable = !isTsSegmentUrl(resUrl))) {
                        val response = StrResponse(url!!, resUrl)
                        callback?.onResult(response)
                        destroy()
                    }
                } catch (e: Exception) {
                    callback?.onError(e)
                    destroy()
                }
            }
        }

        // 新增：onPageStarted 注入 JS 嗅探脚本（覆写 fetch/XHR，参考 M3U8 Link Finder bookmarklet）
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            videoSniffJs?.let { js ->
                view?.evaluateJavascript(js, null)
            }
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            if (checkOverrideUrlHit(view, request.url.toString())) {
                return true
            }
            return super.shouldOverrideUrlLoading(view, request)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            if (checkOverrideUrlHit(view, url)) {
                return true
            }
            return super.shouldOverrideUrlLoading(view, url)
        }

        private fun checkOverrideUrlHit(view: WebView, requestUrl: String): Boolean {
            overrideUrlRegex?.let {
                if (requestUrl.matches(it.toRegex())) {
                    try {
                        // R-P1-1/AD-01：记录命中现场上下文
                        val candidate = SniffCandidate.fromWebViewHit(
                            url = requestUrl,
                            pageUrl = view.url,
                            userAgent = view.settings?.userAgentString,
                            source = SniffCandidate.SOURCE_WEBVIEW_OVERRIDE
                        )
                        // video-sniff-403-and-rss-classic-fix Phase 4 (5.3)：首命中交付，后续仅入缓冲
                        if (recordSniffHit(candidate, deliverable = !isTsSegmentUrl(requestUrl))) {
                            val response = StrResponse(url!!, requestUrl)
                            callback?.onResult(response)
                            destroy()
                        }
                    } catch (e: Exception) {
                        callback?.onError(e)
                        destroy()
                    }
                    return true
                }
            }
            return false
        }

        override fun onLoadResource(view: WebView, resUrl: String) {
            sourceRegex?.let {
                if (resUrl.matches(it.toRegex())) {
                    try {
                        // R-P1-1/AD-01：记录命中现场上下文
                        val candidate = SniffCandidate.fromWebViewHit(
                            url = resUrl,
                            pageUrl = view.url,
                            userAgent = view.settings?.userAgentString,
                            source = SniffCandidate.SOURCE_WEBVIEW_RESOURCE
                        )
                        // video-sniff-403-and-rss-classic-fix Phase 4 (5.3)：首命中交付，后续仅入缓冲
                        if (recordSniffHit(candidate, deliverable = !isTsSegmentUrl(resUrl))) {
                            val response = StrResponse(url!!, resUrl)
                            callback?.onResult(response)
                            destroy()
                        }
                    } catch (e: Exception) {
                        callback?.onError(e)
                    }
                }
            }
        }

        override fun onPageFinished(webView: WebView, url: String) {
            setCookie(url)
            if (!javaScript.isNullOrEmpty()) {
                val runnable = LoadJsRunnable(webView, javaScript)
                mHandler.postDelayed(runnable, 100L + delayTime)
            }
            // 优化1+2：videoSniffJs 非空时，delayTime 后读取 window.__videoUrls__
            // delayTime 从 onPageFinished 开始计时（自适应慢站点，页面加载时间不计入 delayTime）
            if (!videoSniffJs.isNullOrEmpty()) {
                val readRunnable = ReadVideoUrlsRunnable(webView, sourceRegex)
                mHandler.postDelayed(readRunnable, 200L + delayTime)  // 200L 确保 JS hook 已执行
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?
        ) {
            handler?.proceed()
        }

        private inner class LoadJsRunnable(
            webView: WebView,
            private val mJavaScript: String?
        ) : Runnable {
            private val mWebView: WeakReference<WebView> = WeakReference(webView)
            override fun run() {
                mWebView.get()?.loadUrl("javascript:${mJavaScript}")
            }
        }

        // 优化1：新增 ReadVideoUrlsRunnable 读取 window.__videoUrls__，作为 shouldInterceptRequest 和 onLoadResource 的兜底
        // 覆盖 video.src 直接赋值、MSE blob 等 shouldInterceptRequest/onLoadResource 无法捕获的场景
        private inner class ReadVideoUrlsRunnable(
            webView: WebView,
            private val regex: String?
        ) : Runnable {
            private val mWebView: WeakReference<WebView> = WeakReference(webView)
            override fun run() {
                if (closed || callback == null) return  // 防御：destroy 后不处理
                mWebView.get()?.evaluateJavascript("JSON.stringify(window.__videoUrls__ || [])") { result ->
                    if (closed || callback == null) return@evaluateJavascript  // 防御
                    if (result.isNullOrEmpty() || result == "null" || result == "[]") {
                        AppLog.putInfo("R5网络抓包: window.__videoUrls__ 为空, 等待 shouldInterceptRequest 或超时")
                        return@evaluateJavascript
                    }
                    // FR-9: 先用 GSON 解析（源码原有方式），失败时正则提取容错
                    val urls = GSON.fromJsonArray<String>(result).getOrNull()
                        ?: run {
                            AppLog.putWarn("R5网络抓包: GSON 解析 window.__videoUrls__ 失败, 尝试正则提取")
                            extractUrlsByRegex(result)
                        }
                    if (urls.isNullOrEmpty()) {
                        AppLog.putWarn("R5网络抓包: window.__videoUrls__ 解析失败（GSON + 正则均失败）")
                        return@evaluateJavascript
                    }
                    for (url in urls) {
                        if (regex != null && url.matches(regex.toRegex())) {
                            AppLog.putInfo("R5网络抓包: window.__videoUrls__ 命中")
                            // R-P1-1/AD-01：记录命中现场上下文（JS 运行时命中，页面上下文取当前 WebView）
                            val candidate = SniffCandidate.fromWebViewHit(
                                url = url,
                                pageUrl = mWebView.get()?.url,
                                userAgent = mWebView.get()?.settings?.userAgentString,
                                source = SniffCandidate.SOURCE_WEBVIEW_RUNTIME
                            )
                            // video-sniff-403-and-rss-classic-fix Phase 4 (5.3)：首命中交付，后续仅入缓冲
                            if (recordSniffHit(candidate, deliverable = !isTsSegmentUrl(url))) {
                                val response = StrResponse(this@BackstageWebView.url!!, url)
                                callback?.onResult(response)
                                destroy()
                            }
                            return@evaluateJavascript
                        }
                    }
                    AppLog.putInfo("R5网络抓包: window.__videoUrls__ 有 ${urls.size} 个 URL 但无匹配")
                }
            }
        }

        // FR-9（I2 整改）: 正则提取容错，覆盖 m3u8/mp4/mkv/flv/ts/mp3/m4a/aac/mpd（与 VIDEO_SOURCE_REGEX 对齐）
        // video-sniff-403-and-rss-classic-fix Phase 4 (5.2)：纳入 .ts（HLS 分片）——
        // 分片命中经 recordSniffHit 仅入候选缓冲参与评分（5.3），交付仍需匹配源正则，
        // "主清单优先于分片"的既有语义不变（原"移除 .ts"动机由评分体系消解）
        private fun extractUrlsByRegex(jsonStr: String): List<String> {
            val urlRegex = Regex(
                """https?://[^\s"'<>]+\.(?:m3u8|mp4|mkv|flv|ts|mp3|m4a|aac|mpd)(?:\?[^\s"'<>]*)?""",
                RegexOption.IGNORE_CASE
            )
            return urlRegex.findAll(jsonStr).map { it.value }.toList()
        }
    }

    companion object {
        const val JS = "document.documentElement.outerHTML"
        private val quoteRegex = "^\"|\"$".toRegex()

        /** video-sniff-403-and-rss-classic-fix Phase 4 (5.3)：候选缓冲容量上限（防极端页面膨胀） */
        private const val MAX_SNIFF_CANDIDATES = 8
    }

    abstract class Callback {
        abstract fun onResult(response: StrResponse)
        abstract fun onError(error: Throwable)
    }
}