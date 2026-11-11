package io.legado.app.help.exoplayer

/**
 * tasks §13.1：缓存命中率（%）。
 *
 * 口径：`CacheDataSource.EventListener.onCachedBytesRead` 给的是「本次从 SimpleCache
 * 读出的字节」，与累计网络加载字节相加即为总读取量；两者皆 0 时返回 0（未知，不臆造）。
 */
internal fun cacheHitRatePercent(cachedBytes: Long, networkBytes: Long): Int {
    val total = cachedBytes + networkBytes
    if (cachedBytes <= 0L || total <= 0L) return 0
    return ((cachedBytes * 100) / total).toInt().coerceIn(0, 100)
}
