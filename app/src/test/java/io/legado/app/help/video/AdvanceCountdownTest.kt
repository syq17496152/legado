package io.legado.app.help.video

import io.legado.app.help.exoplayer.SegmentPrefetchPolicy.NetworkTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播完续播倒计时纯策略（tasks §7.1）
 */
class AdvanceCountdownTest {

    @Test
    fun `无 UI 承载时立即续播`() {
        assertEquals(AdvanceCountdown.Plan.Immediate, AdvanceCountdown.plan(hasUiHost = false))
    }

    @Test
    fun `有 UI 且秒数为正时延迟`() {
        val plan = AdvanceCountdown.plan(hasUiHost = true, seconds = 3)
        assertTrue(plan is AdvanceCountdown.Plan.Delay)
        assertEquals(3, (plan as AdvanceCountdown.Plan.Delay).seconds)
    }

    @Test
    fun `秒数为零或负数视为关闭倒计时`() {
        assertEquals(
            AdvanceCountdown.Plan.Immediate,
            AdvanceCountdown.plan(hasUiHost = true, seconds = 0)
        )
        assertEquals(
            AdvanceCountdown.Plan.Immediate,
            AdvanceCountdown.plan(hasUiHost = true, seconds = -1)
        )
    }

    @Test
    fun `剩余秒数向上取整`() {
        assertEquals(3, AdvanceCountdown.secondsLeft(0L, 3000L))
        assertEquals(3, AdvanceCountdown.secondsLeft(1L, 3000L))
        assertEquals(2, AdvanceCountdown.secondsLeft(1000L, 3000L))
        assertEquals(2, AdvanceCountdown.secondsLeft(1999L, 3000L))
        assertEquals(1, AdvanceCountdown.secondsLeft(2000L, 3000L))
        assertEquals(1, AdvanceCountdown.secondsLeft(2999L, 3000L))
        assertEquals(0, AdvanceCountdown.secondsLeft(3000L, 3000L))
    }

    @Test
    fun `超时与非法总时长归零`() {
        assertEquals(0, AdvanceCountdown.secondsLeft(5000L, 3000L))
        assertEquals(0, AdvanceCountdown.secondsLeft(0L, 0L))
        assertEquals(0, AdvanceCountdown.secondsLeft(0L, -1L))
    }

    @Test
    fun `倒计时预热片数按档位映射`() {
        assertEquals(3, AdvanceCountdown.warmupPlan(NetworkTier.WEAK, false, 100).count)
        assertEquals(8, AdvanceCountdown.warmupPlan(NetworkTier.STANDARD, false, 100).count)
        assertEquals(12, AdvanceCountdown.warmupPlan(NetworkTier.FAST, false, 100).count)
        assertEquals(20, AdvanceCountdown.warmupPlan(NetworkTier.ULTRA, false, 100).count)
        assertEquals(20, AdvanceCountdown.warmupPlan(NetworkTier.EXTREME, false, 100).count)
        assertEquals(8, AdvanceCountdown.warmupPlan(null, false, 100).count)
    }

    @Test
    fun `短视频预热片数不超过剩余片数`() {
        assertEquals(5, AdvanceCountdown.warmupPlan(NetworkTier.ULTRA, isShort = true, remainingSegments = 5).count)
        assertEquals(0, AdvanceCountdown.warmupPlan(NetworkTier.ULTRA, isShort = true, remainingSegments = 0).count)
    }
}
