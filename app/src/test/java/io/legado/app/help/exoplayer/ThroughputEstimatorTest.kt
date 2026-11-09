package io.legado.app.help.exoplayer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ThroughputEstimator] 行为测试。
 *
 * 覆盖 §1.19 高速网络提速的关键契约：
 * 实测吞吐能替代长期滞后的带宽计估计，且变慢时快速收敛、过期后不采信。
 */
class ThroughputEstimatorTest {

    @Test
    fun `样本换算-字节与耗时得到bps`() {
        // 1,000,000 字节 / 1000ms = 8Mbps
        assertEquals(8_000_000L, ThroughputEstimator.sampleBps(1_000_000L, 1_000L))
    }

    @Test
    fun `样本换算-过小字节或耗时返回零`() {
        assertEquals(0L, ThroughputEstimator.sampleBps(1024L, 1_000L))
        assertEquals(0L, ThroughputEstimator.sampleBps(1_000_000L, 5L))
        assertEquals(0L, ThroughputEstimator.sampleBps(0L, 0L))
    }

    @Test
    fun `样本换算-离谱值被上限钳制`() {
        assertEquals(
            ThroughputEstimator.MAX_PLAUSIBLE_BPS,
            ThroughputEstimator.sampleBps(Long.MAX_VALUE / 1000L, 20L),
        )
    }

    @Test
    fun `EMA-首样本直接作为初值`() {
        assertEquals(50_000_000L, ThroughputEstimator.update(0L, 50_000_000L))
    }

    @Test
    fun `EMA-上升方向按alpha平滑`() {
        // 10M → 30M，alpha=0.5 ⇒ 20M
        assertEquals(20_000_000L, ThroughputEstimator.update(10_000_000L, 30_000_000L, 0.5))
    }

    @Test
    fun `EMA-变慢时用快速下降系数收敛`() {
        // 100M → 1M，下降权重取 max(0.35, 0.6)=0.6 ⇒ 100M - 0.6*99M = 40.6M
        assertEquals(40_600_000L, ThroughputEstimator.update(100_000_000L, 1_000_000L))
        // 连续变慢 3 次后应明显低于原值的 50%
        var v = 100_000_000L
        repeat(3) { v = ThroughputEstimator.update(v, 1_000_000L) }
        assertEquals(true, v < 50_000_000L)
    }

    @Test
    fun `EMA-无效样本不改变原值`() {
        assertEquals(12_345_678L, ThroughputEstimator.update(12_345_678L, 0L))
    }

    @Test
    fun `EMA-结果永不为零`() {
        assertEquals(true, ThroughputEstimator.update(1L, 1L) >= 1L)
    }

    @Test
    fun `有效带宽-取实测与带宽计较大值`() {
        assertEquals(80_000_000L, ThroughputEstimator.effective(2_400_000L, 80_000_000L, 0L))
        assertEquals(10_000_000L, ThroughputEstimator.effective(10_000_000L, 3_000_000L, 0L))
    }

    @Test
    fun `有效带宽-实测过期后折半而非断崖归零`() {
        // 200M 过期一个周期 ⇒ 100M，仍高于带宽计 ⇒ 取 100M（避免稳态缓冲填满后预算跌到下限）
        assertEquals(
            100_000_000L,
            ThroughputEstimator.effective(
                meterBps = 2_400_000L,
                observedBps = 200_000_000L,
                observedAgeMs = ThroughputEstimator.STALE_MS + 1,
            ),
        )
    }

    @Test
    fun `有效带宽-过期多周期继续折半`() {
        // 折半 3 次：200M → 25M
        assertEquals(
            25_000_000L,
            ThroughputEstimator.effective(
                meterBps = 2_400_000L,
                observedBps = 200_000_000L,
                observedAgeMs = ThroughputEstimator.STALE_MS * 3,
            ),
        )
    }

    @Test
    fun `有效带宽-衰减低于带宽计时取带宽计`() {
        assertEquals(
            2_400_000L,
            ThroughputEstimator.effective(
                meterBps = 2_400_000L,
                observedBps = 4_000_000L,
                observedAgeMs = ThroughputEstimator.STALE_MS + 1,
            ),
        )
    }

    @Test
    fun `有效带宽-无任何样本时为零`() {
        assertEquals(0L, ThroughputEstimator.effective(0L, 0L, Long.MAX_VALUE))
    }
}
