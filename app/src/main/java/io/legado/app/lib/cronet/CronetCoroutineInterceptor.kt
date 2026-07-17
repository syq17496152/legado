package io.legado.app.lib.cronet

import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.help.http.CookieManager
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Keep
@Suppress("unused")
class CronetCoroutineInterceptor(private val cookieJar: CookieJar) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (chain.call().isCanceled()) {
            throw IOException("Canceled")
        }
        val original: Request = chain.request()
        //Cronet未初始化
        return if (!CronetLoader.install() || cronetEngine == null) {
            // Issue-7 调试日志：Cronet 未初始化，走 OkHttp（脱敏：只记录长度和路径前30字符）
            val origCookieLen = original.header("Cookie")?.length ?: 0
            AppLog.put("[CookieDebug] CronetInterceptor fallback OkHttp: cookieHeaderLen=$origCookieLen, urlPath=${original.url.toString().substringAfter("://").take(30)}")
            chain.proceed(original)
        } else try {
            val enableCookieJar = original.header(cookieJarHeader) != null
            val builder: Request.Builder = original.newBuilder()
            //移除Keep-Alive,手动设置会导致400 BadRequest
            builder.removeHeader("Keep-Alive")
            builder.removeHeader("Accept-Encoding")

            if (enableCookieJar) {
                // 使用 CookieManager 体系加载 Cookie（与 WebView 登录保存的 Cookie 一致）
                // 注意：不在此处移除 cookieJarHeader，由 AbsCallBack.init() 移除并处理响应 Cookie
                val origCookieLen = original.header("Cookie")?.length ?: 0
                val requestWithCookie = CookieManager.loadRequest(builder.build())
                val newCookieLen = requestWithCookie.header("Cookie")?.length ?: 0
                // Issue-7 调试日志：追踪 Cronet 请求 cookie 注入（脱敏：只记录长度和路径前30字符）
                AppLog.put("[CookieDebug] CronetInterceptor cookieJar: origCookieLen=$origCookieLen, loadedCookieLen=$newCookieLen, urlPath=${original.url.toString().substringAfter("://").take(30)}")
                val newBuilder = requestWithCookie.newBuilder()
                // loadRequest 不会移除 cookieJarHeader，确保 AbsCallBack 能检测到
                newBuilder.removeHeader("Keep-Alive")
                newBuilder.removeHeader("Accept-Encoding")

                val newReq = newBuilder.build()
                val timeout = chain.call().timeout().timeoutNanos() / 1000000
                runBlocking {
                    if (timeout > 0) {
                        withTimeout(timeout) {
                            proceedWithCronet(newReq, chain.call(), chain.readTimeoutMillis())
                        }
                    } else {
                        proceedWithCronet(newReq, chain.call(), chain.readTimeoutMillis())
                    }
                }
                // AbsCallBack 已在 onResponseStarted/onRedirectReceived 中调用 CookieManager.saveResponse()
                // 无需在此处重复保存
            } else {
                // 未启用 CookieJar 的请求，保持原有行为（从 CookieJar 读取）
                if (cookieJar != CookieJar.NO_COOKIES) {
                    val cookieStr = getCookieFromJar(original.url)
                    if (cookieStr.length > 3) {
                        builder.addHeader("Cookie", cookieStr)
                    }
                }

                val newReq = builder.build()
                val timeout = chain.call().timeout().timeoutNanos() / 1000000
                runBlocking {
                    if (timeout > 0) {
                        withTimeout(timeout) {
                            proceedWithCronet(newReq, chain.call(), chain.readTimeoutMillis()).also { response ->
                                receiveCookies(cookieJar, newReq.url, response.headers)
                            }
                        }
                    } else {
                        proceedWithCronet(newReq, chain.call(), chain.readTimeoutMillis()).also { response ->
                            receiveCookies(cookieJar, newReq.url, response.headers)
                        }
                    }
                }
            }

        } catch (e: Exception) {
            //不能抛出错误,抛出错误会导致应用崩溃
            //遇到Cronet处理有问题时的情况，如证书过期等等，回退到okhttp处理
            if (!e.message.toString().contains("ERR_CERT_", true)
                && !e.message.toString().contains("ERR_SSL_", true)
            ) {
                e.printOnDebug()
            }
            chain.proceed(original)
        }

    }


    private suspend fun proceedWithCronet(
        request: Request,
        call: Call,
        readTimeoutMillis: Int
    ): Response =
        suspendCancellableCoroutine<Response> { coroutine ->

            val callBack = object : AbsCallBack(request, call, readTimeoutMillis) {
                override fun waitForDone(urlRequest: UrlRequest): Response {
                    throw UnsupportedOperationException(
                        "waitForDone is not used in CronetCoroutineInterceptor; " +
                        "use proceedWithCronet's suspendCancellableCoroutine pattern instead."
                    )
                }

                override fun onError(error: IOException) {
                    coroutine.resumeWithException(error)
                }

                override fun onSuccess(response: Response) {
                    coroutine.resume(response)
                }

                override fun onCanceled(request: UrlRequest?, info: UrlResponseInfo?) {
                    super.onCanceled(request, info)
                    coroutine.cancel()
                }


            }

            val req = buildRequest(request, callBack)?.also { it.start() }
            coroutine.invokeOnCancellation {
                req?.cancel()
            }


        }


    /** 从 CookieJar 读取 Cookie（仅用于未启用 cookieJarHeader 的请求） */
    private fun getCookieFromJar(url: okhttp3.HttpUrl): String = buildString {
        val cookies = cookieJar.loadForRequest(url)
        cookies.forEachIndexed { index, cookie ->
            if (index > 0) append("; ")
            append(cookie.name).append('=').append(cookie.value)
        }
    }

    /**
     * 从响应头解析 Set-Cookie 并存入 CookieJar（仅用于未启用 cookieJarHeader 的请求）
     * 替代 okhttp3.internal.http.receiveHeaders
     */
    private fun receiveCookies(cookieJar: CookieJar, url: okhttp3.HttpUrl, headers: okhttp3.Headers) {
        val cookies = headers.values("Set-Cookie").mapNotNull { okhttp3.Cookie.parse(url, it) }
        cookieJar.saveFromResponse(url, cookies)
    }
}