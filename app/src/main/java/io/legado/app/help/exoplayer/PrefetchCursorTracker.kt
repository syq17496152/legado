package io.legado.app.help.exoplayer

import java.util.concurrent.ConcurrentHashMap

/**
 * 磁盘预取游标（AD-35）：「连续就绪分片」的进度账本。
 *
 * 职责边界：
 * - 记录/推进每个清单的游标，判定分片是否已就绪；
 * - 不发起任何网络或写盘（由 [VideoSegmentPrefetcher] 执行）。
 *
 * 为什么需要它：磁盘池写入**不推进** `player.bufferedPosition`（那是内存桶），
 * 若窗口起点继续绑缓冲头，命中后原地打转 ⇒ 真机「缓冲一小段就停」。
 *
 * 端口以 lambda 注入，`resetForTest` 替换端口后无需重建本对象（始终读当前值）。
 */
internal class PrefetchCursorTracker(
    private val cache: () -> PrefetchCache,
    private val doneKeys: () -> Set<String>,
) {

    /** 清单 URI → 媒体毫秒（最后一个连续就绪分片的末尾） */
    private val cursors = ConcurrentHashMap<String, Long>()

    /** 切集/切源/退出/seek 时清空（跳转后原区间不再连续） */
    fun clear() {
        cursors.clear()
    }

    fun cursorOf(playlistUri: String): Long? = cursors[playlistUri]

    fun put(playlistUri: String, cursorMs: Long) {
        cursors[playlistUri] = cursorMs
    }

    /**
     * 多清单游标的最小值（视频/音频任一落后就不能宣称更远）。
     *
     * 无游标返回 0：调用方与 `bufferedPosition` 取 max，不改变原语义。
     */
    fun minCursor(playlistUris: Collection<String>): Long =
        playlistUris.mapNotNull { cursors[it] }.minOrNull()?.coerceAtLeast(0L) ?: 0L

    /**
     * 分片是否已就绪。
     *
     * - 字节区间分片（`rangeLength>0`）：按 `[rangeOffset, rangeOffset+rangeLength)` 判定，
     *   键必须带区间，否则同一文件的多个分片会互相误判（AD-43）；
     * - 普通分片：本次会话已写，或缓存已完整命中。
     */
    fun isReady(segment: SegmentPrefetchPolicy.Segment): Boolean {
        if (segment.isRange) {
            if (doneKeys().contains(segment.cacheKey)) return true
            return cache().isCached(segment.uri, segment.rangeOffset, segment.rangeLength)
        }
        if (doneKeys().contains(segment.uri)) return true
        val len = cache().contentLength(segment.uri)
        return len > 0L && cache().isCached(segment.uri, 0L, len)
    }

    /**
     * 从 [fromIndex] 起「连续就绪分片」的末尾（媒体毫秒）。
     *
     * 遇到首个未就绪分片即停：该片下一轮重试，但不阻塞此前已就绪的进度记录。
     */
    fun lastContinuousReadyEnd(
        starts: LongArray,
        durations: LongArray,
        segments: List<SegmentPrefetchPolicy.Segment>,
        fromIndex: Int,
        toIndexExclusive: Int,
    ): Long {
        val end = minOf(toIndexExclusive, segments.size)
        var result = starts.getOrElse(fromIndex) { 0L }
        var i = fromIndex
        while (i in 0 until end) {
            if (!isReady(segments[i])) break
            result = starts[i] + durations[i].coerceAtLeast(0L)
            i++
        }
        return result
    }

    /**
     * 推进单清单游标（只增不减，clamp 到总时长）；有变化时回调 [onAdvanced]。
     *
     * 轮内每批写完后调用：整轮未结束也能让 `prefetchCursorMs()`/灰条立即前进。
     */
    fun advanceFrom(
        playlistUri: String,
        starts: LongArray,
        durations: LongArray,
        segments: List<SegmentPrefetchPolicy.Segment>,
        fromIndex: Int,
        toIndexExclusive: Int,
        totalMs: Long,
        onAdvanced: (previous: Long?, next: Long) -> Unit = { _, _ -> },
    ) {
        val before = cursors[playlistUri]
        val end = lastContinuousReadyEnd(starts, durations, segments, fromIndex, toIndexExclusive)
        val after = maxOf(before ?: 0L, end).coerceIn(0L, totalMs)
        if (after == (before ?: -1L)) return
        cursors[playlistUri] = after
        onAdvanced(before, after)
    }
}
