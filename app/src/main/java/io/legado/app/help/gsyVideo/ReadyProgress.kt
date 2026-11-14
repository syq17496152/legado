package io.legado.app.help.gsyVideo

/**
 * 灰条「就绪进度」计算（AD-39）——纯函数，便于 JVM 单测。
 *
 * 就绪 = `max(内存桶, 磁盘预取游标)`：只有内存桶时灰条只到几十秒，
 * 用户会误以为「缓冲一会就停」；磁盘已落盘的部分同样可秒开，必须计入。
 */
internal object ReadyProgress {

    /**
     * @param directReadyMs 直链 MP4 已落盘预取的“媒体毫秒”（无直链传 0）
     * @return 0..100；时长未知返回 0
     */
    fun percent(
        bufferedPositionMs: Long,
        prefetchCursorMs: Long,
        durationMs: Long,
        directReadyMs: Long = 0L,
    ): Int {
        if (durationMs <= 0L) return 0
        val ready = maxOf(
            bufferedPositionMs.coerceAtLeast(0L),
            prefetchCursorMs.coerceAtLeast(0L),
            directReadyMs.coerceAtLeast(0L),
        ).coerceAtMost(durationMs)
        return (ready * 100L / durationMs).toInt().coerceIn(0, 100)
    }

    /**
     * 会话内灰条单调化（2026-11-13 用户反馈「缓冲条来回跳」）。
     *
     * 内存桶 `bufferedPosition` 会在降级重建/换轨/短时重缓冲时回退，导致灰条倒退；
     * 就绪进度在语义上只应前进：同一会话内取历史最大值，仅在 seek/换集时 `reset`。
     */
    fun stabilize(previous: Int, current: Int, reset: Boolean): Int {
        if (reset) return current.coerceIn(0, 100)
        return maxOf(previous, current).coerceIn(0, 100)
    }

    /**
     * 带兜底的就绪百分比（IF-16，2026-11-14 沉浸态灰条「来回跳」）。
     *
     * 时长未知（起播/换集/换轨瞬间）或播放器暂不可用时，**返回上次值**而不是 0：
     * 写 0 会让灰条瞬间归零、下个采样又涨回，用户看到的就是「缓冲条来回跳」。
     * 其余情况与会话内单调合并，语义上就绪进度只应前进。
     */
    fun percentOrKeep(
        previous: Int,
        bufferedPositionMs: Long,
        prefetchCursorMs: Long,
        durationMs: Long,
        directReadyMs: Long = 0L,
    ): Int {
        if (durationMs <= 0L) return previous.coerceIn(0, 100)
        return stabilize(
            previous = previous,
            current = percent(bufferedPositionMs, prefetchCursorMs, durationMs, directReadyMs),
            reset = false,
        )
    }
}
