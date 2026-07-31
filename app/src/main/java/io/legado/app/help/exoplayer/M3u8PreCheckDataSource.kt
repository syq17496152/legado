package io.legado.app.help.exoplayer

import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * P0-5: m3u8 URL HEAD 预检机制（2026-07-31）
 *
 * 作用：在 HlsMediaSource 创建前验证 m3u8 URL 可达性 + 获取重定向后的 finalUrl，
 * 减少无效 MediaSource 创建（404/403/不可达 URL）和重复重定向延迟。
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
     */
    private fun headPreCheck(url: String, redirectCount: Int): PreCheckResult {
        if (redirectCount >= MAX_REDIRECTS) {
            return PreCheckResult.Fail("Too many redirects (>$MAX_REDIRECTS)")
        }
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false  // 手动跟随重定向，便于记录 finalUrl
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
        } catch (e: Exception) {
            return PreCheckResult.Fail("HEAD connection failed: ${e.javaClass.simpleName}")
        }

        try {
            val code = connection.responseCode
            when (code) {
                in 200..299 -> {
                    val contentType = connection.contentType ?: ""
                    // Content-Type 校验（m3u8 标准 MIME 类型）
                    val isM3u8ContentType = contentType.contains("application/vnd.apple.mpegurl", true) ||
                        contentType.contains("application/x-mpegurl", true) ||
                        contentType.contains("audio/mpegurl", true)
                    // Content-Type 匹配 → 直接成功
                    if (isM3u8ContentType) {
                        return PreCheckResult.Success(url)
                    }
                    // Content-Type 不匹配（可能是 text/plain 或空），降级为只读前 1KB 验证
                    // 根因：部分 CDN 返回非标准 Content-Type（如 text/plain），但内容是合法 m3u8
                    return verifyExtM3UHeader(url)
                }
                in 300..399 -> {
                    // 跟随重定向（302/301）
                    val location = connection.getHeaderField("Location")
                    return if (!location.isNullOrBlank()) {
                        // 处理相对路径重定向（如 Location: /path/xxx.m3u8）
                        val finalLocation = if (location.startsWith("http")) location else {
                            val base = URL(url)
                            URL(base.protocol, base.host, base.port, location).toString()
                        }
                        headPreCheck(finalLocation, redirectCount + 1)
                    } else {
                        PreCheckResult.Fail("Redirect $code without Location")
                    }
                }
                403 -> {
                    // 403 时添加 User-Agent 重试（部分 CDN 拒绝非浏览器 UA）
                    val retryHeaders = headers.toMutableMap().apply {
                        if (!keys.any { it.equals("User-Agent", true) }) {
                            put("User-Agent", ExoPlayerHelper.BROWSER_UA)
                        }
                    }
                    return if (retryHeaders != headers) {
                        M3u8PreCheckDataSource(retryHeaders).headPreCheck(url, redirectCount)
                    } else {
                        // 已包含 UA 仍 403，降级为只读前 1KB 验证
                        verifyExtM3UHeader(url)
                    }
                }
                405 -> {
                    // 405 Method Not Allowed：服务器不支持 HEAD，降级为只读前 1KB 验证
                    return verifyExtM3UHeader(url)
                }
                else -> {
                    // 其他状态码（404/500 等），降级为只读前 1KB 验证（部分 CDN 对 HEAD 返回 404 但 GET 正常）
                    return verifyExtM3UHeader(url)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 方案B：只读前 1KB 验证 #EXTM3U 头（准确率更高，但消耗 1KB 流量）
     *
     * - 跳过 BOM（EF BB BF）后校验前 7 字节是否为 #EXTM3U
     * - 使用 Range: bytes=0-1023 只读前 1KB（减少流量）
     */
    private fun verifyExtM3UHeader(url: String): PreCheckResult {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-1023")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true  // 自动跟随重定向
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (!headers.keys.any { it.equals("User-Agent", true) }) {
                    setRequestProperty("User-Agent", ExoPlayerHelper.BROWSER_UA)
                }
            }
        } catch (e: Exception) {
            return PreCheckResult.Fail("Range-get connection failed: ${e.javaClass.simpleName}")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299 && code != 206) {
                return PreCheckResult.Fail("Range-get failed: code=$code")
            }
            val inputStream = connection.inputStream ?: return PreCheckResult.Fail("Empty input stream")
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
                // 获取重定向后的 finalUrl（如果有重定向）
                val finalUrl = connection.url?.toString() ?: url
                PreCheckResult.Success(finalUrl)
            } else {
                PreCheckResult.Fail("Invalid M3U8 header: firstBytes=${header.take(7)}")
            }
        } catch (e: Exception) {
            return PreCheckResult.Fail("Range-get io failed: ${e.javaClass.simpleName}")
        } finally {
            connection.disconnect()
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
