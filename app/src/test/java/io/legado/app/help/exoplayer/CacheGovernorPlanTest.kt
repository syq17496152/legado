package io.legado.app.help.exoplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨视频缓存治理计划（AD-33）。
 */
class CacheGovernorPlanTest {

    private fun entries(vararg pairs: Pair<String, Long>): List<CacheGovernor.Entry> =
        pairs.map { CacheGovernor.Entry(it.first, it.second) }

    @Test
    fun `空间足够时不清理`() {
        val plan = CacheGovernor.plan(
            entries = entries("a" to 100L),
            protectedKeys = emptySet(),
            needBytes = 0L,
        )
        assertTrue(plan.satisfied)
        assertTrue(plan.keysToRemove.isEmpty())
    }

    @Test
    fun `按需清理非保护key直到满足`() {
        val plan = CacheGovernor.plan(
            entries = entries("old1" to 100L, "old2" to 100L, "old3" to 100L),
            protectedKeys = emptySet(),
            needBytes = 150L,
        )
        assertTrue(plan.satisfied)
        assertEquals(listOf("old1", "old2"), plan.keysToRemove)
        assertEquals(200L, plan.freedBytes)
    }

    @Test
    fun `当前视频key永不清理`() {
        val plan = CacheGovernor.plan(
            entries = entries("current" to 1_000L, "old" to 100L),
            protectedKeys = setOf("current"),
            needBytes = 100L,
        )
        assertTrue(plan.satisfied)
        assertEquals(listOf("old"), plan.keysToRemove)
    }

    @Test
    fun `清理后仍不足标记未满足`() {
        val plan = CacheGovernor.plan(
            entries = entries("old" to 50L),
            protectedKeys = setOf("current"),
            needBytes = 100L,
        )
        assertFalse(plan.satisfied)
        assertEquals(listOf("old"), plan.keysToRemove)
    }
}
