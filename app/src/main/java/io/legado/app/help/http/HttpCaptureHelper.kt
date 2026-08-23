package io.legado.app.help.http

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewPool
import io.legado.app.utils.get
import io.legado.app.utils.runOnUI
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume

object HttpCaptureHelper {

    data class Config(
        val url: String,
        val source: BaseSource? = null,
        val waitMs: Long = 5_000L,
        val timeoutMs: Long = 30_000L,
        val includeRegex: String? = null,
        val excludeRegex: String? = null,
        val maxRequests: Int = 50,
        val maxBodyChars: Int = 20_000,
        val replayResponse: Boolean = true,
        val replayTimeoutMs: Long = 8_000L,
        val maxReplayRequests: Int = 5,
        val replayOnlyMatched: Boolean = true,
        val js: String? = null,
        val coroutineContext: CoroutineContext = EmptyCoroutineContext
    )

    private data class CapturedRequest(
        val sequence: Int,
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val isForMainFrame: Boolean,
        val hasGesture: Boolean,
        val time: Long,
        val matched: Boolean
    )

    suspend fun capture(config: Config): JSONObject {
        require(config.url.startsWith("http://") || config.url.startsWith("https://")) {
            "Only http/https url is supported"
        }
        val include = compileRegex(config.includeRegex)
        val exclude = compileRegex(config.excludeRegex)
        val requests = Collections.synchronizedList(mutableListOf<CapturedRequest>())
        val sequence = AtomicInteger(0)
        val finished = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        var pooledWebView: PooledWebView? = null
        fun releaseWebView() {
            handler.removeCallbacksAndMessages(null)
            pooledWebView?.let { WebViewPool.release(it) }
            pooledWebView = null
        }
        try {
            return withTimeout(config.timeoutMs.coerceIn(10_000L, 90_000L)) {
                val loadResult = suspendCancellableCoroutine<JSONObject> { continuation ->
                    continuation.invokeOnCancellation {
                        runOnUI {
                            if (finished.compareAndSet(false, true)) {
                                releaseWebView()
                            }
                        }
                    }
                    runOnUI {
                        try {
                            pooledWebView = WebViewPool.acquire(appCtx)
                            val webView = pooledWebView?.realWebView ?: run {
                                if (!continuation.isCompleted) {
                                    continuation.resume(errorJson("WebView acquire failed"))
                                }
                                return@runOnUI
                            }
                            initWebView(webView, config)
                            webView.webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): android.webkit.WebResourceResponse? {
                                    recordRequest(
                                        request = request,
                                        include = include,
                                        exclude = exclude,
                                        maxRequests = config.maxRequests,
                                        requests = requests,
                                        sequence = sequence
                                    )
                                    return super.shouldInterceptRequest(view, request)
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    if (finished.get()) return
                                    if (!config.js.isNullOrBlank()) {
                                        view.evaluateJavascript(config.js, null)
                                    }
                                    handler.removeCallbacksAndMessages(null)
                                    handler.postDelayed({
                                        if (finished.compareAndSet(false, true) && !continuation.isCompleted) {
                                            val result = JSONObject().apply {
                                                put("ok", true)
                                                put("url", config.url)
                                                put("finalUrl", view.url.orEmpty())
                                                put("requestCount", requests.size)
                                                put("captureStatus", "captured")
                                                put("captureMode", "request_log_replay")
                                            }
                                            continuation.resume(result)
                                        }
                                        releaseWebView()
                                    }, config.waitMs.coerceIn(500L, 30_000L))
                                }
                            }
                            webView.loadUrl(config.url, config.source?.getHeaderMap(true).orEmpty())
                        } catch (error: Throwable) {
                            if (finished.compareAndSet(false, true)) {
                                releaseWebView()
                            }
                            if (!continuation.isCompleted) {
                                continuation.resume(errorJson(error.localizedMessage ?: error.javaClass.simpleName))
                            }
                        }
                    }
                }
                val array = JSONArray()
                val snapshot = requests.toList()
                val replayCandidates = pickReplayCandidates(snapshot, config)
                snapshot.forEach { item ->
                    array.put(buildRequestJson(item, config, replayCandidates.contains(item.sequence)))
                }
                loadResult.put("requests", array)
                loadResult.put("matchedRequestCount", snapshot.count { it.matched })
                loadResult.put("replayedRequestCount", replayCandidates.size)
                if (snapshot.isEmpty()) {
                    loadResult.put("emptyReason", "no_requests_captured")
                } else if (config.replayResponse && replayCandidates.isEmpty()) {
                    loadResult.put("emptyReason", "no_matched_request")
                }
                loadResult
            }
        } finally {
            runOnUI {
                if (finished.compareAndSet(false, true)) {
                    releaseWebView()
                }
            }
        }
    }

    private fun recordRequest(
        request: WebResourceRequest,
        include: Regex?,
        exclude: Regex?,
        maxRequests: Int,
        requests: MutableList<CapturedRequest>,
        sequence: AtomicInteger
    ) {
        val url = request.url.toString()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val matched = include?.containsMatchIn(url) ?: isLikelyApiRequest(url, request)
        if (include != null && !matched) return
        if (exclude != null && exclude.containsMatchIn(url)) return
        synchronized(requests) {
            if (requests.any { it.url == url && it.method == request.method }) return
            if (requests.size >= maxRequests.coerceIn(1, 200)) return
            requests += CapturedRequest(
                sequence = sequence.incrementAndGet(),
                url = url,
                method = request.method.orEmpty().ifBlank { "GET" },
                headers = request.requestHeaders.orEmpty(),
                isForMainFrame = request.isForMainFrame,
                hasGesture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    request.hasGesture()
                } else {
                    false
                },
                time = System.currentTimeMillis(),
                matched = matched
            )
        }
    }

    private fun pickReplayCandidates(
        requests: List<CapturedRequest>,
        config: Config
    ): Set<Int> {
        if (!config.replayResponse) return emptySet()
        val limit = config.maxReplayRequests.coerceIn(1, 20)
        val replayable = requests.filter { it.method.equals("GET", true) || it.method.equals("HEAD", true) }
        val matched = replayable.filter { it.matched }
        val candidates = if (config.replayOnlyMatched) matched else matched.ifEmpty { replayable }
        return candidates.take(limit).map { it.sequence }.toSet()
    }

    private suspend fun buildRequestJson(
        item: CapturedRequest,
        config: Config,
        shouldReplay: Boolean
    ): JSONObject {
        val json = JSONObject().apply {
            put("sequence", item.sequence)
            put("url", item.url)
            put("method", item.method)
            put("headers", JSONObject(item.headers))
            put("isForMainFrame", item.isForMainFrame)
            put("hasGesture", item.hasGesture)
            put("time", item.time)
            put("matched", item.matched)
        }
        if (!config.replayResponse) {
            json.put("replayed", false)
            json.put("replayStatus", "not_requested")
            json.put("emptyReason", "replay_disabled")
            return json
        }
        if (!item.method.equals("GET", true) && !item.method.equals("HEAD", true)) {
            json.put("replayed", false)
            json.put("replayStatus", "skipped_method")
            json.put("emptyReason", "method_not_replayable")
            json.put("replaySkipReason", "only GET/HEAD requests are replayed")
            return json
        }
        if (!shouldReplay) {
            json.put("replayed", false)
            json.put("replayStatus", if (config.replayOnlyMatched) "skipped_not_matched" else "not_requested")
            json.put("emptyReason", "no_matched_request")
            return json
        }
        val replayTimeout = config.replayTimeoutMs.coerceIn(2_000L, 30_000L)
        val replayResult = withTimeoutOrNull(replayTimeout) {
            runCatching {
                val headers = buildReplayHeaders(item, config)
                json.put("effectiveHeaders", JSONObject(headers.first))
                json.put("headerSources", JSONObject(headers.second))
                val client = okHttpClient.newBuilder()
                    .callTimeout(replayTimeout, TimeUnit.MILLISECONDS)
                    .readTimeout(replayTimeout, TimeUnit.MILLISECONDS)
                    .build()
                val response = client.newCallStrResponse {
                    url(item.url)
                    addHeaders(headers.first)
                    if (item.method.equals("HEAD", true)) {
                        head()
                    }
                }
                val body = response.body.orEmpty()
                json.put("replayed", true)
                json.put("statusCode", response.code())
                json.put("message", response.message())
                json.put("finalUrl", response.url)
                json.put("contentType", response.raw.header("Content-Type").orEmpty())
                json.put("contentLength", response.raw.body.contentLength())
                json.put("bodyLength", body.length)
                json.put("truncated", body.length > config.maxBodyChars)
                json.put("body", body.take(config.maxBodyChars.coerceIn(0, 80_000)))
                json.put("replayStatus", replayStatus(response.code(), body))
                emptyReason(response.code(), body, response.raw.header("Content-Type")).let {
                    if (it != null) json.put("emptyReason", it)
                }
            }
        }
        if (replayResult == null) {
            json.put("replayed", true)
            json.put("replayStatus", "timeout")
            json.put("emptyReason", "request_timeout")
        } else {
            replayResult.onFailure {
                json.put("replayed", true)
                json.put("replayStatus", "network_error")
                json.put("emptyReason", "response_body_read_error")
                json.put("error", it.localizedMessage ?: it.javaClass.simpleName)
            }
        }
        return json
    }

    private fun buildReplayHeaders(
        item: CapturedRequest,
        config: Config
    ): Pair<Map<String, String>, Map<String, String>> {
        val headers = linkedMapOf<String, String>()
        val sources = linkedMapOf<String, String>()
        item.headers.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                headers[key] = value
            }
        }
        if (headers.none { it.key.equals(AppConst.UA_NAME, true) }) {
            headers[AppConst.UA_NAME] = config.source?.getHeaderMap(true)?.get(AppConst.UA_NAME, true)
                ?: AppConfig.userAgent
            sources["ua"] = "source_or_app"
        } else {
            sources["ua"] = "captured"
        }
        if (headers.none { it.key.equals("Referer", true) }) {
            headers["Referer"] = config.url
            sources["referer"] = "page_url"
        } else {
            sources["referer"] = "captured"
        }
        val capturedCookie = headers.entries.firstOrNull { it.key.equals("Cookie", true) }?.value
        val webViewCookie = android.webkit.CookieManager.getInstance().getCookie(item.url)
        val storeCookie = CookieStore.getCookie(item.url)
        val cookie = CookieManager.mergeCookies(capturedCookie, webViewCookie, storeCookie)
        if (!cookie.isNullOrBlank()) {
            headers.keys.firstOrNull { it.equals("Cookie", true) }?.let { headers.remove(it) }
            headers["Cookie"] = cookie
            sources["cookie"] = when {
                !webViewCookie.isNullOrBlank() -> "webview"
                !storeCookie.isNullOrBlank() -> "store"
                !capturedCookie.isNullOrBlank() -> "captured"
                else -> "none"
            }
        } else {
            sources["cookie"] = "none"
        }
        return headers to sources
    }

    private fun replayStatus(code: Int, body: String): String {
        return when {
            code == 204 || code == 304 || body.isEmpty() -> "empty_body"
            code in 200..299 -> "success"
            else -> "http_error"
        }
    }

    private fun emptyReason(code: Int, body: String, contentType: String?): String? {
        return when {
            code == 204 || code == 304 -> "http_204_304"
            body.isEmpty() -> "zero_length_body"
            contentType != null && !contentType.contains("text", true)
                    && !contentType.contains("json", true)
                    && !contentType.contains("xml", true)
                    && !contentType.contains("javascript", true) -> "non_text_response"
            else -> null
        }
    }

    private fun isLikelyApiRequest(url: String, request: WebResourceRequest): Boolean {
        if (request.isForMainFrame) return false
        val lower = url.lowercase()
        return lower.contains("/api/") ||
                lower.contains("ajax") ||
                lower.contains("json") ||
                lower.endsWith(".m3u8") ||
                lower.endsWith(".mpd") ||
                !lower.matches(Regex(""".*\.(js|css|png|jpg|jpeg|gif|webp|svg|ico|woff2?)(\?.*)?$"""))
    }

    private fun compileRegex(pattern: String?): Regex? {
        return pattern?.takeIf { it.isNotBlank() }?.let {
            runCatching { it.toRegex() }.getOrElse { error ->
                throw IllegalArgumentException("Invalid regex: ${error.localizedMessage ?: it}")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(webView: WebView, config: Config) {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.onResume()
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            blockNetworkImage = true
            loadsImagesAutomatically = false
            userAgentString = config.source?.getHeaderMap(true)?.get(AppConst.UA_NAME, true)
                ?: AppConfig.userAgent
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }
        }
    }

    private fun errorJson(message: String) = JSONObject().apply {
        put("ok", false)
        put("error", message)
    }
}
