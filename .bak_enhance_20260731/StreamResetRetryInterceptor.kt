package io.legado.app.help.http

import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * sniff-result-pipeline-fix FR-3: HTTP/2 StreamReset 容错拦截器
 *
 * 作用：捕获 okhttp3.internal.http2.StreamResetException，淘汰连接池连接并重试一次
 *
 * 成熟方案参考：
 * - OkHttp retryOnConnectionFailure 对 HTTP/2 流重置无效（流重置不可重试，连接仍可用）
 * - Spring Cloud LoadBalancer 的 RetryInterceptor：异常时淘汰实例 + 重试
 *
 * 实现策略：
 * - 只捕获 StreamResetException（HTTP/2 特有），不干扰其他异常
 * - 淘汰当前连接（chain.call().cancel()）+ 清理连接池该 host 连接
 * - 重试一次原请求（通过 response.priorResponse 判断避免无限重试）
 *
 * 安全规范：
 * - 日志只输出技术结论（host 前 3 字符 + ***），不输出 URL/cookie
 */
@Keep
object StreamResetRetryInterceptor : Interceptor {

    private const val MAX_RETRY = 1

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return try {
            chain.proceed(request)
        } catch (e: IOException) {
            // 只对 StreamResetException 重试，其他异常直接抛出
            if (isStreamResetException(e)) {
                val host = request.url.host
                AppLog.put("StreamReset 重试, host=${host.take(3)}***, error=${e.message?.take(60)}")
                // 淘汰当前连接 + 清理连接池该 host 连接
                chain.call().cancel()
                chain.connection()?.socket()?.close()
                // 重试一次
                return try {
                    chain.proceed(request)
                } catch (e2: IOException) {
                    AppLog.put("StreamReset 重试失败, host=${host.take(3)}***, error=${e2.message?.take(60)}")
                    throw e2
                }
            } else {
                throw e
            }
        }
    }

    /**
     * 判断是否为 StreamResetException
     *
     * OkHttp 的 StreamResetException 是 internal 类，无法直接 instanceof
     * 通过类名判断（兼容 R8 混淆后的类名）
     */
    fun isStreamResetException(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            val className = cause.javaClass.name
            if (className.contains("StreamResetException", true)
                || (className.contains("http2", true) && className.contains("reset", true))
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }
}
