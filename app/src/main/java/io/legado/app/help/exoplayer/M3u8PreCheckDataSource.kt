package io.legado.app.help.exoplayer

import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * P0-5: m3u8 URL HEAD 预检机制（2026-07-31）
 *
 * 作用：在 HlsMediaSource 创建前验证 m3u8 URL 可达性 + 获取重定向后的 finalUrl，
 * 减少无效 MediaSource 创建（404/403/不可达 URL）和重复重定向延迟。
 *
 * P1-3 (2026-07-31): 替换 HttpURLConnection 为 OkHttp API，走 CronetInterceptor 接入 Cronet，
 * 获得 BoringSSL TLS 指纹 + QUIC + 连接迁移能力，提升反爬 CDN 场景预检成功率。
 *
 * 成熟方案参考：
 * - Chromium MediaDataSource::PreRead：播放前预检 URL 可达性
 * - hls.js loadFailureMediaPlaylistDelegate：预检失败时快速降级
 * - ExoPlayer 官方建议：对不可信 URL 先 HEAD 预检减少播放失败
 *
 * 实现策略（双方案兜底）：
 * - 方案A（首选）：HEAD 请求预检（节省流量，仅获取响应头）
 *   - 200/206：校验 Content-Type（application/vnd.apple.mpegurl / application/x-mpegurl）
 *   - 302/301：跟随 Location（最多 5 次递归）
 *   - 403：添加 User-Agent 重试（部分 CDN 拒绝非浏览器 UA）
 *   - HEAD 失败（405 Method Not Allowed / 其他）→ 降级方案B
 * - 方案B（降级）：只读前 1KB 验证 #EXTM3U 头（准确率更高，但消耗 1KB 流量）
 *   - 跳过 BOM（EF BB BF）后校验前 7 字节是否为 #EXTM3U
 *   - 失败标记 URL 无效
 *
 * 超时控制：
 * - connectTimeout=5000ms（弱网场景容忍）
 * - readTimeout=3000ms（响应头读取）
 *
 * 安全规范：
 * - 日志只输出技术结论（状态码、Content-Type、finalUrl 路径模式），不输出完整 URL
 * - 不输出 Referer/Cookie 值
 */
@Keep
class M3u8PreCheckDataSource(
    private val headers: Map<String, String> = emptyMap()
) {

    /**
     * 预检 m3u8 URL 可达性
     *
     * @param url m3u8 URL（完整，含 query）
     * @return PreCheckResult.Success(finalUrl) 或 PreCheckResult.Fail(reason)
     */
    suspend fun preCheck(url: String): PreCheckResult = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext PreCheckResult.Fail("Invalid URL scheme")
        }
        try {
            // 方案A：HEAD 预检
            val headResult = headPreCheck(url, redirectCount = 0)
            if (headResult is PreCheckResult.Success) {
                AppLog.putDebug("M3u8PreCheck: HEAD success, finalUrlPath=${sanitizeUrlPath(headResult.finalUrl)}")
                return@withContext headResult
            }
            // 方案B：HEAD 失败（405/其他），降级为只读前 1KB 验证
            AppLog.putDebug("M3u8PreCheck: HEAD failed, fallback to range-get verification")
            val rangeResult = verifyExtM3UHeader(url)
            when (rangeResult) {
                is PreCheckResult.Success -> AppLog.putDebug("M3u8PreCheck: range-get success, finalUrlPath=${sanitizeUrlPath(rangeResult.finalUrl)}")
                is PreCheckResult.Fail -> AppLog.putDebug("M3u8PreCheck: range-get failed, reason=${rangeResult.reason}")
            }
            rangeResult
        } catch (e: Exception) {
            AppLog.putDebug("M3u8PreCheck: preCheck failed: ${e.javaClass.simpleName}: ${e.message?.take(60)}")
            PreCheckResult.Fail(e.message ?: "Unknown error")
        }
    }

    /**
     * 方案A：HEAD 请求预检（递归跟随重定向，最多 5 次）
     *
     * P1-3: 使用 OkHttp 同步请求（走 CronetInterceptor 接入 Cronet）
     * - newBuilder() 继承 CronetInterceptor，仅覆盖 followRedirects 和超时配置
     * - 手动跟随重定向以记录 finalUrl
     */
    private fun headPreCheck(url: String, redirectCount: Int): PreCheckResult {
        if (redirectCount >= MAX_REDIRECTS) {
            return PreCheckResult.Fail("Too many redirects (>$MAX_REDIRECTS)")
        }
        val request = okhttp3.Request.Builder()
            .url(url)
            .head()
            .apply {
                headers.forEach { (k, v) -> addHeader(k, v) }
            }
            .build()
        // newBuilder 继承 CronetInterceptor，禁用自动重定向以便手动跟随
        val client = okHttpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                when (code) {
                    in 200..299 -> {
                        val contentType = response.header("Content-Type") ?: ""
                        val isM3u8ContentType = contentType.contains("application/vnd.apple.mpegurl", true) ||
                            contentType.contains("application/x-mpegurl", true) ||
                            contentType.contains("audio/mpegurl", true)
                        if (isM3u8ContentType) {
                            return PreCheckResult.Success(url)
                        }
                        // Content-Type 不匹配（可能是 text/plain 或空），降级为只读前 1KB 验证
                        return verifyExtM3UHeader(url)
                    }
                    in 300..399 -> {
                        val location = response.header("Location")
                        return if (!location.isNullOrBlank()) {
                            val finalLocation = if (location.startsWith("http")) location else {
                                val base = java.net.URL(url)
                                java.net.URL(base.protocol, base.host, base.port, location).toString()
                            }
                            headPreCheck(finalLocation, redirectCount + 1)
                        } else {
                            PreCheckResult.Fail("Redirect $code without Location")
                        }
                    }
                    403 -> {
                        val retryHeaders = headers.toMutableMap().apply {
                            if (!keys.any { it.equals("User-Agent", true) }) {
                                put("User-Agent", ExoPlayerHelper.BROWSER_UA)
                            }
                        }
                        return if (retryHeaders != headers) {
                            M3u8PreCheckDataSource(retryHeaders).headPreCheck(url, redirectCount)
                        } else {
                            verifyExtM3UHeader(url)
                        }
                    }
                    405 -> {
                        return verifyExtM3UHeader(url)
                    }
                    else -> {
                        return verifyExtM3UHeader(url)
                    }
                }
            }
        } catch (e: Exception) {
            return PreCheckResult.Fail("HEAD request failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * 方案B：只读前 1KB 验证 #EXTM3U 头（准确率更高，但消耗 1KB 流量）
     *
     * P1-3: 使用 OkHttp 同步请求（走 CronetInterceptor 接入 Cronet）
     * - followRedirects=true 自动跟随重定向
     * - finalUrl 通过 response.request.url 获取（重定向后的最终 URL）
     */
    private fun verifyExtM3UHeader(url: String): PreCheckResult {
        val request = okhttp3.Request.Builder()
            .url(url)
            .get()
            .addHeader("Range", "bytes=0-1023")
            .apply {
                headers.forEach { (k, v) -> addHeader(k, v) }
                if (!headers.keys.any { it.equals("User-Agent", true) }) {
                    addHeader("User-Agent", ExoPlayerHelper.BROWSER_UA)
                }
            }
            .build()
        val client = okHttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                if (code !in 200..299 && code != 206) {
                    return PreCheckResult.Fail("Range-get failed: code=$code")
                }
                val body = response.body ?: return PreCheckResult.Fail("Empty response body")
                val inputStream = body.byteStream()
                val buffer = ByteArray(7)
                val read = inputStream.read(buffer)
                if (read < 7) {
                    return PreCheckResult.Fail("Insufficient data: read=$read bytes")
                }
                // 跳过 BOM（EF BB BF）
                val startIndex = if (buffer[0] == 0xEF.toByte() &&
                    buffer[1] == 0xBB.toByte() &&
                    buffer[2] == 0xBF.toByte()
                ) 3 else 0
                val headerLength = 7 - startIndex
                if (headerLength <= 0) {
                    return PreCheckResult.Fail("Invalid header after BOM skip")
                }
                val header = String(buffer, startIndex, headerLength)
                return if (header.startsWith("#EXTM3U")) {
                    // 获取重定向后的 finalUrl（OkHttp 自动跟随重定向后，response.request.url 是最终 URL）
                    val finalUrl = response.request.url.toString()
                    PreCheckResult.Success(finalUrl)
                } else {
                    PreCheckResult.Fail("Invalid M3U8 header: firstBytes=${header.take(7)}")
                }
            }
        } catch (e: Exception) {
            return PreCheckResult.Fail("Range-get io failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * URL 路径脱敏（只保留 path 前 40 字符，符合输出安全规范）
     */
    private fun sanitizeUrlPath(url: String): String {
        return try {
            val u = java.net.URL(url)
            "path=${u.path?.take(40)}"
        } catch (e: Exception) {
            "raw=${url.take(30)}"
        }
    }

    /**
     * 预检结果密封类
     */
    sealed class PreCheckResult {
        /**
         * 预检成功
         * @param finalUrl 重定向后的最终 URL（如无重定向则与原始 URL 相同）
         */
        data class Success(val finalUrl: String) : PreCheckResult()

        /**
         * 预检失败
         * @param reason 失败原因（技术描述，不含敏感信息）
         */
        data class Fail(val reason: String) : PreCheckResult()
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 3000
    }
}
