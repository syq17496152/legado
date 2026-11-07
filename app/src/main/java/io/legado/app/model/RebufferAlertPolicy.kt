package io.legado.app.model

/**
 * 会话内卡顿统计（纯数据，可 JVM 单测）。
 *
 * 只统计**稳态播放中**的缓冲等待；起播首缓冲与用户 seek 引发的缓冲由采集侧过滤，
 * 否则每次拖动进度条都会被误判为卡顿（design §1.14 #6）。
 */
data class RebufferStats(
    val count: Int = 0,
    val maxSingleMs: Long = 0L,
    val totalMs: Long = 0L,
)

/** 一次卡顿记录的结果：已累计的统计 + 是否应提示切换线路 */
data class RebufferUpdate(
    val stats: RebufferStats,
    val notifySwitchRoute: Boolean,
)

/**
 * 卡顿自愈策略（design §1.14 #6，纯函数可单测）
 *
 * 触发条件（任一）：本轮缓冲等待 ≥2 次，或单次 ≥3s。
 * 满足条件且存在可切换线路时才提示；没有其它线路时只记录归因（提示也无处可切）。
 */
object RebufferAlertPolicy {

    /** 次数阈值 */
    const val COUNT_THRESHOLD = 2

    /** 单次时长阈值（毫秒） */
    const val SINGLE_THRESHOLD_MS = 3_000L

    /** 纯函数：累计一次卡顿并给出是否提示 */
    fun record(
        stats: RebufferStats,
        durationMs: Long,
        hasAlternativeRoute: Boolean,
    ): RebufferUpdate {
        val duration = durationMs.coerceAtLeast(0L)
        val updated = RebufferStats(
            count = stats.count + 1,
            maxSingleMs = maxOf(stats.maxSingleMs, duration),
            totalMs = stats.totalMs + duration,
        )
        return RebufferUpdate(updated, shouldNotify(updated, hasAlternativeRoute))
    }

    /** 纯函数：是否达到提示阈值，且存在可切换线路 */
    fun shouldNotify(stats: RebufferStats, hasAlternativeRoute: Boolean): Boolean =
        hasAlternativeRoute && (stats.count >= COUNT_THRESHOLD || stats.maxSingleMs >= SINGLE_THRESHOLD_MS)
}
