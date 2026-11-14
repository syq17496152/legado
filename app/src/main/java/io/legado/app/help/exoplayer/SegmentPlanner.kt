package io.legado.app.help.exoplayer

/**
 * 预取窗口的写盘规划（基线 §1.3 / 本期 AD-31、AD-35）——纯函数，便于 JVM 直测。
 *
 * 职责：把「时间窗口 + 码率 + 字节上限 + 内存桶深度」换算成
 * 「实际要写的分片下标」（按估算字节截断，避免只写半个分片）+ 磁盘池 NEAR/FAR 排序。
 */
internal object SegmentPlanner {

    /**
     * @param starts 每个分片的起始媒体毫秒（与 [segments] 等长）
     * @param indexes 时间窗口内经字节截断后的待写分片下标（升序）
     * @param ordered 同 [indexes]，但 NEAR（即将进内存桶）优先、FAR 次之
     * @param nearCount [ordered] 中属于 NEAR 区的前缀数量
     */
    data class Plan(
        val starts: LongArray,
        val indexes: List<Int>,
        val ordered: List<Int>,
        val nearCount: Int,
    )

    /**
     * @param fallbackBucketMs 内存桶深度未知时的兜底时长（防 NEAR 区为空）
     */
    fun plan(
        segments: List<SegmentPrefetchPolicy.Segment>,
        window: SegmentPrefetchPolicy.Window,
        bitrateBps: Long,
        bufferedPositionMs: Long,
        bucketDepthMs: Long,
        fallbackBucketMs: Long,
    ): Plan? {
        if (segments.isEmpty() || window.isEmpty) return null
        if (window.startIndex < 0 || window.endIndexExclusive > segments.size) return null

        val starts = LongArray(segments.size)
        var acc = 0L
        for (i in segments.indices) {
            starts[i] = acc
            acc += segments[i].durationMs.coerceAtLeast(0L)
        }

        val effectiveBitrate = if (bitrateBps > 0L) {
            bitrateBps
        } else {
            SegmentPrefetchPolicy.ESTIMATE_BITRATE_BPS
        }
        var plannedBytes = 0L
        val indexes = ArrayList<Int>(window.count)
        for (i in window.startIndex until window.endIndexExclusive) {
            // 6s × 8Mbps / 8 × 1.3 = 7.8MB（1.3 为安全系数）
            val estimate = (segments[i].durationMs / 1000.0 * effectiveBitrate / 8.0 * 1.3)
                .toLong()
                .coerceAtLeast(1L)
            if (plannedBytes > 0 && plannedBytes + estimate > window.maxBytes) break
            plannedBytes += estimate
            indexes.add(i)
        }
        if (indexes.isEmpty()) return null

        val bucketDepth = if (bucketDepthMs > 0L) bucketDepthMs else fallbackBucketMs
        val nearEndMs = bufferedPositionMs + bucketDepth
        val ordered = indexes.sortedWith(
            compareBy({ if (starts[it] < nearEndMs) 0 else 1 }, { it })
        )
        return Plan(
            starts = starts,
            indexes = indexes,
            ordered = ordered,
            nearCount = ordered.count { starts[it] < nearEndMs },
        )
    }
}
