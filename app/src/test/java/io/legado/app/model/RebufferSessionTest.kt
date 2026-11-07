package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡顿自愈会话状态（design §1.14 #6 / tasks §13.8）
 *
 * 锁定「同一会话最多提示一次」与复位语义：长视频反复卡顿时不能反复弹提示打断观看。
 */
class RebufferSessionTest {

    @Test
    fun `同一会话达到阈值后只提示一次`() {
        val session = RebufferSession()
        assertFalse(session.record(500L, hasAlternativeRoute = true).notifySwitchRoute)
        assertTrue(session.record(400L, hasAlternativeRoute = true).notifySwitchRoute)
        // 第三次仍满足阈值，但本会话已提示过 ⇒ 不再提示
        assertFalse(session.record(4_000L, hasAlternativeRoute = true).notifySwitchRoute)
        assertTrue(session.alerted)
        assertEquals(3, session.stats.count)
    }

    @Test
    fun `单次超过三秒立即提示并只提示一次`() {
        val session = RebufferSession()
        assertTrue(session.record(3_000L, hasAlternativeRoute = true).notifySwitchRoute)
        assertFalse(session.record(5_000L, hasAlternativeRoute = true).notifySwitchRoute)
    }

    @Test
    fun `无备选线路时只累计不提示`() {
        val session = RebufferSession()
        assertFalse(session.record(500L, hasAlternativeRoute = false).notifySwitchRoute)
        assertFalse(session.record(6_000L, hasAlternativeRoute = false).notifySwitchRoute)
        assertFalse(session.alerted)
        assertEquals(2, session.stats.count)
        assertEquals(6_000L, session.stats.maxSingleMs)
    }

    @Test
    fun `reset 后统计与提示状态清空并可再次提示`() {
        val session = RebufferSession()
        session.record(3_000L, hasAlternativeRoute = true)
        assertTrue(session.alerted)

        session.reset()
        assertEquals(RebufferStats(), session.stats)
        assertFalse(session.alerted)

        assertTrue(session.record(3_000L, hasAlternativeRoute = true).notifySwitchRoute)
    }
}
