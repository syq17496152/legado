package io.legado.app.help.exoplayer

/**
 * 长视频滚动回看缓存策略（纯函数 + 清理计划）。
 *
 * 设计依据：docs/specs/video-buffer-prefetch-v2/design.md §1.13 / AD-11
 *
 * 关键差异（审计 B2）：
 * - HLS 每片一个缓存 key ⇒ 可 `removeResource(key)`；
 * - 直链 MP4/FLV 只有一个 key ⇒ **必须按 span 精确删**，否则会把前向预取的 span 一起删掉。
 */
object RollingBackCache {

    /** 时长轴上的分片区间（HLS 用 startMs/endMs；直链用 byteStart/byteEnd） */
    data class Span(
        val key: String,
        val startMs: Long,
        val endMs: Long,
        val byteStart: Long = 0L,
        val byteEnd: Long = 0L,
    )

    enum class Kind { RESOURCE, SPAN }

    data class Removal(
        val kind: Kind,
        val key: String,
        val byteStart: Long = 0L,
        val byteEnd: Long = 0L,
    )

    /**
     * 生成清理计划。
     *
     * @param positionMs 当前播放位置
     * @param windowMs 回看窗口（已按容量钳制）
     * @param singleKey 是否单 key（直链）
     * @param bitrateBps 直链码率估算（单 key 时用于把时间换算成字节）
     */
    fun plan(
        spans: List<Span>,
        positionMs: Long,
        windowMs: Long,
        singleKey: Boolean,
        bitrateBps: Long = 0L,
    ): List<Removal> {
        if (windowMs <= 0L || spans.isEmpty()) return emptyList()
        val cutoffMs = (positionMs - windowMs).coerceAtLeast(0L)

        if (!singleKey) {
            return spans
                .filter { it.endMs <= cutoffMs }
                .map { Removal(Kind.RESOURCE, it.key) }
                .distinctBy { it.key }
        }

        // 单 key：按字节窗口精确删除
        if (bitrateBps <= 0L) return emptyList() // 无法换算，交给 LRU
        val cutoffBytes = cutoffMs * bitrateBps / 8L / 1000L
        return spans
            .filter { it.byteEnd <= cutoffBytes && it.byteEnd > it.byteStart }
            .map { Removal(Kind.SPAN, it.key, it.byteStart, it.byteEnd - it.byteStart) }
    }

    /** 执行清理（注入 [PrefetchCache] 便于 JVM 测试） */
    fun trim(cache: PrefetchCache, removals: List<Removal>): Int {
        var count = 0
        removals.forEach { removal ->
            kotlin.runCatching {
                when (removal.kind) {
                    Kind.RESOURCE -> cache.removeResource(removal.key)
                    Kind.SPAN -> cache.removeSpan(removal.key, removal.byteStart, removal.byteEnd)
                }
                count++
            }
        }
        return count
    }
}
