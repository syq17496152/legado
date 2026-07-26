package io.legado.app.help.exoplayer

import android.util.LruCache

/**
 * exoplayer-resilience Layer 1：URL → mimeType LRU 缓存
 *
 * 缓存预嗅探结果，避免对同一 URL 重复发起 Range 请求。
 *
 * - 实现：[LruCache] 容量 100（参考 [io.legado.app.model.analyzeRule.AnalyzeUrl.customIp] 模式）
 * - TTL：1 小时（[CACHE_TTL_MS]，避免源切换格式后误判）
 * - 线程安全：[LruCache] 内部 synchronized，无需额外同步
 *
 * key 规则（R2 修订）：**完整 URL（含 query）**
 * - 原设计文档 AD-04 计划用"去除 query 后的 path"，但实施时发现致命 BUG：
 *   同一站点 `/play.php?id=1`（mp4）和 `/play.php?id=2`（m3u8）去 query 后都用 `/play.php` 作为 key，
 *   会导致视频2误用视频1的缓存 mimeType，造成 3002 错误重现。
 * - 改用完整 URL 作为 key：不同 id 视频缓存独立，token 类 query 变化的 URL 缓存命中率降低但不影响功能。
 * - 已知上限：token 类 URL 二次播放用新 token 时无法命中缓存（需重新嗅探），可接受。
 * - 升级路径：如需更精细的 key 策略，可去除 "token"/"sign"/"expires" 等签名参数但保留 id 类参数。
 *
 * 设计文档 AD-04 需在后续同步更新此决策。
 */
object MimeSnifferCache {

    /** 缓存容量：100 个 URL → mimeType 映射（每个 entry 约 200 字节，总计约 20KB） */
    private const val MAX_CACHE_SIZE = 100

    /** TTL：1 小时（毫秒），避免源切换格式后误判 */
    private const val CACHE_TTL_MS = 60L * 60 * 1000

    /** 内部缓存条目：mimeType + 写入时间戳（mimeType 可空，缓存"未知"标记避免重复嗅探） */
    private data class CacheEntry(
        val mimeType: String?,
        val timestamp: Long
    )

    /**
     * 缓存值包装类：区分"未命中"（get 返回 null）和"嗅探过但未识别"（get 返回 CacheValue(null)）
     *
     * 设计理由：
     * - 未命中：需要发起 Range 请求嗅探
     * - 未识别：已嗅探过但 magic number 不匹配，避免重复嗅探浪费网络请求
     */
    data class CacheValue(val mimeType: String?)

    private val cache = LruCache<String, CacheEntry>(MAX_CACHE_SIZE)

    /**
     * 获取缓存的 mimeType（若未过期）
     *
     * @param url 完整 URL（含 query）
     * @return [CacheValue]（命中，mimeType 可能为 null 表示未识别）或 null（缓存未命中 / 已过期）
     */
    @Synchronized
    fun get(url: String): CacheValue? {
        val entry = cache.get(url) ?: return null
        val now = System.currentTimeMillis()
        if (now - entry.timestamp > CACHE_TTL_MS) {
            // 过期清理
            cache.remove(url)
            return null
        }
        return CacheValue(entry.mimeType)
    }

    /**
     * 缓存嗅探结果
     *
     * @param url 完整 URL（含 query）
     * @param mimeType 嗅探得到的 mimeType，可为 null（表示"嗅探过但未识别"，缓存 null 避免重复嗅探失败）
     */
    @Synchronized
    fun put(url: String, mimeType: String?) {
        cache.put(url, CacheEntry(mimeType, System.currentTimeMillis()))
    }

    /**
     * 清除全部缓存（用于调试或源切换场景）
     */
    @Synchronized
    fun clear() {
        cache.evictAll()
    }

    /**
     * 获取当前缓存大小（用于调试日志）
     */
    @Synchronized
    fun size(): Int = cache.size()
}
