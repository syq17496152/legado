package io.legado.app.help.exoplayer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §13.7 预取限速曲线（纯函数锁定）。
 *
 * 目标：后台预取超前目标速率时必须等待，且单次等待有上限（保证会话结束能及时取消）。
 */
class PrefillThrottleTest {

    @Test
    fun `未超前目标速率时无需等待`() {
        // 1MB / 1.5MBps = 666ms 应耗时；实际已耗 1000ms ⇒ 不超前
        assertEquals(0L, PrefillThrottle.waitMs(1_000_000L, 1_000L, 1_500_000L))
    }

    @Test
    fun `超前时按差额等待`() {
        // 10KB / 100KBps = 100ms 应耗时；实际 50ms ⇒ 需再等 50ms
        assertEquals(50L, PrefillThrottle.waitMs(10_000L, 50L, 100_000L))
    }

    @Test
    fun `单次等待不超过上限200ms`() {
        // 10KB / 1KBps = 10000ms 应耗时；实际 0ms ⇒ 差额 10000ms，截断为 200ms
        assertEquals(200L, PrefillThrottle.waitMs(10_000L, 0L, 1_000L))
    }

    @Test
    fun `不限速或零字节时不等待`() {
        assertEquals(0L, PrefillThrottle.waitMs(10_000L, 0L, 0L))
        assertEquals(0L, PrefillThrottle.waitMs(0L, 0L, 1_500_000L))
    }
}
