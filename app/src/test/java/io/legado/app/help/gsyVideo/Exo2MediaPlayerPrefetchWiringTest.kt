package io.legado.app.help.gsyVideo

import io.legado.app.help.exoplayer.BufferTierPolicy
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.exoplayer.LivePlaybackPolicy
import io.legado.app.help.exoplayer.SpeedBufferPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exo2MediaPlayer 预取/直播/倍速接线所依赖的策略契约。
 *
 * 放在本包（与 Exo2MediaPlayer 同目录）以守护「播放器接入的错误策略组合」：
 * - 直播不得写缓存（审计 B3）；
 * - 倍速需求带宽参与档位裁决（AD-13）；
 * - 卡顿信号才降档、恢复有滞回（AD-05）。
 */
class Exo2MediaPlayerPrefetchWiringTest {

    @Test
    fun `直播不写缓存优先于未知时长`() {
        assertFalse(LivePlaybackPolicy.shouldWriteCache(isLive = true, backCacheSec = 120, durationMs = 0L))
    }

    @Test
    fun `倍速需求带宽进入档位裁决`() {
        val decision = BufferTierPolicy.decide(
            currentForceTier = null,
            bandwidthEstimateBps = 10_000_000L,
            trackBitrateBps = 4_000_000L,
            speed = 3f,
            rebufferCount = 0,
            msSinceLastSwitch = 100_000L,
        )
        assertEquals(ExoPlayerHelper.BandwidthTier.MEDIUM, decision.forceTier)
        assertEquals("demand", decision.reason)
    }

    @Test
    fun `好网无卡顿不降档`() {
        val decision = BufferTierPolicy.decide(
            currentForceTier = null,
            bandwidthEstimateBps = 10_000_000L,
            trackBitrateBps = 2_000_000L,
            speed = 1f,
            rebufferCount = 0,
            msSinceLastSwitch = 100_000L,
        )
        assertNull(decision.forceTier)
    }

    @Test
    fun `倍速窗口放大`() {
        assertEquals(120_000L, SpeedBufferPolicy.scaledWindowMs(60_000L, 2f))
        assertTrue(SpeedBufferPolicy.scaledMaxBufferMs(120_000, 2f) > 120_000)
    }

    @Test
    fun `整集闸门关闭时退回窗口预取`() {
        val input = io.legado.app.help.exoplayer.SegmentPrefetchPolicy.Input(
            segments = (0 until 300).map {
                io.legado.app.help.exoplayer.SegmentPrefetchPolicy.Segment(6_000L, "s$it")
            },
            currentPositionMs = 0L,
            bufferedPositionMs = 0L,
            durationMs = 30 * 60_000L,
            speed = 1f,
            isLive = false,
            hasEndList = true,
            hasByteRange = false,
            hasDrm = false,
            isMetered = false,
            videoCacheEnabled = true,
            prefetchEnabled = true,
            buffering = false,
            paused = false,
            foreground = true,
            cacheSizeBytes = 4096L * 1024 * 1024,
            bitrateBps = 8_000_000L,
            bandwidthEstimateBps = 120_000_000L,
            hasMeasuredSample = true,
            allowFullEpisode = false,
        )
        val state = io.legado.app.help.exoplayer.SegmentPrefetchPolicy.stateOf(
            input,
            io.legado.app.help.exoplayer.SegmentPrefetchPolicy.NetworkTier.EXTREME,
        )
        assertTrue(
            state == io.legado.app.help.exoplayer.SegmentPrefetchPolicy.PrefetchState.RUNNING ||
                state == io.legado.app.help.exoplayer.SegmentPrefetchPolicy.PrefetchState.TAIL
        )
    }

    @Test
    fun `后台时预取门禁为background`() {
        val reason = io.legado.app.help.exoplayer.SegmentPrefetchPolicy.gate(
            io.legado.app.help.exoplayer.SegmentPrefetchPolicy.Input(
                segments = listOf(io.legado.app.help.exoplayer.SegmentPrefetchPolicy.Segment(6_000L, "a")),
                currentPositionMs = 0L,
                bufferedPositionMs = 0L,
                durationMs = 60_000L,
                speed = 1f,
                isLive = false,
                hasEndList = true,
                hasByteRange = false,
                hasDrm = false,
                isMetered = false,
                videoCacheEnabled = true,
                prefetchEnabled = true,
                buffering = false,
                paused = false,
                foreground = false,
                cacheSizeBytes = 100L * 1024 * 1024,
                bitrateBps = 8_000_000L,
                bandwidthEstimateBps = 10_000_000L,
            )
        )
        assertEquals("background", reason?.reason)
    }
}
