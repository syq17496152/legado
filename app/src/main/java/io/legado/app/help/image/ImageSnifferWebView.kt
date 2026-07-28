package io.legado.app.help.image

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.constant.AppLog
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewPool
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.get
import io.legado.app.utils.runOnUI
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 图片嗅探 WebView（批量 URL 收集）
 *
 * 与 BackstageWebView 的区别：
 * - BackstageWebView：命中**单个** URL 后立即 destroy（视频嗅探场景）
 * - ImageSnifferWebView：收集**所有**图片 URL，超时后返回完整列表（图片嗅探场景）
 *
 * 复用基础设施：
 * - WebViewPool.acquire / release（WebView 复用池）
 * - IMAGE_SNIFF_JS（5 路 hook：Image.src/fetch/XHR/IntersectionObserver/document.write）
 * - shouldInterceptRequest 拦截图片资源请求
 *
 * 设计要点（参考 BackstageWebView 坑点）：
 * 1. shouldInterceptRequest 在工作线程调用，destroy 必须切到 UI 线程
 * 2. 协程取消时同步销毁 WebView（invokeOnCancellation）
 * 3. closed 标志位防御：destroy 后回调直接 return
 * 4. blockNetworkImage = false（让图片请求正常发起，shouldInterceptRequest 才能拦截）
 */
class ImageSnifferWebView(
    private val url: String,
    private val headerMap: HashMap<String, String>? = null,
    private val tag: String? = null,
    private val timeout: Long = 8000L,
    private val delayTime: Long = 1500L
) {

    private val mHandler = Handler(Looper.getMainLooper())
    private var pooledWebView: PooledWebView? = null
    @Volatile
    private var closed = false

    /** 已收集的图片 URL 集合（ConcurrentHashMap 线程安全，shouldInterceptRequest 在工作线程调用） */
    private val collectedUrls = ConcurrentHashMap.newKeySet<String>()

    /**
     * 嗅探图片 URL 列表
     *
     * 流程：
     * 1. acquire WebView
     * 2. onPageStarted 注入 IMAGE_SNIFF_JS（hook Image.src/fetch/XHR/IO/document.write）
     * 3. shouldInterceptRequest 拦截 Content-Type 为 image 的资源
     * 4. onPageFinished 后 delayTime 读取 window.__imageUrls__（JS hook 收集的 URL）
     * 5. 合并 shouldInterceptRequest + JS hook 结果，返回完整列表
     * 6. 超时或协程取消时 destroy WebView
     *
     * @return List<String> 图片 URL 列表（可能为空，不抛异常）
     */
    suspend fun sniffImageUrls(): List<String> {
        if (url.isBlank()) return emptyList()
        return try {
            withTimeoutOrNull(timeout) {
                suspendCancellableCoroutine<List<String>> { block ->
                    block.invokeOnCancellation {
                        runOnUI { destroy() }
                    }
                    runOnUI {
                        try {
                            loadAndSniff(block)
                        } catch (e: Throwable) {
                            AppLog.putDebugWithTag(
                                AppLog.TAG_IMAGE_SNIFF,
                                "sniffImageUrls loadAndSniff error: ${e::class.simpleName} ${e.message?.take(100)}",
                                throwable = e,
                                level = AppLog.Level.ERROR
                            )
                            destroy()
                            if (block.context.isActive) {
                                block.resume(emptyList())
                            }
                        }
                    }
                }
            } ?: run {
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_SNIFF,
                    "sniffImageUrls timeout: url=${sanitizeUrl(url)} collected=${collectedUrls.size}",
                    level = AppLog.Level.WARN
                )
                synchronized(collectedUrls) { collectedUrls.toList() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // 协程取消必须传播
        } catch (e: Exception) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_SNIFF,
                "sniffImageUrls error: ${e::class.simpleName} ${e.message?.take(100)}",
                throwable = e,
                level = AppLog.Level.ERROR
            )
            emptyList()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadAndSniff(block: kotlin.coroutines.Continuation<List<String>>) {
        val pooled = WebViewPool.acquire(appCtx)
        pooledWebView = pooled
        val webView = pooled.realWebView
        webView.onResume()
        val settings = webView.settings
        // 关键：blockNetworkImage = false，让图片请求正常发起，shouldInterceptRequest 才能拦截
        settings.blockNetworkImage = false
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = headerMap?.get("User-Agent") ?: settings.userAgentString
        // 修复：禁用缓存，避免相同 URL 二次加载命中缓存导致 shouldInterceptRequest 不触发
        // 铁证：read.php articleIndex=1 intercepted=3（首次），articleIndex=2 intercepted=0（42ms onPageFinished 缓存命中）
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.clearCache(true)
        webView.webViewClient = ImageSnifferWebClient(block)
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_SNIFF,
            "loadAndSniff start: url=${sanitizeUrl(url)} timeout=$timeout delayTime=$delayTime",
            level = AppLog.Level.INFO
        )
        // 加载页面（带 headerMap）
        val headers = headerMap?.let { HashMap(it) } ?: hashMapOf()
        webView.loadUrl(url, headers)
    }

    private inner class ImageSnifferWebClient(
        private val block: kotlin.coroutines.Continuation<List<String>>
    ) : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            // 注入 IMAGE_SNIFF_JS（hook Image.src/fetch/XHR/IO/document.write）
            view?.evaluateJavascript(ImageUrlExtractor.IMAGE_SNIFF_JS, null)
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_SNIFF,
                "onPageStarted: IMAGE_SNIFF_JS injected, url=${sanitizeUrl(url)}",
                level = AppLog.Level.INFO
            )
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            // 防御：destroy 后不再处理（shouldInterceptRequest 在工作线程调用，可能延迟）
            if (closed) return null
            val resUrl = request?.url?.toString() ?: return null
            // 排除嵌套 URL（参考 BackstageWebView 第1层判断）
            if (resUrl.contains("url=http") || resUrl.contains("v=http") || resUrl.contains(".html")) {
                return null
            }
            // 匹配图片 URL 正则
            if (resUrl.matches(IMAGE_SOURCE_REGEX.toRegex())) {
                collectedUrls.add(resUrl)
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_SNIFF,
                    "shouldInterceptRequest hit: url=${sanitizeUrl(resUrl)} total=${collectedUrls.size}",
                    level = AppLog.Level.INFO
                )
            }
            // 返回 null = 不拦截，让请求正常发出（图片资源不阻塞页面渲染）
            return null
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_SNIFF,
                "onPageFinished: url=${sanitizeUrl(url)} delayTime=$delayTime intercepted=${collectedUrls.size}",
                level = AppLog.Level.INFO
            )
            // delayTime 后读取 window.__imageUrls__（JS hook 收集的 URL）
            mHandler.postDelayed({
                if (closed) return@postDelayed
                readJsCollectedUrls(view)
            }, delayTime)
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?
        ) {
            handler?.proceed()
        }

        private fun readJsCollectedUrls(view: WebView) {
            view.evaluateJavascript("JSON.stringify(window.__imageUrls__ || [])") { result ->
                // 防御：destroy 后不处理
                if (closed) return@evaluateJavascript
                if (result.isNullOrEmpty() || result == "null" || result == "[]") {
                    AppLog.putDebugWithTag(
                        AppLog.TAG_IMAGE_SNIFF,
                        "readJsCollectedUrls: window.__imageUrls__ empty, fallback to intercepted only, count=${collectedUrls.size}",
                        level = AppLog.Level.WARN
                    )
                    finishSniff()
                    return@evaluateJavascript
                }
                val urls = try {
                    GSON.fromJsonArray<String>(result).getOrNull()
                } catch (e: Exception) {
                    AppLog.putDebugWithTag(
                        AppLog.TAG_IMAGE_SNIFF,
                        "readJsCollectedUrls parse error: ${e::class.simpleName}",
                        throwable = e,
                        level = AppLog.Level.ERROR
                    )
                    null
                }
                if (urls != null) {
                    urls.forEach { url ->
                        if (url.matches(IMAGE_SOURCE_REGEX.toRegex()) ||
                            url.startsWith("http://") || url.startsWith("https://")) {
                            collectedUrls.add(url)
                        }
                    }
                }
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_SNIFF,
                    "readJsCollectedUrls: jsCount=${urls?.size ?: 0} merged=${collectedUrls.size}",
                    level = AppLog.Level.INFO
                )
                finishSniff()
            }
        }

        private fun finishSniff() {
            if (closed) return
            val result = synchronized(collectedUrls) { collectedUrls.toList() }
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_SNIFF,
                "finishSniff: total=${result.size} url=${sanitizeUrl(url)}",
                level = AppLog.Level.INFO
            )
            // 切到 UI 线程 destroy（避免工作线程 View 操作崩溃）
            mHandler.post {
                destroy()
                if (block.context.isActive) {
                    block.resume(result)
                }
            }
        }
    }

    @Synchronized
    private fun destroy() {
        if (closed) return
        closed = true
        pooledWebView?.let { WebViewPool.release(it) }
        pooledWebView = null
    }

    companion object {
        /**
         * 图片 URL 正则（参考 VIDEO_SOURCE_REGEX 设计）
         *
         * 设计要点：
         * - 长度 ≥12 过滤短 URL 误匹配
         * - 排除 data:image/ 内联图片（base64，无法下载且撑爆 logcat）
         * - (?i) 忽略大小写（JPEG/Jpg/jpg 混用）
         * - BackstageWebView 用 resUrl.matches(regex) 全匹配，需 .* 前后通配
         */
        val IMAGE_SOURCE_REGEX = """(?i).*https?://[^\s]{12,}\.(?:jpg|jpeg|png|webp|gif|bmp|svg|avif)(?:\?[^\s]*)?.*"""

        /**
         * URL 脱敏（参考 VideoUrlExtractor.sanitizeUrl）
         * 禁止输出完整 URL（含域名/token/鉴权），只保留路径前 30 字符
         */
        fun sanitizeUrl(url: String?): String {
            if (url.isNullOrBlank()) return "empty"
            return try {
                val u = java.net.URL(url)
                val path = u.path?.take(30) ?: ""
                "path=${path}"
            } catch (e: Exception) {
                "raw=${url.take(20)}"
            }
        }
    }
}
