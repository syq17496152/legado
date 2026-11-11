package io.legado.app.help.exoplayer

import androidx.media3.datasource.cache.ContentMetadata
import io.legado.app.constant.AppLog

/**
 * 磁盘池治理（review P0-4 / AD-33）。
 *
 * 问题：`SimpleCache` 的 `LeastRecentlyUsedCacheEvictor` 是**全局 LRU**，不区分视频。
 * 连续看多部时，新视频的整集全量会把旧视频（甚至当前视频早期分片）挤掉。
 *
 * 方案：进入整集模式前做「容量预检 + 定向清理」：
 * 1. 只清理**非当前视频**的 key（当前会话已学习的分片 URI / 直链 URI 受保护）；
 * 2. 清理到满足 `needBytes` 或达到池子上限即停；
 * 3. 仍不足时由调用方降级为 `FILL_TO_CAPACITY`，不强行整集。
 *
 * 纯函数 [plan] 可 JVM 直测；[govern] 负责生产执行（读 `ExoPlayerHelper.cache`）。
 */
object CacheGovernor {

    /** 单次治理最多删除的 key 数（防长时间阻塞预取线程） */
    const val MAX_REMOVE_KEYS = 512

    data class Entry(val key: String, val sizeBytes: Long)

    data class Plan(
        val keysToRemove: List<String>,
        val freedBytes: Long,
        val satisfied: Boolean,
    )

    /**
     * 计划清理：按传入顺序（调用方按最旧优先排序）删除非保护 key，直到释放 ≥ [needBytes]。
     *
     * @param entries 缓存条目（key + 字节数）
     * @param protectedKeys 当前视频的 key（绝不删除）
     * @param needBytes 需要释放的字节数
     */
    fun plan(
        entries: List<Entry>,
        protectedKeys: Set<String>,
        needBytes: Long,
    ): Plan {
        if (needBytes <= 0L) return Plan(emptyList(), 0L, true)
        var freed = 0L
        val remove = ArrayList<String>()
        for (entry in entries) {
            if (entry.key in protectedKeys) continue
            if (remove.size >= MAX_REMOVE_KEYS) break
            remove.add(entry.key)
            freed += entry.sizeBytes.coerceAtLeast(0L)
            if (freed >= needBytes) break
        }
        return Plan(remove, freed, freed >= needBytes)
    }

    /**
     * 生产执行：读取磁盘池 key/大小，按 [plan] 清理非保护 key。
     *
     * @return true=已释放足够空间（可进入整集模式）；false=空间仍不足（降级填满池子）
     */
    fun govern(
        protectedKeys: Set<String>,
        needBytes: Long,
        cacheSizeBytes: Long,
    ): Boolean {
        if (needBytes <= 0L) return true
        val cache = runCatching { ExoPlayerHelper.cache }.getOrNull() ?: return false
        val entries = runCatching {
            cache.keys.map { key ->
                val metadata = cache.getContentMetadata(key)
                Entry(key, ContentMetadata.getContentLength(metadata).coerceAtLeast(0L))
            }
        }.getOrDefault(emptyList())
        val plan = plan(entries, protectedKeys, needBytes)
        var freed = 0L
        plan.keysToRemove.forEach { key ->
            val ok = runCatching { cache.removeResource(key) }.isSuccess
            if (ok) {
                freed += entries.firstOrNull { it.key == key }?.sizeBytes ?: 0L
            }
        }
        AppLog.put(
            "[BufferSpeed] cache_govern need=$needBytes freed=$freed removed=${plan.keysToRemove.size} " +
                "satisfied=${freed >= needBytes} cacheSize=$cacheSizeBytes"
        )
        return freed >= needBytes
    }
}
