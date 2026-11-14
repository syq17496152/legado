package io.legado.app.help.exoplayer

/**
 * 长视频回看清理（从 VideoSegmentPrefetcher 拆出，守 G-38 单文件行数）。
 *
 * 设计依据：video-buffer-prefetch-v2 AD-11；本期 R34 回看额度 35% → 15%。
 */
internal object LearnedBackCacheTrimmer {

    /** 已学习清单的分片时间轴 span（回看清理用） */
    fun spans(playlists: Collection<MediaPlaylist>): List<RollingBackCache.Span> {
        val result = ArrayList<RollingBackCache.Span>()
        playlists.forEach { playlist ->
            var acc = 0L
            playlist.segments.forEach { seg ->
                val start = acc
                acc += seg.durationMs.coerceAtLeast(0L)
                result.add(RollingBackCache.Span(key = seg.uri, startMs = start, endMs = acc))
            }
        }
        return result
    }

    /** HLS 分片级回看清理：按分片 key 删资源 */
    fun trim(
        cache: PrefetchCache,
        spans: List<RollingBackCache.Span>,
        positionMs: Long,
        windowMs: Long,
    ): Int {
        if (windowMs <= 0L || spans.isEmpty()) return 0
        val removals = RollingBackCache.plan(spans, positionMs, windowMs, singleKey = false)
        return RollingBackCache.trim(cache, removals)
    }

    /** 直链单 key 回看清理：按 span 精确删 */
    fun trimSingleKey(
        cache: PrefetchCache,
        key: String,
        positionMs: Long,
        windowMs: Long,
        bitrateBps: Long,
    ): Int {
        if (windowMs <= 0L || key.isBlank() || bitrateBps <= 0L) return 0
        val cached = cache.cachedSpans(key)
        if (cached.isEmpty()) return 0
        val spans = cached.map { span ->
            val startMs = span.position * 8L * 1000L / bitrateBps
            val endMs = (span.position + span.length) * 8L * 1000L / bitrateBps
            RollingBackCache.Span(
                key = key,
                startMs = startMs,
                endMs = endMs,
                byteStart = span.position,
                byteEnd = span.position + span.length,
            )
        }
        val removals = RollingBackCache.plan(
            spans,
            positionMs = positionMs,
            windowMs = windowMs,
            singleKey = true,
            bitrateBps = bitrateBps,
        )
        return RollingBackCache.trim(cache, removals)
    }
}
