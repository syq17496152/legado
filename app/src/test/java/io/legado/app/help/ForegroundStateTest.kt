package io.legado.app.help

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 进程前后台状态（review P0-1）。
 */
class ForegroundStateTest {

    @Before
    fun setUp() {
        ForegroundState.resetForTest(value = true)
    }

    @Test
    fun `全部Activity停止后判后台`() {
        ForegroundState.onActivityStarted()
        assertTrue(ForegroundState.isForeground())
        ForegroundState.onActivityStopped()
        // reset 时初始计数为 1，这里再启一个再停一个
        ForegroundState.onActivityStarted()
        ForegroundState.onActivityStopped()
        assertTrue(ForegroundState.isForeground())
    }

    @Test
    fun `无Activity启动时判后台`() {
        ForegroundState.resetForTest(value = false)
        assertFalse(ForegroundState.isForeground())
        ForegroundState.onActivityStarted()
        assertTrue(ForegroundState.isForeground())
        ForegroundState.onActivityStopped()
        assertFalse(ForegroundState.isForeground())
    }

    @Test
    fun `多Activity计数不为负`() {
        ForegroundState.resetForTest(value = false)
        ForegroundState.onActivityStopped()
        ForegroundState.onActivityStopped()
        assertFalse(ForegroundState.isForeground())
        ForegroundState.onActivityStarted()
        assertTrue(ForegroundState.isForeground())
    }
}
