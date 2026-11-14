package io.legado.app.help.exoplayer

/**
 * 倍速缓冲策略（纯函数）。
 *
 * 设计依据：docs/specs/video-buffer-prefetch-v2/design.md §1.15
 *
 * 核心：Media3 的缓冲时长按「媒体时间」计，2x 播放时 120s 媒体缓冲只够 60s 实时；
 * 因此预取窗口与缓冲深度必须随 speed 放大，档位判定用「需求带宽 = 码率 × speed」。
 */
object SpeedBufferPolicy {

    const val MIN_SPEED = 0.25f

    /**
     * 倍速上限：与播放器真实可选倍速对齐（真机实测 `speed=10.0`，IF-14）。
     *
     * 若仍按 4x 夹取，`requiredBandwidthBps` 会把需求带宽低估 2.5 倍 ⇒
     * `shouldDowngrade`/`BufferTierPolicy` 误判「带宽够」⇒ 高码率 + 高倍速必卡。
     */
    const val MAX_SPEED = 10.0f

    /**
     * 缓冲/窗口放大的倍速上限（与 [MAX_SPEED] 解耦）。
     *
     * 需求带宽判断必须用真实倍速，但缓冲深度不需要同比放大：minBuffer 若按 10x 拉到 60s，
     * 加载器会持续满载下载，与「高倍速让位给即时播放」相悖。
     */
    const val BUFFER_SCALE_MAX_SPEED = 4.0f

    /** 预取窗口上限（媒体毫秒） */
    const val MAX_WINDOW_MS = 180_000

    /** 缓冲深度上限（媒体毫秒） */
    const val MAX_BUFFER_MS = 300_000

    /** minBuffer 放大上限（媒体毫秒）：高倍速下避免加载器过早停止 */
    const val MAX_MIN_BUFFER_MS = 60_000

    /** 判定降档的余量：estimate < required × 1.5 */
    const val DOWNGRADE_MARGIN = 1.5

    /** 防御异常倍速（GSY/上层传入 0 或负数） */
    fun sanitizeSpeed(speed: Float): Float =
        if (speed.isNaN() || speed <= 0f) 1f else speed.coerceIn(MIN_SPEED, MAX_SPEED)

    /** 播放该码率在 speed 下所需的持续带宽（bps） */
    fun requiredBandwidthBps(trackBitrateBps: Long, speed: Float): Long {
        if (trackBitrateBps <= 0L) return 0L
        return (trackBitrateBps * sanitizeSpeed(speed)).toLong()
    }

    /** 预取窗口按倍速放大（只放大不缩小），放大倍速封顶 [BUFFER_SCALE_MAX_SPEED]，窗口封顶 [MAX_WINDOW_MS] */
    fun scaledWindowMs(baseWindowMs: Long, speed: Float): Long {
        if (baseWindowMs <= 0L) return 0L
        val s = sanitizeSpeed(speed).coerceAtMost(BUFFER_SCALE_MAX_SPEED)
        val factor = if (s < 1f) 1f else s
        return (baseWindowMs * factor).toLong().coerceIn(baseWindowMs, MAX_WINDOW_MS.toLong())
    }

    /**
     * 预取预算系数（按倍速**下调**）。
     *
     * 方向说明（2026-11-13 复判 IF-14）：倍速越高，即时播放的码率需求越大（码率 × speed），
     * 预取必须让位，否则与播放抢同一份带宽 ⇒ 缓冲水位被击穿。
     * 原实现「≥1.5x 放宽到 0.35」方向相反，已废弃。
     *
     * 阶梯：<1.5x → 0.30（基线）；<2x → 0.25；<4x → 0.20；≥4x → 0.15。
     */
    fun budgetRatio(speed: Float): Double {
        val s = sanitizeSpeed(speed)
        return when {
            s < 1.5f -> 0.30
            s < 2.0f -> 0.25
            s < 4.0f -> 0.20
            else -> 0.15
        }
    }

    /**
     * 预取实际预算比。
     *
     * - 常规倍速（<1.5x）：档位比例**原样生效**，不压缩好网档位已有的激进预算（0.45~0.65）；
     * - 高倍速（≥1.5x）：取「档位比例」与「倍速比例」的较小者，保证预取让位给即时播放。
     */
    fun effectiveBudgetRatio(tierRatio: Double, speed: Float): Double {
        val s = sanitizeSpeed(speed)
        if (s < 1.5f) return tierRatio
        return minOf(tierRatio, budgetRatio(s))
    }

    /** prepare 时放大 maxBuffer（只放大不缩小），放大倍速封顶 [BUFFER_SCALE_MAX_SPEED]，深度封顶 [MAX_BUFFER_MS] */
    fun scaledMaxBufferMs(baseMaxBufferMs: Int, speed: Float): Int {
        if (baseMaxBufferMs <= 0) return baseMaxBufferMs
        val s = sanitizeSpeed(speed).coerceAtMost(BUFFER_SCALE_MAX_SPEED)
        val factor = if (s < 1f) 1f else s
        return (baseMaxBufferMs * factor).toInt().coerceIn(baseMaxBufferMs, MAX_BUFFER_MS)
    }

    /**
     * prepare 时放大 minBuffer（只放大不缩小），放大倍速封顶 [BUFFER_SCALE_MAX_SPEED]，
     * 深度封顶 [MAX_MIN_BUFFER_MS]。
     *
     * 高倍速下媒体时间消耗更快，minBuffer 不放大时加载器会更早停止、缓冲余量不足。
     */
    fun scaledMinBufferMs(baseMinBufferMs: Int, speed: Float): Int {
        if (baseMinBufferMs <= 0) return baseMinBufferMs
        val s = sanitizeSpeed(speed).coerceAtMost(BUFFER_SCALE_MAX_SPEED)
        val factor = if (s < 1f) 1f else s
        return (baseMinBufferMs * factor).toInt()
            .coerceIn(baseMinBufferMs, MAX_MIN_BUFFER_MS)
    }

    /** 是否应降档：带宽估计低于需求 × 余量 */
    fun shouldDowngrade(bandwidthEstimateBps: Long, trackBitrateBps: Long, speed: Float): Boolean {
        if (bandwidthEstimateBps <= 0L || trackBitrateBps <= 0L) return false
        val required = requiredBandwidthBps(trackBitrateBps, speed)
        return bandwidthEstimateBps < required * DOWNGRADE_MARGIN
    }
}
