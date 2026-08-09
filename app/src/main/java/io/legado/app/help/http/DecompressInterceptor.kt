package io.legado.app.help.http

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppLog.Level
import io.legado.app.constant.AppLog.TAG_DECOMPRESS
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import okio.source
import org.brotli.dec.BrotliInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

object DecompressInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requestBuilder = request.newBuilder()

        var transparentDecompress = false
        if (request.header("Accept-Encoding") == null && request.header("Range") == null) {
            transparentDecompress = true
            requestBuilder.header("Accept-Encoding", "gzip, deflate, br")
        }

        val response = chain.proceed(requestBuilder.build())
        val body = response.body

        if (!transparentDecompress || !response.promisesBody() || body == ResponseBody.EMPTY) {
            return response
        }

        val encoding = response.header("Content-Encoding")?.lowercase()
        val source = try {
            when (encoding) {
                "gzip" -> GZIPInputStream(body.byteStream()).source().buffer()
                "deflate" -> InflaterInputStream(body.byteStream(), Inflater(true)).source().buffer()
                "br" -> BrotliInputStream(body.byteStream()).source().buffer()
                else -> return response
            }
        } catch (e: Exception) {
            // 解压异常：回退透传原始 body（移除编码头，避免上层二次解压）
            AppLog.putDebugWithTag(
                TAG_DECOMPRESS,
                "br/gzip/deflate inflate failed, fallback passthrough, url=${request.url.host.take(50)}, encoding=$encoding, ${e.message}",
                e,
                Level.ERROR
            )
            return response.newBuilder()
                .removeHeader("Content-Encoding")
                .removeHeader("Content-Length")
                .body(body)
                .build()
        }
        if (encoding == "br") {
            AppLog.putDebugWithTag(
                TAG_DECOMPRESS,
                "br handled, url=${request.url.host.take(50)}, encoding=br",
                level = Level.INFO
            )
        }

        return response.newBuilder()
            .removeHeader("Content-Encoding")
            .removeHeader("Content-Length")
            .body(source.asResponseBody(body.contentType(), -1))
            .build()
    }

    /**
     * 判断响应是否承诺有 body（替代 okhttp3.internal.http.promisesBody）
     *
     * 逻辑等价于 OkHttp 内部实现：
     * HEAD 请求和 1xx/204/205 响应码没有 body
     */
    private fun Response.promisesBody(): Boolean {
        if (request.method == "HEAD") return false
        val code = code
        return !(code in 100..199 || code == 204 || code == 205)
    }
}
