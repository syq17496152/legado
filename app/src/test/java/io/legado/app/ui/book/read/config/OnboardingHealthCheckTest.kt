package io.legado.app.ui.book.read.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引导完成前体检 + 移动网络下载确认（design §1.17 第 4 维 / R21 / S18）。
 * 纯函数断言：三项检查的完成态、待办数、以及"蜂窝才拦、确认后放行"的判定。
 */
class OnboardingHealthCheckTest {

    @Test
    fun `三项检查未完成时各给一个可点动作`() {
        val items = OnboardingHealthCheck.items(
            notificationsOk = false,
            batteryOk = false,
            engineOk = false
        )
        assertEquals(3, items.size)
        assertEquals(
            listOf(
                OnboardingHealthCheck.ID_NOTIFICATION,
                OnboardingHealthCheck.ID_BATTERY,
                OnboardingHealthCheck.ID_ENGINE
            ),
            items.map { it.id }
        )
        assertTrue(items.all { !it.ok && it.action.isNotBlank() })
        assertEquals(3, OnboardingHealthCheck.pendingCount(items))
    }

    @Test
    fun `全部完成时不再显示动作且待办为零`() {
        val items = OnboardingHealthCheck.items(
            notificationsOk = true,
            batteryOk = true,
            engineOk = true
        )
        assertTrue(items.all { it.ok })
        assertTrue(items.all { it.action.isBlank() })
        assertEquals(0, OnboardingHealthCheck.pendingCount(items))
    }

    @Test
    fun `部分完成只统计未完成项`() {
        val items = OnboardingHealthCheck.items(
            notificationsOk = true,
            batteryOk = false,
            engineOk = true
        )
        assertEquals(1, OnboardingHealthCheck.pendingCount(items))
        assertEquals("去设置", items.first { it.id == OnboardingHealthCheck.ID_BATTERY }.action)
    }

    @Test
    fun `只在蜂窝网络且未确认时需要弹确认`() {
        assertTrue(OnboardingHealthCheck.needsMobileConfirm(isCellular = true))
        assertFalse(OnboardingHealthCheck.needsMobileConfirm(isCellular = true, wifiOnlyConfirmed = true))
        assertFalse(OnboardingHealthCheck.needsMobileConfirm(isCellular = false))
        assertFalse(OnboardingHealthCheck.needsMobileConfirm(isCellular = false, wifiOnlyConfirmed = true))
    }
}
