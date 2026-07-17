package io.legado.app.help.http

import io.legado.app.constant.AppLog
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import okio.source
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
            requestBuilder.header("Accept-Encoding", "gzip, deflate")
        }

        val response = chain.proceed(requestBuilder.build())
        val body = response.body

        // Issue7 调试日志：记录解压前的 Content-Encoding 和响应状态（脱敏：只记录路径片段和长度）
        val reqAcceptEnc = request.header("Accept-Encoding") ?: "(added:gzip,deflate)"
        val resContentEnc = response.header("Content-Encoding")?.lowercase()
        val resContentType = response.header("Content-Type")?.take(40)
        val hasCookieJar = request.header(CookieManager.cookieJarHeader) != null
        // Issue7 缓存假设验证：记录 cacheResponse 和 networkResponse 状态
        val cacheCode = response.cacheResponse?.code
        val networkCode = response.networkResponse?.code
        AppLog.put("[DecompressDebug] reqAcceptEnc=$reqAcceptEnc, resContentEnc=$resContentEnc, " +
            "resContentType=$resContentType, httpCode=${response.code}, hasCookieJar=$hasCookieJar, " +
            "transparentDecompress=$transparentDecompress, bodySize=${body?.contentLength() ?: -1}, " +
            "cacheCode=$cacheCode, networkCode=$networkCode, " +
            "urlPath=${request.url.encodedPath?.take(40)}")

        if (!transparentDecompress || !response.promisesBody() || body == ResponseBody.EMPTY) {
            AppLog.put("[DecompressDebug] skip decompress: transparentDecompress=$transparentDecompress, " +
                "promisesBody=${response.promisesBody()}, bodyEmpty=${body == ResponseBody.EMPTY}, " +
                "resContentEnc=$resContentEnc")
            return response
        }

        val encoding = resContentEnc
        val source = when (encoding) {
            "gzip" -> GZIPInputStream(body.byteStream()).source().buffer()
            "deflate" -> InflaterInputStream(body.byteStream(), Inflater(true)).source().buffer()
            else -> {
                // 未知编码（如 br），记录警告
                AppLog.put("[DecompressDebug] WARN: unknown encoding=$encoding, body will be raw bytes, " +
                    "may cause parse failure")
                return response
            }
        }

        AppLog.put("[DecompressDebug] decompressed: encoding=$encoding")
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
