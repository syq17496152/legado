package io.legado.app.help.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.5 重连退避（AD-11）：`:llm` 崩溃后最多重试 3 次，退避梯度 1s/2s/4s。
 * 上限必须锁死——无上限重连会反复拉起 native 进程，把低端设备拖到更糟。
 */
class LlmRetryPolicyTest {

    @Test
    fun `前三次重试按 1s 2s 4s 退避`() {
        assertEquals(1_000L, LlmRetryPolicy.next(0).delayMs)
        assertEquals(2_000L, LlmRetryPolicy.next(1).delayMs)
        assertEquals(4_000L, LlmRetryPolicy.next(2).delayMs)
    }

    @Test
    fun `attempt 从 1 递增且三次内允许`() {
        assertTrue(LlmRetryPolicy.next(0).allowed)
        assertTrue(LlmRetryPolicy.next(1).allowed)
        assertTrue(LlmRetryPolicy.next(2).allowed)
        assertEquals(1, LlmRetryPolicy.next(0).attempt)
        assertEquals(3, LlmRetryPolicy.next(2).attempt)
    }

    @Test
    fun `额度用尽后不再允许且不给延迟`() {
        val decision = LlmRetryPolicy.next(LlmRetryPolicy.MAX_ATTEMPTS)
        assertFalse(decision.allowed)
        assertEquals(0L, decision.delayMs)
        assertTrue(LlmRetryPolicy.exhausted(LlmRetryPolicy.MAX_ATTEMPTS))
        assertFalse(LlmRetryPolicy.exhausted(0))
    }

    @Test
    fun `负数已用次数按 0 处理`() {
        assertEquals(1, LlmRetryPolicy.next(-1).attempt)
        assertTrue(LlmRetryPolicy.next(-1).allowed)
    }
}
