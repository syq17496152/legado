package io.legado.app.help.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * crash-hardening AD-07：推理进程崩溃熔断。
 *
 * 覆盖：单次死亡不熔断 / 窗口内两次熔断 / 冷却到期自动恢复 / 窗口外旧记录被清理 / 手动重试解除。
 */
class LlmCircuitBreakerTest {

    private val t0 = 1_000_000L
    private val minute = 60_000L

    @Before
    fun setUp() {
        LlmCircuitBreaker.resetForTest()
    }

    @Test
    fun singleDeath_doesNotTrip() {
        LlmCircuitBreaker.recordDeath(t0)
        assertFalse(LlmCircuitBreaker.isCoolingDown(t0))
        assertEquals(1, LlmCircuitBreaker.deathCount(t0))
    }

    @Test
    fun twoDeathsWithinWindow_tripsAndCoolsDown() {
        LlmCircuitBreaker.recordDeath(t0)
        LlmCircuitBreaker.recordDeath(t0 + minute)
        assertTrue(LlmCircuitBreaker.isCoolingDown(t0 + minute))
        assertEquals(2, LlmCircuitBreaker.deathCount(t0 + minute))
    }

    @Test
    fun cooldownExpiresAfter30Minutes() {
        LlmCircuitBreaker.recordDeath(t0)
        LlmCircuitBreaker.recordDeath(t0 + 1)
        assertTrue(LlmCircuitBreaker.isCoolingDown(t0 + 29 * minute))
        assertFalse(LlmCircuitBreaker.isCoolingDown(t0 + 31 * minute))
    }

    @Test
    fun deathsOutsideWindow_areDropped() {
        LlmCircuitBreaker.recordDeath(t0)
        // 11 分钟后再死一次：窗口内只剩 1 次，不熔断
        LlmCircuitBreaker.recordDeath(t0 + 11 * minute)
        assertFalse(LlmCircuitBreaker.isCoolingDown(t0 + 11 * minute))
        assertEquals(1, LlmCircuitBreaker.deathCount(t0 + 11 * minute))
    }

    @Test
    fun seedDeaths_canTripOnStartup() {
        LlmCircuitBreaker.seedDeaths(2, t0)
        assertTrue(LlmCircuitBreaker.isCoolingDown(t0))
        assertEquals(2, LlmCircuitBreaker.deathCount(t0))
    }

    @Test
    fun manualRetry_clearsCooldown() {
        LlmCircuitBreaker.seedDeaths(2, t0)
        assertTrue(LlmCircuitBreaker.isCoolingDown(t0))
        LlmCircuitBreaker.allowManualRetry()
        assertFalse(LlmCircuitBreaker.isCoolingDown(t0))
        assertEquals(0, LlmCircuitBreaker.deathCount(t0))
    }
}
