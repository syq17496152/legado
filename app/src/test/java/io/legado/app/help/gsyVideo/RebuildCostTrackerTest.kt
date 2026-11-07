package io.legado.app.help.gsyVideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 重建耗时计时（design §1.14 / tasks §14.3）
 */
class RebuildCostTrackerTest {

    private var now = 0L
    private val tracker = RebuildCostTracker { now }

    @Test
    fun `未标记时消费返回null`() {
        assertNull(tracker.consumeCostMs())
    }

    @Test
    fun `标记到首帧返回真实耗时`() {
        now = 10_000L
        tracker.markStart()
        now = 11_850L
        assertEquals(1_850L, tracker.consumeCostMs())
    }

    @Test
    fun `只消费一次`() {
        now = 1_000L
        tracker.markStart()
        now = 1_500L
        assertEquals(500L, tracker.consumeCostMs())
        now = 9_000L
        assertNull(tracker.consumeCostMs())
    }

    @Test
    fun `reset 丢弃未消费的计时`() {
        now = 1_000L
        tracker.markStart()
        tracker.reset()
        now = 2_000L
        assertNull(tracker.consumeCostMs())
    }

    @Test
    fun `时钟回拨按零处理`() {
        now = 5_000L
        tracker.markStart()
        now = 4_000L
        assertEquals(0L, tracker.consumeCostMs())
    }

    @Test
    fun `再次标记可重新计时`() {
        now = 1_000L
        tracker.markStart()
        now = 1_200L
        assertEquals(200L, tracker.consumeCostMs())

        now = 5_000L
        tracker.markStart()
        now = 6_000L
        assertEquals(1_000L, tracker.consumeCostMs())
    }
}
