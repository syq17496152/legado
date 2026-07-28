package io.legado.app.help.http

import io.legado.app.constant.AppLog
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * T4.2: 302 重定向缓存 Interceptor
 *
 * 核心能力：
 * - 缓存 302 重定向结果，避免重复请求重定向链
 * - 提高抓取成功率（用户核心诉求：视频/图片地址经常 302 跳转到 CDN）
 * - 减少网络往返，提升加载速度
 *
 * 工作原理：
 * - 拦截 302 响应，缓存 Location 头中的目标 URL
 * - 后续相同 URL 的请求直接从缓存读取目标 URL，跳过 302 跳转
 * - 缓存有效期：5 分钟（平衡缓存命中率与 URL 时效性）
 *
 * 使用场景：
 * - 视频地址 302 跳转到 CDN（如 play.php?id=xxx → cdn.example.com/video.mp4）
 * - 图片地址 302 跳转到 CDN
 * - 减少重复跳转，提高抓取成功率
 */
object RedirectCacheInterceptor : Interceptor {

    /** 重定向缓存：原始 URL → 缓存条目（目标 URL + 时间戳） */
    private val redirectCache = ConcurrentHashMap<String, CacheEntry>()

    /** 缓存有效期：5 分钟 */
    private const val CACHE_VALIDITY_MS = 5 * 60 * 1000L

    /** 最大缓存数量：LRU 淘汰超过此数量的最旧条目 */
    private const val MAX_CACHE_SIZE = 100

    /**
     * 缓存条目（目标 URL + 时间戳）
     */
    private data class CacheEntry(
        val targetUrl: String,
        val timestamp: Long
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url.toString()

        // 检查缓存命中
        val cachedEntry = redirectCache[originalUrl]
        if (cachedEntry != null) {
            val isExpired = System.currentTimeMillis() - cachedEntry.timestamp > CACHE_VALIDITY_MS
            if (!isExpired) {
                // 缓存命中且未过期，直接请求目标 URL
                AppLog.putDebug("RedirectCache: cache hit, skip 302, from=${sanitizeUrl(originalUrl)}, to=${sanitizeUrl(cachedEntry.targetUrl)}")
                val newRequest = originalRequest.newBuilder()
                    .url(cachedEntry.targetUrl)
                    .build()
                return chain.proceed(newRequest)
            } else {
                // 缓存过期，删除
                redirectCache.remove(originalUrl)
                AppLog.putDebug("RedirectCache: cache expired, remove, url=${sanitizeUrl(originalUrl)}")
            }
        }

        // 缓存未命中，继续请求
        val response = chain.proceed(originalRequest)

        // 拦截重定向响应，缓存 Location 头
        // 301/302/307/308 全覆盖（307/308 常见于 CDN 重定向且保持请求方法）
        if (response.code == 301 || response.code == 302 || response.code == 307 || response.code == 308) {
            val location = response.header("Location")
            if (location != null) {
                // Location 可能是相对路径（如 /video/play.mp4），必须解析为绝对 URL
                // 否则缓存命中时 newBuilder().url(相对路径) 抛 IllegalArgumentException
                val resolved = originalRequest.url.resolve(location)?.toString()
                if (resolved != null) {
                    redirectCache[originalUrl] = CacheEntry(resolved, System.currentTimeMillis())
                    AppLog.putDebug("RedirectCache: cache ${response.code}, from=${sanitizeUrl(originalUrl)}, to=${sanitizeUrl(resolved)}")
                    // LRU 淘汰：超过最大缓存数量时删除最旧条目
                    evictOldestIfNeeded()
                } else {
                    AppLog.putDebug("RedirectCache: location resolve failed, skip cache, from=${sanitizeUrl(originalUrl)}")
                }
            }
        }

        return response
    }

    /**
     * LRU 淘汰：超过最大缓存数量时删除最旧条目
     */
    private fun evictOldestIfNeeded() {
        if (redirectCache.size <= MAX_CACHE_SIZE) return

        // 按时间戳排序，删除最旧的条目
        val sortedEntries = redirectCache.entries.sortedBy { it.value.timestamp }
        val entriesToRemove = sortedEntries.take(redirectCache.size - MAX_CACHE_SIZE)
        entriesToRemove.forEach { entry ->
            redirectCache.remove(entry.key)
            AppLog.putDebug("RedirectCache: LRU evict, url=${sanitizeUrl(entry.key)}")
        }
    }

    /**
     * 清除缓存（释放资源时调用）
     */
    fun clearCache() {
        redirectCache.clear()
        AppLog.putDebug("RedirectCache: cache cleared")
    }

    /**
     * URL 脱敏（只保留路径模式，不输出完整 URL）
     */
    private fun sanitizeUrl(url: String): String {
        return try {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}${uri.path}?..."
        } catch (e: Exception) {
            "unknown"
        }
    }
}
