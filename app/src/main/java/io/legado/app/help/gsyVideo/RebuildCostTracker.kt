package io.legado.app.help.gsyVideo

import io.legado.app.help.video.VideoTrace

/**
 * 重建耗时计时（design §1.14 / tasks §14.3，纯逻辑可 JVM 单测）
 *
 * 口径：`initVideo` 开始 → 首帧到达。只消费一次，避免 READY 多次回调重复计时。
 */
internal class RebuildCostTracker(private val clock: () -> Long) {

    private var startedAt = 0L

    fun markStart() {
        startedAt = clock()
    }

    /** @return 重建耗时（ms）；未标记或已消费过返回 null */
    fun consumeCostMs(): Long? {
        if (startedAt <= 0L) return null
        val cost = (clock() - startedAt).coerceAtLeast(0L)
        startedAt = 0L
        return cost
    }

    fun reset() {
        startedAt = 0L
    }
}

/**
 * 进程级重建耗时埋点（播放器全局单例，同一时刻只有一次重建）。
 */
internal object PlaybackRebuildTrace {

    private val tracker = RebuildCostTracker { android.os.SystemClock.uptimeMillis() }

    /** initVideo 开始时调用 */
    fun onSetupStart() {
        tracker.markStart()
    }

    /** 首帧到达时调用：命中则输出 `stage=advance_rebuild costMs=` */
    fun onFirstFrame() {
        tracker.consumeCostMs()?.let { costMs ->
            VideoTrace.event("advance_rebuild", "costMs" to costMs)
        }
    }

    /** 会话结束/释放时复位 */
    fun reset() {
        tracker.reset()
    }
}
