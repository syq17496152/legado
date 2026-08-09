package io.legado.app.help.http

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

object NetworkLogInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!NetworkLog.isEnabled) return chain.proceed(chain.request())
        val request = chain.request()
        val start = System.nanoTime()
        try {
            val response = chain.proceed(request)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000L
            NetworkLog.recordOkHttp(request, response, elapsedMs)
            return response
        } catch (e: IOException) {
            val elapsedMs = (System.nanoTime() - start) / 1_000_000L
            NetworkLog.recordOkHttp(request, null, elapsedMs, e)
            throw e
        } catch (e: Throwable) {
            val elapsedMs = (System.nanoTime() - start) / 1_000_000L
            NetworkLog.recordOkHttp(request, null, elapsedMs, e)
            throw e
        }
    }
}
