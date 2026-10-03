package io.legado.app.lib.cronet

import androidx.annotation.Keep
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.CookieManager
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.constant.AppLog
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.DebugLog
import io.legado.app.utils.asIOException
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.delay
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.IOException
import java.net.ProtocolException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


@Keep
abstract class AbsCallBack(
    var originalRequest: Request,
    val mCall: Call,
    var readTimeoutMillis: Int,
    private val eventListener: EventListener? = null,
    private val responseCallback: Callback? = null
) : UrlRequest.Callback() {

    var mResponse: Response
    private var followCount = 0
    private var request: UrlRequest? = null
    private var finished = AtomicBoolean(false)
    private val canceled = AtomicBoolean(false)
    private val callbackResults = ArrayBlockingQueue<CallbackResult>(2)
    private val urlResponseInfoChain = arrayListOf<UrlResponseInfo>()
    private var cancelJob: Coroutine<*>? = null
    private var followRedirect = false
    private var enableCookieJar = false
    private var redirectRequest: Request? = null

    init {
        if (readTimeoutMillis == 0) {
            readTimeoutMillis = Int.MAX_VALUE
        }
        if (originalRequest.header(cookieJarHeader) != null) {
            enableCookieJar = true
            originalRequest = originalRequest.newBuilder()
                .removeHeader(cookieJarHeader).build()
        }
    }


    @Throws(IOException::class)
    abstract fun waitForDone(urlRequest: UrlRequest): Response

    /**
     * 当发生错误时，通知子类终止阻塞抛出错误
     * @param error
     */
    abstract fun onError(error: IOException)

    /**
     * 请求成功后，通知子类结束阻塞，返回response
     * @param response
     */
    abstract fun onSuccess(response: Response)

    /** IF-23：onError 幂等（first-wins），避免「决策分支已上报」+「onCanceled 兜底分支再上报」重复报错 */
    private val errorReported = AtomicBoolean(false)

    /** 幂等错误上报：回调内不得让重复报错/异常逃逸成「既不成功也不失败」的黑洞态 */
    private fun safeError(error: IOException) {
        if (errorReported.compareAndSet(false, true)) {
            onError(error)
        }
    }

    /** 脱敏：只保留路径片段（不输出域名 / 完整 URL，遵守输出安全规范） */
    private fun maskPath(url: String?): String =
        url?.substringAfter("://")?.substringAfter("/")?.take(30) ?: "unknown"

    override fun onRedirectReceived(
        request: UrlRequest,
        info: UrlResponseInfo,
        newLocationUrl: String
    ) {
        if (followCount > MAX_FOLLOW_COUNT) {
            request.cancel()
            safeError(IOException("Too many redirect"))
            return
        }
        if (mCall.isCanceled()) {
            safeError(IOException("Cronet Request Canceled"))
            request.cancel()
            return
        }
        followCount += 1
        urlResponseInfoChain.add(info)

        // IF-23 修复：base 必须取「本次返回 3xx 的响应 URL」。
        // 多跳时本实例被复用，originalRequest 仍停在首跳 URL，用它作 base 会把相对 Location 解析到错误地址。
        val baseUrl = info.url
        val client = okHttpClient
        // Location 优先取 Cronet 解析值；为空时回退响应头（Cronet 未给出时仍可自救）
        val location = runCatching {
            newLocationUrl.ifBlank { info.allHeaders["Location"]?.lastOrNull().orEmpty() }
        }.getOrElse { newLocationUrl }

        val decision = RedirectPolicy.decide(
            baseUrl = baseUrl,
            location = location,
            allowCrossScheme = client.followSslRedirects
        )
        val resolved = (decision as? RedirectPolicy.Decision.Follow)?.url
        // 沿用既有门控语义：跨 scheme 由 followSslRedirects 决定，同 scheme 看 followRedirects
        val crossScheme = resolved != null &&
                RedirectPolicy.schemeOf(baseUrl) != RedirectPolicy.schemeOf(resolved)
        val followEnabled = resolved != null && (crossScheme || client.followRedirects)

        if (!followEnabled) {
            val reason = (decision as? RedirectPolicy.Decision.Reject)?.reason
                ?: "followRedirects disabled"
            AppLog.put(
                "[CronetRedirect] hop=$followCount code=${info.httpStatusCode} " +
                    "path=${maskPath(baseUrl)} action=reject reason=$reason"
            )
            // 不跟随必须“快速失败”：绝不能让无 body 的 3xx 把调用方阻塞到超时
            safeError(IOException("Redirect not followed: $reason"))
            request.cancel()
            return
        }

        // 回调内任何异常都不得逃逸 —— 否则 request.cancel() 不执行、onCanceled 不触发 ⇒ 请求既不成功也不失败（静默挂死）
        val built = runCatching {
            val response = toResponse(originalRequest, info, urlResponseInfoChain)
            if (enableCookieJar) {
                CookieManager.saveResponse(response)
            }
            buildRedirectRequest(response, originalRequest.method, resolved!!)
        }
        val exception = built.exceptionOrNull()
        if (exception == null) {
            redirectRequest = built.getOrThrow()
            followRedirect = true
            AppLog.put(
                "[CronetRedirect] hop=$followCount code=${info.httpStatusCode} " +
                    "path=${maskPath(baseUrl)} action=follow"
            )
        } else {
            followRedirect = false
            redirectRequest = null
            AppLog.put(
                "[CronetRedirect] hop=$followCount build redirect failed: " +
                    "${exception.javaClass.simpleName}: ${exception.message?.take(80)}"
            )
            safeError(IOException("Build redirect request failed", exception))
        }
        request.cancel()
    }


    override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
        this.request = request

        val response: Response
        try {
            response = toResponse(originalRequest, info, urlResponseInfoChain, CronetBodySource())
        } catch (e: IOException) {
            request.cancel()
            cancelJob?.cancel()
            onError(e)
            return
        }

        if (enableCookieJar) {
            CookieManager.saveResponse(response)
        }

        mResponse = response
        onSuccess(response)

        //打印协议，用于调试
        val msg = "onResponseStarted[${info.negotiatedProtocol}][${info.httpStatusCode}]${info.url}"
        DebugLog.i(javaClass.simpleName, msg)
        if (eventListener != null) {
            eventListener.responseHeadersEnd(mCall, response)
            eventListener.responseBodyStart(mCall)
        }
        try {
            responseCallback?.onResponse(mCall, response)
        } catch (e: IOException) {
            // Pass?
        }
    }


    @Throws(IOException::class)
    override fun onReadCompleted(
        request: UrlRequest,
        info: UrlResponseInfo,
        byteBuffer: ByteBuffer
    ) {
        callbackResults.add(CallbackResult(CallbackStep.ON_READ_COMPLETED, byteBuffer))
    }


    override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
        callbackResults.add(CallbackResult(CallbackStep.ON_SUCCESS))
        cancelJob?.cancel()
        eventListener?.responseBodyEnd(mCall, info.receivedByteCount)
        //DebugLog.i(javaClass.simpleName, "end[${info.negotiatedProtocol}]${info.url}")

        eventListener?.callEnd(mCall)
    }


    // Cronet 500：onFailed 的 UrlResponseInfo 参数由可空改为非空（HttpEngine API 收紧）
    override fun onFailed(request: UrlRequest, info: UrlResponseInfo, error: CronetException) {
        callbackResults.add(CallbackResult(CallbackStep.ON_FAILED, null, error))
        cancelJob?.cancel()
        // P2-C 修复：保留原始错误信息，按 AGENTS.md "改造必加日志"规范用 AppLog.put 永久记录
        // 根因：error.asIOException() 会把 CronetException("System error") 转为 IOException，丢失 PROTOCOL_ERROR 根因
        // 脱敏：只保留路径片段，不输出完整域名/URL（P0 输出安全规范）
        val protocol = info.negotiatedProtocol
        val httpCode = info.httpStatusCode
        val urlPath = info.url.substringAfter("://").substringAfter("/").take(50)
        DebugLog.e(javaClass.name, "onFailed: protocol=$protocol, httpCode=$httpCode, error=${error.message}")
        AppLog.put("Cronet 请求失败: protocol=$protocol, httpCode=$httpCode, path=$urlPath, error=${error.message}")
        safeError(error.asIOException())
        eventListener?.callFailed(mCall, error)
        responseCallback?.onFailure(mCall, error)
    }

    override fun onCanceled(request: UrlRequest, info: UrlResponseInfo) {
        val target = redirectRequest
        if (followRedirect && target != null) {
            followRedirect = false
            redirectRequest = null
            // 发下一跳：构造/启动失败必须上报，绝不能静默（否则退回「既不成功也不失败」的黑洞态）
            val started = runCatching {
                val next = if (enableCookieJar) CookieManager.loadRequest(target) else target
                buildRequest(next, this)?.also { it.start() }
            }.getOrNull()
            if (started == null) {
                AppLog.put("[CronetRedirect] next hop not started (buildRequest null or threw)")
                safeError(IOException("Cronet next redirect hop not started"))
            }
            return
        }
        canceled.set(true)
        callbackResults.add(CallbackResult(CallbackStep.ON_CANCELED))
        cancelJob?.cancel()
        //DebugLog.i(javaClass.simpleName, "cancel[${info?.negotiatedProtocol}]${info?.url}")
        eventListener?.callEnd(mCall)
        safeError(IOException("Cronet Request Canceled"))
    }

    fun startCheckCancelJob(request: UrlRequest) {
        cancelJob = Coroutine.async {
            while (!mCall.isCanceled()) {
                delay(1000)
            }
            request.cancel()
        }
    }

    init {
        mResponse = Response.Builder()
            .sentRequestAtMillis(System.currentTimeMillis())
            .request(originalRequest)
            .protocol(Protocol.HTTP_1_0)
            .code(0)
            .message("")
            .build()
    }

    companion object {
        const val MAX_FOLLOW_COUNT = 20
        private val encodingsHandledByCronet = setOf("br", "deflate", "gzip", "x-gzip")

        private fun protocolFromNegotiatedProtocol(responseInfo: UrlResponseInfo): Protocol {
            val negotiatedProtocol = responseInfo.negotiatedProtocol.lowercase(Locale.getDefault())
            return when {
                negotiatedProtocol.contains("h3") -> {
                    Protocol.QUIC
                }

                negotiatedProtocol.contains("quic") -> {
                    Protocol.QUIC
                }

                negotiatedProtocol.contains("spdy") -> {
                    @Suppress("DEPRECATION")
                    Protocol.SPDY_3
                }

                negotiatedProtocol.contains("h2") -> {
                    Protocol.HTTP_2
                }

                negotiatedProtocol.contains("1.1") -> {
                    Protocol.HTTP_1_1
                }

                else -> {
                    Protocol.HTTP_1_0
                }
            }
        }

        private fun headersFromResponse(
            responseInfo: UrlResponseInfo,
            keepEncodingAffectedHeaders: Boolean
        ): Headers {

            val headers = responseInfo.allHeadersAsList
            return Headers.Builder().apply {
                for ((key, value) in headers) {
                    try {

                        if (!keepEncodingAffectedHeaders
                            && (key.equals("content-encoding", ignoreCase = true)
                                    || key.equals("Content-Length", ignoreCase = true))
                        ) {
                            // Strip all content encoding headers as decoding is done handled by cronet
                            continue
                        }
                        add(key, value)
                    } catch (e: Exception) {
                        DebugLog.w(javaClass.name, "Invalid HTTP header/value: $key$value")
                        // Ignore that header
                    }
                }

            }.build()

        }

        @Throws(IOException::class)
        private fun createResponse(
            request: Request,
            responseInfo: UrlResponseInfo,
            bodySource: Source? = null
        ): Response.Builder {
            val protocol = protocolFromNegotiatedProtocol(responseInfo)

            val contentEncodingHeaders =
                responseInfo.allHeaders.getOrDefault("content-encoding", emptyList())
            val contentEncodingItems = contentEncodingHeaders.flatMap {
                it.splitNotBlank(",").toList()
            }
            val keepEncodingAffectedHeaders = contentEncodingItems.isEmpty()
                    || !encodingsHandledByCronet.containsAll(contentEncodingItems)

            // Issue7 调试日志：记录 Cronet 响应的内容编码处理（脱敏：只保留路径片段）
            val urlPath = responseInfo.url?.substringAfter("://")?.substringAfter("/")?.take(40) ?: "unknown"
            val httpCode = responseInfo.httpStatusCode
            AppLog.put("[CronetDebug] createResponse: path=$urlPath, httpCode=$httpCode, " +
                "contentEncoding=$contentEncodingItems, keepHeaders=$keepEncodingAffectedHeaders, " +
                "hasBodySource=${bodySource != null}, negotiatedProtocol=${responseInfo.negotiatedProtocol}")

            val headers = headersFromResponse(responseInfo, keepEncodingAffectedHeaders)
            val contentLength = if (keepEncodingAffectedHeaders) {
                responseInfo.allHeaders["Content-Length"]?.lastOrNull()
            } else null
            val contentType = responseInfo.allHeaders["content-type"]?.lastOrNull()
                ?: "text/plain; charset=\"utf-8\""

            val responseBody = bodySource?.let {
                createResponseBody(
                    request,
                    responseInfo.httpStatusCode,
                    contentType,
                    contentLength,
                    bodySource
                )
            } ?: ResponseBody.EMPTY

            return Response.Builder()
                .request(request)
                .receivedResponseAtMillis(System.currentTimeMillis())
                .protocol(protocol)
                .code(responseInfo.httpStatusCode)
                .message(responseInfo.httpStatusText)
                .headers(headers)
                .body(responseBody)
        }

        private fun buildPriorResponse(
            request: Request,
            redirectResponseInfos: List<UrlResponseInfo>,
        ): Response? {
            var priorResponse: Response? = null
            if (redirectResponseInfos.isNotEmpty()) {
                for (i in redirectResponseInfos.indices) {
                    val url = redirectResponseInfos[i].url
                    val redirectedRequest = request.newBuilder().url(url).build()
                    priorResponse = createResponse(redirectedRequest, redirectResponseInfos[i])
                        .priorResponse(priorResponse)
                        .build()
                }

            }
            return priorResponse
        }

        @Throws(IOException::class)
        private fun createResponseBody(
            request: Request,
            httpStatusCode: Int,
            contentType: String?,
            contentLengthString: String?,
            bodySource: Source
        ): ResponseBody {

            // Ignore content-length header for HEAD requests (consistency with OkHttp)
            val contentLength: Long = if (request.method == "HEAD") {
                0
            } else {
                contentLengthString?.toLongOrNull() ?: -1
            }

            // Check for absence of body in No Content / Reset Content responses (OkHttp consistency)
            if ((httpStatusCode == 204 || httpStatusCode == 205) && contentLength > 0) {
                throw ProtocolException(
                    "HTTP $httpStatusCode had non-zero Content-Length: $contentLengthString"
                )
            }
            return bodySource.buffer()
                .asResponseBody(contentType?.toMediaTypeOrNull(), contentLength)
        }

        private fun buildRedirectRequest(
            userResponse: Response,
            method: String,
            newLocationUrl: String
        ): Request {
            // Most redirects don't include a request body.
            val requestBuilder = userResponse.request.newBuilder()
            if (permitsRequestBody(method)) {
                val responseCode = userResponse.code
                val maintainBody = redirectsWithBody(method) ||
                        responseCode == HTTP_PERM_REDIRECT ||
                        responseCode == HTTP_TEMP_REDIRECT
                if (redirectsToGet(method)
                    && responseCode != HTTP_PERM_REDIRECT
                    && responseCode != HTTP_TEMP_REDIRECT
                ) {
                    requestBuilder.method("GET", null)
                } else {
                    val requestBody = if (maintainBody) userResponse.request.body else null
                    requestBuilder.method(method, requestBody)
                }
                if (!maintainBody) {
                    requestBuilder.removeHeader("Transfer-Encoding")
                    requestBuilder.removeHeader("Content-Length")
                    requestBuilder.removeHeader("Content-Type")
                }
            }

            return requestBuilder.url(newLocationUrl).build()
        }

        /** 替代 okhttp3.internal.http.HttpMethod 的常量 */
        private const val HTTP_PERM_REDIRECT = 308
        private const val HTTP_TEMP_REDIRECT = 307

        /** 替代 HttpMethod.permitsRequestBody：POST/PUT/PATCH/DELETE/PROPPATCH/REPORT 允许 body */
        private fun permitsRequestBody(method: String): Boolean = when (method) {
            "GET", "HEAD", "OPTIONS", "TRACE", "CONNECT" -> false
            else -> true
        }

        /** 替代 HttpMethod.redirectsWithBody：PROPFIND/PROPPATCH/MKACTIVITY/MKCALENDAR/REPORT 保持 body */
        private fun redirectsWithBody(method: String): Boolean = when (method) {
            "PROPFIND", "PROPPATCH", "MKACTIVITY", "MKCALENDAR", "REPORT" -> true
            else -> false
        }

        /** 替代 HttpMethod.redirectsToGet：除 PROPFIND 外的重定向都转为 GET */
        private fun redirectsToGet(method: String): Boolean = method != "PROPFIND"

        private fun toResponse(
            request: Request,
            responseInfo: UrlResponseInfo,
            redirectResponseInfos: List<UrlResponseInfo>,
            bodySource: Source? = null
        ): Response {
            val responseBuilder = createResponse(request, responseInfo, bodySource)
            val newRequest = request.newBuilder().url(responseInfo.url).build()
            return responseBuilder
                .request(newRequest)
                .priorResponse(buildPriorResponse(request, redirectResponseInfos))
                .build()
        }
    }

    inner class CronetBodySource : Source {

        private var buffer = ByteBuffer.allocateDirect(32 * 1024)
        private var closed = false
        private val timeout = readTimeoutMillis.toLong()

        override fun close() {
            cancelJob?.cancel()
            if (closed) {
                return
            }
            closed = true
            if (!finished.get()) {
                request?.cancel()
            }
        }

        @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (canceled.get()) {
                throw IOException("Cronet Request Canceled")
            }

            require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
            check(!closed) { "closed" }

            if (finished.get()) {
                return -1
            }

            if (byteCount < buffer.limit()) {
                buffer.limit(byteCount.toInt())
            }

            request?.read(buffer)

            val result = callbackResults.poll(timeout, TimeUnit.MILLISECONDS)
            if (result == null) {
                request?.cancel()
                throw IOException("Cronet request body read timeout after wait $timeout ms")
            }

            return when (result.callbackStep) {
                CallbackStep.ON_FAILED -> {
                    finished.set(true)
                    buffer = null
                    throw IOException(result.exception)
                }

                CallbackStep.ON_SUCCESS -> {
                    finished.set(true)
                    buffer = null
                    -1
                }

                CallbackStep.ON_CANCELED -> {
                    buffer = null
                    throw IOException("Request Canceled")
                }

                CallbackStep.ON_READ_COMPLETED -> {
                    result.buffer!!.flip()
                    val bytesWritten = sink.write(result.buffer)
                    result.buffer.clear()
                    bytesWritten.toLong()
                }
            }
        }

        override fun timeout(): Timeout {
            return mCall.timeout()
        }

    }
}
