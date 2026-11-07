package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡顿自愈提示策略（design §1.14 #6 / tasks §13.8）
 */
class RebufferAlertPolicyTest {

    @Test
    fun `空统计不提示`() {
        assertFalse(RebufferAlertPolicy.shouldNotify(RebufferStats(), hasAlternativeRoute = true))
    }

    @Test
    fun `单次短缓冲不提示`() {
        val update = RebufferAlertPolicy.record(
            RebufferStats(),
            durationMs = 500L,
            hasAlternativeRoute = true
        )
        assertEquals(1, update.stats.count)
        assertFalse(update.notifySwitchRoute)
    }

    @Test
    fun `累计两次缓冲触发提示`() {
        val first = RebufferAlertPolicy.record(RebufferStats(), 500L, hasAlternativeRoute = true)
        assertFalse(first.notifySwitchRoute)
        val second = RebufferAlertPolicy.record(first.stats, 400L, hasAlternativeRoute = true)
        assertTrue(second.notifySwitchRoute)
        assertEquals(2, second.stats.count)
        assertEquals(900L, second.stats.totalMs)
        assertEquals(500L, second.stats.maxSingleMs)
    }

    @Test
    fun `单次超过三秒立即提示`() {
        val update = RebufferAlertPolicy.record(
            RebufferStats(),
            durationMs = 3_000L,
            hasAlternativeRoute = true
        )
        assertTrue(update.notifySwitchRoute)
        assertEquals(3_000L, update.stats.maxSingleMs)
    }

    @Test
    fun `没有可切线路时只归因不提示`() {
        val first = RebufferAlertPolicy.record(RebufferStats(), 500L, hasAlternativeRoute = false)
        val second = RebufferAlertPolicy.record(first.stats, 5_000L, hasAlternativeRoute = false)
        assertFalse(second.notifySwitchRoute)
        assertEquals(2, second.stats.count)
    }

    @Test
    fun `负时长按零处理`() {
        val update = RebufferAlertPolicy.record(RebufferStats(), -100L, hasAlternativeRoute = true)
        assertEquals(0L, update.stats.totalMs)
        assertEquals(0L, update.stats.maxSingleMs)
        assertEquals(1, update.stats.count)
    }
}
