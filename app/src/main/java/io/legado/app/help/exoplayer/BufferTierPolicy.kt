package io.legado.app.help.exoplayer

/**
 * 带宽档位裁决（纯函数）。
 *
 * 设计依据：docs/specs/video-buffer-prefetch-v2/design.md §1.5 / AD-05
 *
 * 替代旧实现（把「整段加载耗时」当 TTFB，连续 3 次 >1s 就降 WEAK）：
 * 新判据 = 带宽估计 + 需求带宽（码率 × 倍速）+ 卡顿信号，并带恢复滞回。
 */
object BufferTierPolicy {

    /** 降档最小间隔 */
    const val DOWNGRADE_INTERVAL_MS = 30_000L

    /** 恢复最小间隔 */
    const val RECOVER_INTERVAL_MS = 60_000L

    /** 卡顿触发阈值（近 30s 内 rebuffer 次数） */
    const val REBUFFER_TRIGGER = 2

    /** 需求带宽余量 */
    const val REQUIRED_MARGIN = 1.5

    data class Decision(
        val forceTier: ExoPlayerHelper.BandwidthTier?,
        val reason: String,
    )

    private val NONE = Decision(null, "none")

    fun decide(
        currentForceTier: ExoPlayerHelper.BandwidthTier?,
        bandwidthEstimateBps: Long,
        trackBitrateBps: Long,
        speed: Float,
        rebufferCount: Int,
        msSinceLastSwitch: Long,
    ): Decision {
        if (bandwidthEstimateBps <= 0L) return NONE
        val capacity = tierOf(bandwidthEstimateBps)
        val required = SpeedBufferPolicy.requiredBandwidthBps(trackBitrateBps, speed)
        val capacityByDemand = if (required > 0L && bandwidthEstimateBps < required * REQUIRED_MARGIN) {
            oneDown(capacity)
        } else {
            capacity
        }

        // 卡顿信号：只有发生 rebuffer 才主动降档，且取「容量档」与「需求档」中更差者
        if (rebufferCount >= REBUFFER_TRIGGER) {
            if (msSinceLastSwitch < DOWNGRADE_INTERVAL_MS) return NONE
            val target = worseOf(currentForceTier ?: capacity, capacityByDemand)
            if (currentForceTier == target) return NONE
            return Decision(target, "rebuffer")
        }

        // 无卡顿时：需求不满足也降一档（高倍速/高码率场景，不等卡了才降）
        if (capacityByDemand != capacity) {
            if (msSinceLastSwitch < DOWNGRADE_INTERVAL_MS) return NONE
            val target = worseOf(currentForceTier ?: capacity, capacityByDemand)
            if (currentForceTier == target) return NONE
            return Decision(target, "demand")
        }

        // 恢复：强制档位存在且网络明显富余、无卡顿、满足恢复间隔
        if (currentForceTier != null &&
            capacity == ExoPlayerHelper.BandwidthTier.GOOD &&
            msSinceLastSwitch >= RECOVER_INTERVAL_MS
        ) {
            return Decision(null, "recover")
        }
        return NONE
    }

    fun tierOf(bandwidthBps: Long): ExoPlayerHelper.BandwidthTier = when {
        bandwidthBps < 1_000_000L -> ExoPlayerHelper.BandwidthTier.WEAK
        bandwidthBps < 5_000_000L -> ExoPlayerHelper.BandwidthTier.MEDIUM
        else -> ExoPlayerHelper.BandwidthTier.GOOD
    }

    private fun order(tier: ExoPlayerHelper.BandwidthTier): Int = when (tier) {
        ExoPlayerHelper.BandwidthTier.WEAK -> 0
        ExoPlayerHelper.BandwidthTier.MEDIUM -> 1
        ExoPlayerHelper.BandwidthTier.GOOD -> 2
    }

    private fun worseOf(
        a: ExoPlayerHelper.BandwidthTier,
        b: ExoPlayerHelper.BandwidthTier,
    ): ExoPlayerHelper.BandwidthTier = if (order(a) <= order(b)) a else b

    private fun oneDown(tier: ExoPlayerHelper.BandwidthTier): ExoPlayerHelper.BandwidthTier =
        when (tier) {
            ExoPlayerHelper.BandwidthTier.GOOD -> ExoPlayerHelper.BandwidthTier.MEDIUM
            ExoPlayerHelper.BandwidthTier.MEDIUM -> ExoPlayerHelper.BandwidthTier.WEAK
            ExoPlayerHelper.BandwidthTier.WEAK -> ExoPlayerHelper.BandwidthTier.WEAK
        }
}
