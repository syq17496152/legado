package io.legado.app.help.exoplayer

/**
 * 预取器的可测性端口（注入用）。
 *
 * 设计依据：docs/specs/video-buffer-prefetch-v2/audit-implementation-20261109.md B7
 *
 * 生产实现接 `ExoPlayerHelper.cache` 与本地 CacheDataSource；JVM 测试用假实现，
 * 避免在预取器内部直接 new 网络/系统服务导致只能写空壳测试（G-42 会拦）。
 */
data class CachedSpan(val position: Long, val length: Long)

interface PrefetchCache {
    fun isCached(key: String, position: Long, length: Long): Boolean
    fun contentLength(key: String): Long
    fun removeResource(key: String)
    fun removeSpan(key: String, position: Long, length: Long)

    /** 已缓存 span（回看清理用；默认空实现便于测试假对象） */
    fun cachedSpans(key: String): List<CachedSpan> = emptyList()

    /**
     * 缓存当前已用字节（含本集）；未知返回 0。
     *
     * AD-37：池余量必须扣除真实占用，避免「以为能写、实际写不进」。
     */
    fun usedBytes(): Long = 0L
}

/** 分片写入器：把 URI 从 [position] 起的前 [maxBytes] 写入缓存（实现用 CacheDataSource） */
fun interface SegmentWriter {
    suspend fun write(
        uri: String,
        headers: Map<String, String>,
        position: Long,
        maxBytes: Long,
    ): Long
}

/** 清单文本抓取器 */
fun interface ManifestFetcher {
    suspend fun fetch(uri: String, headers: Map<String, String>): String?
}

/** 时钟（测试可注入） */
fun interface PrefetchClock {
    fun nowMs(): Long
}
