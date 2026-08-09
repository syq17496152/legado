package io.legado.app.help.http

import io.legado.app.data.appDb
import io.legado.app.data.entities.UrlRecord
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.net.URI

// precise-manage: 全局网址记录拦截器（借鉴 Legado_Max UrlRecordInterceptor，去除 DebugEventCenter 上报）
object UrlRecordInterceptor : Interceptor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!AppConfig.recordUrl) return chain.proceed(chain.request())
        val request = chain.request()
        val startTime = System.currentTimeMillis()
        var response: Response? = null
        var errorMsg: String? = null
        var responseCode = 0
        try {
            response = chain.proceed(request)
            responseCode = response.code
            return response
        } catch (e: Exception) {
            errorMsg = e.message
            throw e
        } finally {
            val duration = System.currentTimeMillis() - startTime
            val url = sanitizeUrl(request.url.toString())
            val domain = request.url.host
            val sourceName = request.header("X-Source-Name")
            val sourceUrl = request.header("X-Source-Url")
            val requestBody = if (request.method.equals("POST", true)) {
                request.body?.let { body ->
                    try {
                        val buffer = Buffer()
                        body.writeTo(buffer)
                        buffer.readUtf8().takeIf { it.length <= 1000 }
                    } catch (e: Exception) {
                        null
                    }
                }
            } else {
                null
            }
            val record = UrlRecord(
                url = url,
                domain = domain,
                method = request.method,
                sourceName = sourceName,
                sourceUrl = sourceUrl,
                timestamp = startTime,
                responseCode = responseCode,
                duration = duration,
                requestBody = requestBody,
                errorMsg = errorMsg
            )
            scope.launch {
                runCatching { appDb.urlRecordDao.insert(record) }
            }
        }
    }

    fun cancelAll() {
        scope.cancel()
    }

    internal fun sanitizeUrl(url: String): String {
        return try {
            val uri = URI(url)
            val rawQuery = uri.rawQuery ?: return url
            if (!rawQuery.contains("=")) return url
            val sanitized = rawQuery.split("&").joinToString("&") { param ->
                val index = param.indexOf('=')
                if (index > 0) {
                    val key = param.substring(0, index)
                    if (SENSITIVE_KEYS.contains(key)) "$key=***" else param
                } else {
                    param
                }
            }
            val newUri = URI(uri.scheme, uri.authority, uri.path, sanitized, uri.fragment)
            newUri.toString()
        } catch (e: Exception) {
            url
        }
    }

    private val SENSITIVE_KEYS = setOf(
        "token", "access_token", "auth_token", "api_key", "apikey",
        "key", "password", "passwd", "pwd", "secret", "authorization"
    )
}
