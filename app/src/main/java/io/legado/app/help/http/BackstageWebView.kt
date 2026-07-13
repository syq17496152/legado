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
    private val videoSniffJs: String? = null            // 新增：页面加载前注入的JS（视频嗅探用）
) {

    private val mHandler = Handler(Looper.getMainLooper())
    private var callback: Callback? = null
    private var pooledWebView: PooledWebView? = null
    private var closed = false

    suspend fun getStrResponse(): StrResponse = withTimeout(timeout ?: 60000L) {
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
        val pooledWebView = WebViewPool.acquire(appCtx)
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
                // isVideoFormat 第1层：排除嵌套URL（参考 Sniffer.java isVideoFormat 第3步）
                // 避免 ?url=https://cdn.com/video.m3u8 重定向URL误匹配
                if (resUrl.contains("url=http") || resUrl.contains("v=http") || resUrl.contains(".html")) {
                    return null  // 跳过嵌套URL，不拦截
                }
                // isVideoFormat 第2层：sourceRegex 匹配
                sourceRegex?.let { regex ->
                    if (resUrl.matches(regex.toRegex())) {
                        try {
                            val response = StrResponse(url!!, resUrl)
                            callback?.onResult(response)
                        } catch (e: Exception) {
                            callback?.onError(e)
                        }
                        // 修复P0崩溃：shouldInterceptRequest 在工作线程调用（chromium 设计），
                        // destroy() 内部 WebViewPool.release 操作 webView.setLayoutParams 等 View 方法，
                        // 必须切到 UI 线程执行，否则抛 IllegalStateException: Calling View methods on another thread than the UI thread
                        // 崩溃证据：crash-2026-07-13-14-53-47 + logcat L4258 shouldInterceptRequest(BackstageWebView.kt:354)
                        AppLog.putInfo("R5网络抓包命中(工作线程), post到UI线程执行destroy")
                        mHandler.post { destroy() }
                    }
                }
            }
            return null  // 返回 null 表示不拦截，让请求正常发出
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
            if (shouldOverrideUrlLoading(request.url.toString())) {
                return true
            }
            return super.shouldOverrideUrlLoading(view, request)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            if (shouldOverrideUrlLoading(url)) {
                return true
            }
            return super.shouldOverrideUrlLoading(view, url)
        }

        private fun shouldOverrideUrlLoading(requestUrl: String): Boolean {
            overrideUrlRegex?.let {
                if (requestUrl.matches(it.toRegex())) {
                    try {
                        val response = StrResponse(url!!, requestUrl)
                        callback?.onResult(response)
                    } catch (e: Exception) {
                        callback?.onError(e)
                    }
                    destroy()
                    return true
                }
            }
            return false
        }

        override fun onLoadResource(view: WebView, resUrl: String) {
            sourceRegex?.let {
                if (resUrl.matches(it.toRegex())) {
                    try {
                        val response = StrResponse(url!!, resUrl)
                        callback?.onResult(response)
                    } catch (e: Exception) {
                        callback?.onError(e)
                    }
                    destroy()
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
                    val urls = GSON.fromJsonArray<String>(result).getOrNull()
                    if (urls == null) {
                        AppLog.putWarn("R5网络抓包: 解析 window.__videoUrls__ 失败")
                        return@evaluateJavascript
                    }
                    for (url in urls) {
                        if (regex != null && url.matches(regex.toRegex())) {
                            AppLog.putInfo("R5网络抓包: window.__videoUrls__ 命中")
                            val response = StrResponse(this@BackstageWebView.url!!, url)
                            callback?.onResult(response)
                            destroy()
                            return@evaluateJavascript
                        }
                    }
                    AppLog.putInfo("R5网络抓包: window.__videoUrls__ 有 ${urls.size} 个 URL 但无匹配")
                }
            }
        }

    }

    companion object {
        const val JS = "document.documentElement.outerHTML"
        private val quoteRegex = "^\"|\"$".toRegex()
    }

    abstract class Callback {
        abstract fun onResult(response: StrResponse)
        abstract fun onError(error: Throwable)
    }
}