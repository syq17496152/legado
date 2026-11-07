package io.legado.app.help.gsyVideo

/**
 * 稳态卡顿计时器（纯逻辑，可 JVM 单测；design §1.14 #6）
 *
 * 判定口径（2026-11-07 真机日志修正）：
 * - 只在「已起播成功」之后开始计时（首缓冲不计入卡顿）；
 * - 用户 seek 后进入稳定期（[SEEK_SETTLE_MS]），期间的 BUFFERING 一律不计入——
 *   真机实测一次拖动会连续产生多段 BUFFERING/READY 抖动（单段 0~11ms），
 *   只拦截「seek 后第一段」会让后续抖动被误判成卡顿并误弹「切换线路」提示；
 * - 小于 [MIN_COUNTED_MS] 的瞬时抖动属于播放器正常换段，不计入用户可感知卡顿。
 *
 * @param clock 时间源（生产传 `SystemClock.uptimeMillis`，测试注入假时钟）
 */
internal class RebufferTracker(
    private val clock: () -> Long,
    private val seekSettleMs: Long = SEEK_SETTLE_MS,
    private val minCountedMs: Long = MIN_COUNTED_MS,
) {

    companion object {
        /** seek 后的稳定期：期间所有缓冲都归因于 seek，不计入卡顿 */
        const val SEEK_SETTLE_MS = 1_500L

        /** 用户可感知的最小卡顿时长，低于此值只视为换段抖动 */
        const val MIN_COUNTED_MS = 200L

        /** 「本会话还未 seek」哨兵值 */
        private const val NO_SEEK = Long.MIN_VALUE
    }

    private var bufferingStartAt = 0L

    /** 最近一次 seek 的时间；[NO_SEEK] 表示本会话还没 seek 过 */
    private var lastSeekAt = NO_SEEK

    /** 用户 seek：刷新稳定期起点（含 seek 前后相邻的多段抖动） */
    fun markSeek() {
        lastSeekAt = clock()
    }

    /** 进入 BUFFERING（调用方保证已起播成功） */
    fun onBufferingStart() {
        bufferingStartAt = clock()
    }

    /**
     * 离开 BUFFERING：返回本次真实卡顿时长（ms），null=不计入。
     */
    fun onBufferingEnd(): Long? {
        if (bufferingStartAt <= 0L) return null
        val startAt = bufferingStartAt
        bufferingStartAt = 0L
        val endAt = clock()
        // 稳定期用「缓冲起点 - seek 时刻」判定：seek 事件可能晚于 BUFFERING 到达，
        // 差值可能为负，同样落在稳定期内，一并忽略。
        if (lastSeekAt != NO_SEEK && startAt - lastSeekAt <= seekSettleMs) {
            return null
        }
        val duration = (endAt - startAt).coerceAtLeast(0L)
        return if (duration < minCountedMs) null else duration
    }

    /** 会话复位（换源/新集） */
    fun reset() {
        bufferingStartAt = 0L
        lastSeekAt = NO_SEEK
    }
}
