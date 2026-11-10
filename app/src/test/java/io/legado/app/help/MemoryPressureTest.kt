package io.legado.app.help

import android.content.ComponentCallbacks2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryPressureTest {

    private var fakeNow = 1_000_000L

    @Before
    fun setUp() {
        MemoryPressure.availableMemoryProvider = { 4L * 1024 * 1024 }
        MemoryPressure.currentTimeProvider = { fakeNow }
        MemoryPressure.maxMemoryProvider = { 256L * 1024 * 1024 }
        MemoryPressure.resetForTest()
        MemoryPressure.resetLevelForTest()
    }

    @After
    fun tearDown() {
        MemoryPressure.availableMemoryProvider = null
        MemoryPressure.currentTimeProvider = null
        MemoryPressure.maxMemoryProvider = null
        MemoryPressure.resetForTest()
    }

    @Test
    fun shouldTrimNow_lowAvailable_isTrue() {
        assertTrue(MemoryPressure.shouldTrimNow())
    }

    @Test
    fun shouldTrimNow_highAvailable_isFalse() {
        MemoryPressure.availableMemoryProvider = { 512L * 1024 * 1024 }
        assertFalse(MemoryPressure.shouldTrimNow())
    }

    @Test
    fun trimLevel_lowMemory_isCritical() {
        MemoryPressure.availableMemoryProvider = { 4L * 1024 * 1024 }
        assertEquals(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            MemoryPressure.trimLevelForCurrentState()
        )
    }

    @Test
    fun throttleTrim_executes_whenNeeded() {
        var executed = 0
        MemoryPressure.throttleTrim { executed++ }
        assertEquals(1, executed)
    }

    @Test
    fun throttleTrim_skips_withinInterval() {
        var executed = 0
        MemoryPressure.throttleTrim { executed++ }
        MemoryPressure.throttleTrim { executed++ }
        assertEquals(1, executed)
    }

    @Test
    fun throttleTrim_executes_afterInterval() {
        var executed = 0
        MemoryPressure.throttleTrim { executed++ }
        fakeNow = 1_002_000L
        MemoryPressure.throttleTrim { executed++ }
        assertEquals(2, executed)
    }

    @Test
    fun throttleTrim_skips_whenMemoryAvailable() {
        MemoryPressure.availableMemoryProvider = { 512L * 1024 * 1024 }
        var executed = 0
        MemoryPressure.throttleTrim { executed++ }
        assertEquals(0, executed)
    }

    // ---------------- crash-hardening AD-01 / AD-03 / AD-09 ----------------

    private val mb = 1024L * 1024
    private val heap256 = 256L * mb

    @Test
    fun levelOf_256mHeap_boundaries() {
        // MODERATE 阈值 = max(25% * 256MB, 24MB) = 64MB；LOW = 38.4MB；CRITICAL = 20.48MB
        assertEquals(MemoryPressure.PressureLevel.NORMAL, MemoryPressure.levelOf(100 * mb, heap256))
        assertEquals(MemoryPressure.PressureLevel.MODERATE, MemoryPressure.levelOf(60 * mb, heap256))
        assertEquals(MemoryPressure.PressureLevel.LOW, MemoryPressure.levelOf(30 * mb, heap256))
        assertEquals(MemoryPressure.PressureLevel.CRITICAL, MemoryPressure.levelOf(10 * mb, heap256))
    }

    @Test
    fun levelOf_smallHeap_usesAbsoluteFloor() {
        // 128MB 堆：25% = 32MB > 24MB 下限 ⇒ MODERATE 阈值 32MB（下限只在极小堆生效）
        val heap128 = 128L * mb
        assertEquals(MemoryPressure.PressureLevel.MODERATE, MemoryPressure.levelOf(30 * mb, heap128))
        assertEquals(MemoryPressure.PressureLevel.NORMAL, MemoryPressure.levelOf(40 * mb, heap128))
        // 比例阈值低于下限时以下限为准：64MB 堆 25% = 16MB < 24MB ⇒ 阈值 24MB
        val heap64 = 64L * mb
        assertEquals(MemoryPressure.PressureLevel.NORMAL, MemoryPressure.levelOf(25 * mb, heap64))
        assertEquals(MemoryPressure.PressureLevel.MODERATE, MemoryPressure.levelOf(23 * mb, heap64))
    }

    @Test
    fun refreshLevel_degradesImmediately_andRecoversAfterSustain() {
        MemoryPressure.availableMemoryProvider = { 30 * mb }
        assertEquals(MemoryPressure.PressureLevel.LOW, MemoryPressure.refreshLevel())
        // 内存转好：30s 内不降档（迟滞）
        MemoryPressure.availableMemoryProvider = { 200 * mb }
        assertEquals(MemoryPressure.PressureLevel.LOW, MemoryPressure.refreshLevel())
        fakeNow += 29_000L
        assertEquals(MemoryPressure.PressureLevel.LOW, MemoryPressure.refreshLevel())
        // 持续满 30s 才降回
        fakeNow += 2_000L
        assertEquals(MemoryPressure.PressureLevel.NORMAL, MemoryPressure.refreshLevel())
    }

    @Test
    fun throttleState_disabled_keepsCapabilitiesNormal() {
        val state = MemoryPressure.throttleState(MemoryPressure.PressureLevel.CRITICAL, enabled = false)
        assertEquals(MemoryPressure.PressureLevel.CRITICAL, state.level)
        assertEquals(1f, state.imageDecodeScale, 0f)
        assertTrue(state.prefetchAllowed)
        assertTrue(state.animationAllowed)
    }

    @Test
    fun throttleState_byLevel_mapsExpectedCapabilities() {
        val low = MemoryPressure.throttleState(MemoryPressure.PressureLevel.LOW, enabled = true)
        assertEquals(0.75f, low.imageDecodeScale, 0f)
        assertFalse(low.prefetchAllowed)
        assertTrue(low.animationAllowed)

        val critical = MemoryPressure.throttleState(MemoryPressure.PressureLevel.CRITICAL, enabled = true)
        assertEquals(0.5f, critical.imageDecodeScale, 0f)
        assertFalse(critical.prefetchAllowed)
        assertFalse(critical.animationAllowed)

        val moderate = MemoryPressure.throttleState(MemoryPressure.PressureLevel.MODERATE, enabled = true)
        assertEquals(1f, moderate.imageDecodeScale, 0f)
        assertTrue(moderate.prefetchAllowed)
    }

    @Test
    fun allowActions_respectsCooldown() {
        MemoryPressure.availableMemoryProvider = { 60 * mb }
        MemoryPressure.refreshLevel()
        assertTrue(MemoryPressure.allowActions())
        assertFalse(MemoryPressure.allowActions())
        fakeNow += 60_001L
        assertTrue(MemoryPressure.allowActions())
    }
}
