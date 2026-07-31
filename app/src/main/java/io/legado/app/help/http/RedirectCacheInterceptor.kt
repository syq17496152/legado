package io.legado.app.help.http

import androidx.annotation.Keep
import android.util.LruCache
import io.legado.app.constant.AppLog
import okhttp3.Interceptor
import okhttp3.Response

/**
 * P1-5: 302 重定向缓存拦截器（2026-07-31，V3 修订 2026-07-31）
 *
 * 作用：缓存重定向映射（原 URL → finalUrl），避免同一 URL 重复重定向往返延迟。
 *
 * 成熟方案参考：
 * - Chrome RedirectHistoryCache：浏览器缓存重定向映射，相同 URL 直接跳转 finalUrl
 * - OkHttp 内部 retryAndFollowUpInterceptor：默认每次请求都重新跟随重定向，无跨请求缓存
 *
 * V3 修订（FR-1）：
 * - 原实现用 response.header("Location") 仅获取第一层重定向且永远不成立
 *   （OkHttp followRedirects=true 自动跟随重定向后，应用拦截器看不到 302 响应）
 * - V3 改用 response.request.url 获取跟随所有重定向后的最终URL
 * - 支持多层重定向（A→B→C 缓存 A→C 映射，非仅第一层）
 *
 * 实现策略：
 * - LruCache 500 条 + TTL 10 分钟（平衡命中率与 finalUrl 时效性）
 * - 缓存 key 带 Referer/Cookie 维度（防盗链场景 finalUrl 可能随 header 变化）
 * - 命中时改写请求 URL 为 finalUrl，跳过重定向往返
 * - 比较 request.url 与 response.request.url，不同则缓存映射
 *
 * 安全规范：
 * - 日志只输出技术结论（命中/未命中/缓存大小），不输出 URL/Referer/Cookie 值
 * - Cookie 维度 key 只取前 8 字符（避免完整 cookie 泄漏）
 */
@Keep
object RedirectCacheInterceptor : Interceptor {

    /** 缓存条目：finalUrl + 过期时间戳 */
    private data class RedirectEntry(val finalUrl: String, val expireAt: Long)

    /** LruCache 500 条（按 LRU 策略淘汰最久未访问的条目） */
    private val cache = LruCache<String, RedirectEntry>(MAX_CACHE_SIZE)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val originalUrl = request.url.toString()

        // 构建缓存 key（URL + Referer 维度 + Cookie 维度前 8 字符）
        val referer = request.header("Referer")
        val cookie = request.header("Cookie")
        val cacheKey = buildCacheKey(originalUrl, referer, cookie)

        // 缓存命中检查
        synchronized(cache) {
            cache.get(cacheKey)?.let { entry ->
                if (System.currentTimeMillis() < entry.expireAt) {
                    // 命中：改写请求 URL 为 finalUrl，跳过 302 往返
                    val newRequest = request.newBuilder()
                        .url(entry.finalUrl)
                        .build()
                    AppLog.putDebug("RedirectCache: hit, skipping 302, cacheSize=${cache.size()}")
                    return chain.proceed(newRequest)
                } else {
                    // 过期：移除
                    cache.remove(cacheKey)
                }
            }
        }

        // 缓存未命中：正常发起请求（OkHttp followRedirects=true 会自动跟随重定向）
        val response = chain.proceed(request)

        // V3-FR-1: 使用 response.request.url 获取跟随所有重定向后的最终URL（多层重定向 A→B→C 缓存 A→C 映射）
        // 原实现用 response.header("Location") 仅获取第一层重定向且永远不成立
        // （OkHttp followRedirects=true 自动跟随重定向后，应用拦截器看不到 302 响应）
        val finalUrl = response.request.url.toString()
        if (originalUrl != finalUrl) {
            // 发生重定向：缓存原始 URL → 最终 URL 映射
            synchronized(cache) {
                cache.put(cacheKey, RedirectEntry(finalUrl, System.currentTimeMillis() + CACHE_TTL_MS))
            }
            AppLog.putDebug("RedirectCache: cached redirect mapping, cacheSize=${cache.size()}")
        }
        return response
    }

    /**
     * 构建缓存 key（URL + Referer 维度 + Cookie 维度前 8 字符）
     *
     * 防盗链场景 finalUrl 可能随 header 变化：
     * - 不同 Referer 可能得到不同 finalUrl（CDN 基于 Referer 返回不同 CDN 节点）
     * - 不同 Cookie 可能得到不同 finalUrl（登录态影响重定向目标）
     */
    private fun buildCacheKey(url: String, referer: String?, cookie: String?): String {
        // URL 维度 + Referer 维度（取 path 前 20 字符避免完整 Referer 泄漏） + Cookie 维度（取前 8 字符）
        val refererKey = referer?.let { it.take(20) } ?: ""
        val cookieKey = cookie?.let { it.take(8) } ?: ""
        return "$url|referer=$refererKey|cookie=$cookieKey"
    }

    private const val MAX_CACHE_SIZE = 500
    private const val CACHE_TTL_MS = 10 * 60 * 1000L  // 10 分钟
}
