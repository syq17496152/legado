package io.legado.app.lib.cronet

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.help.http.CookieManager
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.utils.printOnDebug
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

@Keep
@Suppress("unused")
class CronetInterceptor(private val cookieJar: CookieJar) : Interceptor {

    companion object {
        // app-stability-round2 P2-1: 运行时降级机制
        // 根因：3287 次 protocol=unknown httpCode=-1（QUIC/HTTP3 协议协商未完成，疑似 UDP 被网络阻断）
        // 现有 catch 回退 OkHttp 保证功能可用，但每次请求都走 Cronet 失败再回退，浪费无效往返 + 日志噪音
        // 方案：连续协议错误达阈值后，本次会话内降级到 OkHttp，减少无效 Cronet 尝试
        // 已知上限：降级后本次会话不再尝试 Cronet，需重启 App 恢复 | 升级路径：可改为基于时间窗口的降级（如 5 分钟后重试）
        private const val DEGRADE_THRESHOLD = 5
        @Volatile private var protocolErrorCount = 0
        @Volatile private var degradedForSession = false

        // 日志去重：相同错误消息 60 秒内只记一次，避免高频失败刷屏
        @Volatile private var lastLoggedError: String? = null
        @Volatile private var lastLoggedErrorTime = 0L
        private const val LOG_DEDUP_INTERVAL_MS = 60_000L

        /**
         * 降级状态查询（供 HttpHelper 等外部诊断使用）
         */
        fun isDegraded(): Boolean = degradedForSession
    }

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        if (chain.call().isCanceled()) {
            throw IOException("Canceled")
        }
        val original: Request = chain.request()
        // P2-1: 运行时降级——会话内连续协议错误达阈值后直接走 OkHttp，避免无效 Cronet 往返
        if (degradedForSession) {
            return chain.proceed(original)
        }
        //Cronet未初始化
        if (!CronetLoader.install() || cronetEngine == null) {
            return chain.proceed(original)
        }
        val cronetException: Exception
        try {
            val builder: Request.Builder = original.newBuilder()
            //移除Keep-Alive,手动设置会导致400 BadRequest
            builder.removeHeader("Keep-Alive")
            builder.removeHeader("Accept-Encoding")

            // https://github.com/gedoor/legado/issues/5025#issuecomment-2851156500
            if (!original.isHttps &&
                original.header("User-Agent")?.startsWith("Mozilla", true) == true
            ) {
                val referer = original.header("Referer")
                if (referer != null && referer.startsWith("https:", true)) {
                    builder.header("Referer", "http" + referer.substring(5))
                }
            }

            var newReq = builder.build()

            if (newReq.header(cookieJarHeader) != null) {
                newReq = CookieManager.loadRequest(newReq)
            }

            return proceedWithCronet(newReq, chain.call(), chain.readTimeoutMillis())!!
        } catch (e: Exception) {
            cronetException = e
            //不能抛出错误,抛出错误会导致应用崩溃
            //遇到Cronet处理有问题时的情况，如证书过期等等，回退到okhttp处理
            // app-stability-round2 P2-1: 协议错误计数 + 运行时降级 + 日志去重
            val errMsg = e.message.toString()
            // 协议错误判断：扩展覆盖连接层失败（protocol=unknown 对应 QUIC/连接被拒/Socket 异常）
            val isProtocolError = errMsg.contains("PROTOCOL_ERROR", true)
                || errMsg.contains("StreamReset", true)
                || errMsg.contains("System error", true)
                || errMsg.contains("ERR_QUIC", true)
                || errMsg.contains("ERR_CONNECTION", true)
                || errMsg.contains("ERR_SOCKET", true)
            if (!errMsg.contains("ERR_CERT_", true)
                && !errMsg.contains("ERR_SSL_", true)
            ) {
                e.printOnDebug()
            }
            if (isProtocolError) {
                protocolErrorCount++
                // 日志去重：相同错误 60 秒内只记一次，避免高频失败刷屏
                val now = System.currentTimeMillis()
                if (errMsg != lastLoggedError || now - lastLoggedErrorTime > LOG_DEDUP_INTERVAL_MS) {
                    AppLog.put("Cronet 协议错误，回退到 OkHttp: error=${errMsg.take(80)} (累计 $protocolErrorCount 次)")
                    lastLoggedError = errMsg
                    lastLoggedErrorTime = now
                }
                // 达阈值降级：避免后续请求继续无效 Cronet 往返
                if (protocolErrorCount >= DEGRADE_THRESHOLD && !degradedForSession) {
                    degradedForSession = true
                    AppLog.put("Cronet 连续协议错误达 $DEGRADE_THRESHOLD 次，本次会话降级到 OkHttp（重启 App 恢复）")
                }
            }
        }
        try {
            return chain.proceed(original)
        } catch (e: Exception) {
            e.addSuppressed(cronetException)
            throw e
        }
    }

    @SuppressLint("ObsoleteSdkInt")
    @Throws(IOException::class)
    private fun proceedWithCronet(request: Request, call: Call, readTimeoutMillis: Int): Response? {
        val callBack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            NewCallBack(request, call, readTimeoutMillis)
        } else {
            OldCallback(request, call, readTimeoutMillis)
        }
        buildRequest(request, callBack)?.let {
            return callBack.waitForDone(it)
        }
        return null
    }


    /** Returns a 'Cookie' HTTP request header with all cookies, like `a=b; c=d`. */
    private fun getCookie(url: HttpUrl): String = buildString {
        val cookies = cookieJar.loadForRequest(url)
        cookies.forEachIndexed { index, cookie ->
            if (index > 0) append("; ")
            append(cookie.name).append('=').append(cookie.value)
        }
    }

}
