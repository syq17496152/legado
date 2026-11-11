package io.legado.app.help.exoplayer

/**
 * 实测吞吐估算（纯函数）。
 *
 * 设计依据：docs/specs/video-buffer-prefetch-v2/design.md §1.19（AD-17 高速网络提速）
 *
 * 背景（2026-11-09 模拟器铁证）：
 * `DefaultBandwidthMeter.bitrateEstimate` 只反映「播放器加载器」的滑动窗口样本，
 * 起播瞬间常停在初始估计值（实测 2.4Mbps），与 5G/WiFi6 实际 100Mbps+ 吞吐严重背离，
 * 导致预取预算只有 720KB/s、120s 大窗口永不触发。
 *
 * 因此用 [androidx.media3.exoplayer.analytics.AnalyticsListener.onLoadCompleted]
 * 的 per-load 实测吞吐（bytes × 8000 / loadDurationMs）做 EMA，
 * 再与带宽计估计取较大值，保证「网越快 → 窗口越大、预算越高」真正生效。
 */
object ThroughputEstimator {

    /** 上升方向 EMA 系数（平滑突发抖动，避免预算瞬间拉满） */
    const val DEFAULT_ALPHA = 0.35

    /** 下降方向 EMA 系数（网络变差要快速收敛，防止继续按高速预算预取） */
    const val FAST_DOWN_ALPHA = 0.60

    /** 小于该字节数的样本视为噪声（探测请求/重定向/4KB 头），不参与估计 */
    const val MIN_VALID_BYTES = 8L * 1024L

    /** 小于该耗时的样本无法可靠换算吞吐（计时精度不足） */
    const val MIN_VALID_DURATION_MS = 20L

    /** 合理性上限 2Gbps（防异常计时导致的离谱值污染预算） */
    const val MAX_PLAUSIBLE_BPS = 2_000_000_000L

    /** 样本过期周期：超过该时长未更新则开始折半衰减（避免暂停后仍按旧高速预算） */
    const val STALE_MS = 90_000L

    /**
     * 由单次加载字节数与耗时换算吞吐（bps）。
     *
     * @return 0 表示样本不可用（过小/耗时不可信），调用方应跳过本次更新
     */
    fun sampleBps(bytesLoaded: Long, loadDurationMs: Long): Long {
        if (bytesLoaded < MIN_VALID_BYTES) return 0L
        if (loadDurationMs < MIN_VALID_DURATION_MS) return 0L
        // 用 Double 换算：bytes × 8000 在极端大字节数下会溢出 Long（实测用例翻车）
        val bps = bytesLoaded.toDouble() * 8000.0 / loadDurationMs.toDouble()
        if (!bps.isFinite() || bps <= 0.0) return 0L
        return bps.toLong().coerceIn(0L, MAX_PLAUSIBLE_BPS)
    }

    /**
     * EMA 更新。
     *
     * - 首个有效样本直接作为初值（不衰减，起播即可用实测值）
     * - 变慢时用 [FAST_DOWN_ALPHA] 快速收敛，变快时用 [alpha] 平滑上升
     */
    fun update(previousBps: Long, sampleBps: Long, alpha: Double = DEFAULT_ALPHA): Long {
        if (sampleBps <= 0L) return previousBps.coerceAtLeast(0L)
        if (previousBps <= 0L) return sampleBps
        val weight = if (sampleBps < previousBps) {
            maxOf(alpha, FAST_DOWN_ALPHA)
        } else {
            alpha.coerceIn(0.05, 1.0)
        }
        val blended = previousBps + (sampleBps - previousBps) * weight
        return blended.toLong().coerceAtLeast(1L)
    }

    /**
     * 有效带宽 = max(带宽计估计, 实测 EMA 的衰减值)。
     *
     * 衰减规则：样本超过 [STALE_MS] 未更新，每过一个周期折半（最多折 5 次 ≈ 1/32）。
     * 为什么不是直接丢弃：缓冲填满时播放器会停止加载，此时没有新样本，
     * 若直接归零会让预取预算断崖式跌到 `MIN_BUDGET_BPS`，seek/切集后首轮预取被白白限速。
     * 折半既能让长时间暂停/切后台后逐步收手，又不会在稳态播放中误伤。
     */
    fun effective(meterBps: Long, observedBps: Long, observedAgeMs: Long = 0L): Long {
        var observed = observedBps.coerceAtLeast(0L)
        if (observed > 0L && observedAgeMs > STALE_MS) {
            val periods = ((observedAgeMs - STALE_MS) / STALE_MS).toInt().coerceIn(0, 4)
            repeat(periods + 1) { observed /= 2 }
        }
        return maxOf(meterBps.coerceAtLeast(0L), observed)
    }
}
