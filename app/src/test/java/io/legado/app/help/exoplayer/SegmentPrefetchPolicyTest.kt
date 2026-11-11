package io.legado.app.help.exoplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分片预取策略（基线 design §1.3/§1.12/§1.15 + 本期 R32~R43）。
 */
class SegmentPrefetchPolicyTest {

    private fun segments(count: Int, durationMs: Long = 6_000L): List<SegmentPrefetchPolicy.Segment> =
        (0 until count).map { SegmentPrefetchPolicy.Segment(durationMs, "seg$it.ts") }

    private fun input(
        segments: List<SegmentPrefetchPolicy.Segment> = segments(20),
        currentPositionMs: Long = 0L,
        bufferedPositionMs: Long = 0L,
        durationMs: Long = 30 * 60_000L,
        speed: Float = 1f,
        isLive: Boolean = false,
        hasEndList: Boolean = true,
        hasByteRange: Boolean = false,
        hasDrm: Boolean = false,
        isMetered: Boolean = false,
        videoCacheEnabled: Boolean = true,
        prefetchEnabled: Boolean = true,
        buffering: Boolean = false,
        paused: Boolean = false,
        foreground: Boolean = true,
        cacheSizeBytes: Long = 100L * 1024 * 1024,
        bitrateBps: Long = 8_000_000L,
        bandwidthEstimateBps: Long = 10_000_000L,
        bucketDepthMs: Long = 0L,
        episodeWrittenBytes: Long = 0L,
        hasMeasuredSample: Boolean = false,
        previousTier: SegmentPrefetchPolicy.NetworkTier? = null,
        msSinceTierSwitch: Long = 0L,
        allowFullEpisode: Boolean = true,
    ) = SegmentPrefetchPolicy.Input(
        segments = segments,
        currentPositionMs = currentPositionMs,
        bufferedPositionMs = bufferedPositionMs,
        durationMs = durationMs,
        speed = speed,
        isLive = isLive,
        hasEndList = hasEndList,
        hasByteRange = hasByteRange,
        hasDrm = hasDrm,
        isMetered = isMetered,
        videoCacheEnabled = videoCacheEnabled,
        prefetchEnabled = prefetchEnabled,
        buffering = buffering,
        paused = paused,
        foreground = foreground,
        cacheSizeBytes = cacheSizeBytes,
        bitrateBps = bitrateBps,
        bandwidthEstimateBps = bandwidthEstimateBps,
        bucketDepthMs = bucketDepthMs,
        episodeWrittenBytes = episodeWrittenBytes,
        hasMeasuredSample = hasMeasuredSample,
        previousTier = previousTier,
        msSinceTierSwitch = msSinceTierSwitch,
        allowFullEpisode = allowFullEpisode,
    )

    // ===================== 门禁 =====================

    @Test
    fun `门禁-直播禁预取`() {
        assertNotNull(SegmentPrefetchPolicy.gate(input(isLive = true)))
    }

    @Test
    fun `门禁-计量网络与缓存关闭`() {
        assertEquals("metered", SegmentPrefetchPolicy.gate(input(isMetered = true))?.reason)
        assertEquals(
            "video_cache_off",
            SegmentPrefetchPolicy.gate(input(videoCacheEnabled = false))?.reason,
        )
    }

    @Test
    fun `门禁-清单形态`() {
        assertEquals("no_endlist", SegmentPrefetchPolicy.gate(input(hasEndList = false))?.reason)
        assertEquals("byterange", SegmentPrefetchPolicy.gate(input(hasByteRange = true))?.reason)
        assertEquals("drm", SegmentPrefetchPolicy.gate(input(hasDrm = true))?.reason)
        assertEquals("no_duration", SegmentPrefetchPolicy.gate(input(durationMs = 0L))?.reason)
    }

    @Test
    fun `门禁-暂停与后台`() {
        assertEquals("paused", SegmentPrefetchPolicy.gate(input(paused = true))?.reason)
        assertEquals("background", SegmentPrefetchPolicy.gate(input(foreground = false))?.reason)
    }

    @Test
    fun `门禁-正常放行`() {
        assertNull(SegmentPrefetchPolicy.gate(input()))
    }

    // ===================== 档位映射（R32） =====================

    @Test
    fun `档位映射五档边界`() {
        assertEquals(SegmentPrefetchPolicy.NetworkTier.WEAK, SegmentPrefetchPolicy.tierOf(1_999_999L))
        assertEquals(SegmentPrefetchPolicy.NetworkTier.STANDARD, SegmentPrefetchPolicy.tierOf(2_000_000L))
        assertEquals(SegmentPrefetchPolicy.NetworkTier.STANDARD, SegmentPrefetchPolicy.tierOf(19_999_999L))
        assertEquals(SegmentPrefetchPolicy.NetworkTier.FAST, SegmentPrefetchPolicy.tierOf(20_000_000L))
        assertEquals(SegmentPrefetchPolicy.NetworkTier.FAST, SegmentPrefetchPolicy.tierOf(49_999_999L))
        assertEquals(SegmentPrefetchPolicy.NetworkTier.ULTRA, SegmentPrefetchPolicy.tierOf(50_000_000L))
        assertEquals(SegmentPrefetchPolicy.NetworkTier.ULTRA, SegmentPrefetchPolicy.tierOf(99_999_999L))
        assertEquals(SegmentPrefetchPolicy.NetworkTier.EXTREME, SegmentPrefetchPolicy.tierOf(100_000_000L))
    }

    @Test
    fun `档位表数值与设计一致`() {
        assertEquals(30_000L, SegmentPrefetchPolicy.NetworkTier.WEAK.windowMs)
        assertEquals(60_000L, SegmentPrefetchPolicy.NetworkTier.STANDARD.windowMs)
        assertEquals(180_000L, SegmentPrefetchPolicy.NetworkTier.FAST.windowMs)
        assertEquals(300_000L, SegmentPrefetchPolicy.NetworkTier.ULTRA.windowMs)
        assertEquals(600_000L, SegmentPrefetchPolicy.NetworkTier.EXTREME.windowMs)
        assertEquals(1, SegmentPrefetchPolicy.NetworkTier.WEAK.concurrency)
        assertEquals(2, SegmentPrefetchPolicy.NetworkTier.STANDARD.concurrency)
        assertEquals(3, SegmentPrefetchPolicy.NetworkTier.EXTREME.concurrency)
    }

    @Test
    fun `自调档-无实测样本不上浮高速档`() {
        val tier = SegmentPrefetchPolicy.resolveTier(
            input(bandwidthEstimateBps = 120_000_000L, hasMeasuredSample = false)
        )
        assertEquals(SegmentPrefetchPolicy.NetworkTier.STANDARD, tier)
    }

    @Test
    fun `自调档-有样本且满30秒才升档`() {
        val blocked = SegmentPrefetchPolicy.resolveTier(
            input(
                bandwidthEstimateBps = 120_000_000L,
                hasMeasuredSample = true,
                previousTier = SegmentPrefetchPolicy.NetworkTier.FAST,
                msSinceTierSwitch = 10_000L,
            )
        )
        assertEquals(SegmentPrefetchPolicy.NetworkTier.FAST, blocked)
        val allowed = SegmentPrefetchPolicy.resolveTier(
            input(
                bandwidthEstimateBps = 120_000_000L,
                hasMeasuredSample = true,
                previousTier = SegmentPrefetchPolicy.NetworkTier.FAST,
                msSinceTierSwitch = 31_000L,
            )
        )
        assertEquals(SegmentPrefetchPolicy.NetworkTier.EXTREME, allowed)
    }

    @Test
    fun `自调档-降档立即生效`() {
        val tier = SegmentPrefetchPolicy.resolveTier(
            input(
                bandwidthEstimateBps = 1_000_000L,
                previousTier = SegmentPrefetchPolicy.NetworkTier.EXTREME,
                msSinceTierSwitch = 0L,
            )
        )
        assertEquals(SegmentPrefetchPolicy.NetworkTier.WEAK, tier)
    }

    // ===================== 窗口（R32/R39） =====================

    @Test
    fun `窗口-长视频普通网取60秒`() {
        val window = SegmentPrefetchPolicy.selectWindow(
            input(cacheSizeBytes = 4096L * 1024 * 1024)
        )!!
        // 12s（跳过前 2 片）到 72s：片 2..11
        assertEquals(2, window.startIndex)
        assertEquals(12, window.endIndexExclusive)
        assertEquals(10, window.count)
        assertTrue(window.maxBytes > 0)
        assertEquals(SegmentPrefetchPolicy.PrefetchState.RUNNING, window.state)
    }

    @Test
    fun `窗口-短视频整集且受字节上限约束`() {
        val window = SegmentPrefetchPolicy.selectWindow(
            input(
                durationMs = 5 * 60_000L,
                cacheSizeBytes = 4096L * 1024 * 1024,
            )
        )!!
        assertEquals(2, window.startIndex)
        assertEquals(20, window.endIndexExclusive)
        assertEquals(18, window.count)
        // 108s × 8Mbps / 8 × 1.3 = 140,400,000
        assertEquals(140_400_000L, window.maxBytes)
    }

    @Test
    fun `窗口-高速网络整集剩余（不受600秒卡住）`() {
        val window = SegmentPrefetchPolicy.selectWindow(
            input(
                segments = segments(40),
                bandwidthEstimateBps = 30_000_000L,
                cacheSizeBytes = 4096L * 1024 * 1024,
                hasMeasuredSample = true,
            )
        )!!
        assertEquals(2, window.startIndex)
        assertEquals(40, window.endIndexExclusive)
        assertEquals(38, window.count)
    }

    @Test
    fun `窗口-倍速放大`() {
        val normal = SegmentPrefetchPolicy.selectWindow(input())!!
        val double = SegmentPrefetchPolicy.selectWindow(input(speed = 2f))!!
        assertTrue(double.count >= normal.count)
    }

    @Test
    fun `窗口-字节上限受缓存容量钳制`() {
        val window = SegmentPrefetchPolicy.selectWindow(
            input(cacheSizeBytes = 10L * 1024 * 1024)
        )!!
        // 10MiB × 25%（STANDARD）
        assertEquals(2_621_440L, window.maxBytes)
    }

    @Test
    fun `窗口-缓冲头已到末尾返回空`() {
        val window = SegmentPrefetchPolicy.selectWindow(input(bufferedPositionMs = 200_000L))
        assertNull(window)
    }

    @Test
    fun `窗口-无分片或时长为0返回空`() {
        assertNull(SegmentPrefetchPolicy.selectWindow(input(segments = emptyList())))
        assertNull(
            SegmentPrefetchPolicy.selectWindow(
                input(segments = listOf(SegmentPrefetchPolicy.Segment(0L, "a.ts")))
            )
        )
    }

    @Test
    fun `窗口-码率未知时字节有限`() {
        val window = SegmentPrefetchPolicy.selectWindow(input(bitrateBps = 0L))!!
        assertTrue(window.maxBytes > 0L)
        assertTrue(window.maxBytes < Long.MAX_VALUE / 2)
    }

    // ===================== 完成度与整集（R33/R43） =====================

    @Test
    fun `完成度-剩余不足阈值判COMPLETE`() {
        val state = SegmentPrefetchPolicy.stateOf(
            input(
                segments = segments(200),
                bufferedPositionMs = 1_185_000L,
            )
        )
        assertEquals(SegmentPrefetchPolicy.PrefetchState.COMPLETE, state)
    }

    @Test
    fun `完成度-尾部不足一窗判TAIL`() {
        val state = SegmentPrefetchPolicy.stateOf(
            input(
                segments = segments(200),
                bufferedPositionMs = 1_080_000L,
                bandwidthEstimateBps = 30_000_000L,
                allowFullEpisode = false,
            ),
            SegmentPrefetchPolicy.NetworkTier.FAST,
        )
        assertEquals(SegmentPrefetchPolicy.PrefetchState.TAIL, state)
    }

    @Test
    fun `整集-30分钟可装下判FULL_EPISODE`() {
        val state = SegmentPrefetchPolicy.stateOf(
            input(
                segments = segments(300),
                durationMs = 30 * 60_000L,
                cacheSizeBytes = 4096L * 1024 * 1024,
                bandwidthEstimateBps = 120_000_000L,
                hasMeasuredSample = true,
            )
        )
        assertEquals(SegmentPrefetchPolicy.PrefetchState.FULL_EPISODE, state)
    }

    @Test
    fun `整集-2小时装不下判FILL_TO_CAPACITY`() {
        val state = SegmentPrefetchPolicy.stateOf(
            input(
                segments = segments(1200),
                durationMs = 120 * 60_000L,
                cacheSizeBytes = 512L * 1024 * 1024,
                bandwidthEstimateBps = 120_000_000L,
                hasMeasuredSample = true,
            )
        )
        assertEquals(SegmentPrefetchPolicy.PrefetchState.FILL_TO_CAPACITY, state)
    }

    @Test
    fun `整集-闸门关闭时退回窗口`() {
        val state = SegmentPrefetchPolicy.stateOf(
            input(
                segments = segments(300),
                durationMs = 30 * 60_000L,
                cacheSizeBytes = 4096L * 1024 * 1024,
                bandwidthEstimateBps = 120_000_000L,
                hasMeasuredSample = true,
                allowFullEpisode = false,
            )
        )
        assertTrue(
            state == SegmentPrefetchPolicy.PrefetchState.RUNNING ||
                state == SegmentPrefetchPolicy.PrefetchState.TAIL
        )
    }

    @Test
    fun `整集-码率未知退回窗口`() {
        val state = SegmentPrefetchPolicy.stateOf(
            input(
                segments = segments(300),
                durationMs = 30 * 60_000L,
                cacheSizeBytes = 4096L * 1024 * 1024,
                bandwidthEstimateBps = 120_000_000L,
                hasMeasuredSample = true,
                bitrateBps = 0L,
            )
        )
        assertTrue(
            state == SegmentPrefetchPolicy.PrefetchState.RUNNING ||
                state == SegmentPrefetchPolicy.PrefetchState.TAIL
        )
    }

    // ===================== 磁盘池分区（R41/AD-29） =====================

    @Test
    fun `池分区-NEAR随桶深变化`() {
        val window = SegmentPrefetchPolicy.selectWindow(
            input(
                segments = segments(300),
                durationMs = 30 * 60_000L,
                cacheSizeBytes = 4096L * 1024 * 1024,
                bandwidthEstimateBps = 120_000_000L,
                hasMeasuredSample = true,
                bufferedPositionMs = 60_000L,
                bucketDepthMs = 120_000L,
            )
        )!!
        val regions = SegmentPrefetchPolicy.poolRegions(
            input(
                segments = segments(300),
                durationMs = 30 * 60_000L,
                cacheSizeBytes = 4096L * 1024 * 1024,
                bandwidthEstimateBps = 120_000_000L,
                hasMeasuredSample = true,
                bufferedPositionMs = 60_000L,
                bucketDepthMs = 120_000L,
            ),
            window,
        )
        assertEquals(180_000L, regions.nearEndMs)
        assertEquals(window.endMs, regions.farEndMs)
        assertTrue(regions.nearHasGap)
    }

    // ===================== 预算（R32） =====================

    @Test
    fun `预算-普通网比例与上限`() {
        val running = SegmentPrefetchPolicy.budget(
            input(bufferedPositionMs = 30_000L, currentPositionMs = 0L, bandwidthEstimateBps = 10_000_000L)
        )
        assertEquals(SegmentPrefetchPolicy.BudgetState.RUNNING, running.state)
        assertEquals(3_000_000L, running.bytesPerSec)
    }

    @Test
    fun `预算-五档上限`() {
        val weak = SegmentPrefetchPolicy.budget(
            input(bufferedPositionMs = 30_000L, bandwidthEstimateBps = 1_000_000L),
            SegmentPrefetchPolicy.NetworkTier.WEAK,
        )
        assertEquals((1_000_000L * 0.20).toLong(), weak.bytesPerSec)
        val fast = SegmentPrefetchPolicy.budget(
            input(bufferedPositionMs = 30_000L, bandwidthEstimateBps = 30_000_000L),
            SegmentPrefetchPolicy.NetworkTier.FAST,
        )
        assertEquals(SegmentPrefetchPolicy.NetworkTier.FAST.maxBudgetBps, fast.bytesPerSec)
        val ultra = SegmentPrefetchPolicy.budget(
            input(bufferedPositionMs = 30_000_000L, bandwidthEstimateBps = 100_000_000L),
            SegmentPrefetchPolicy.NetworkTier.EXTREME,
        )
        assertEquals(SegmentPrefetchPolicy.NetworkTier.EXTREME.maxBudgetBps, ultra.bytesPerSec)
    }

    @Test
    fun `预算-低水位降速`() {
        val throttled = SegmentPrefetchPolicy.budget(
            input(bufferedPositionMs = 5_000L, bandwidthEstimateBps = 10_000_000L)
        )
        assertEquals(SegmentPrefetchPolicy.BudgetState.THROTTLED, throttled.state)
        assertEquals(2_000_000L, throttled.bytesPerSec)
    }

    @Test
    fun `预算-极低水位暂停`() {
        val paused = SegmentPrefetchPolicy.budget(input(bufferedPositionMs = 2_000L))
        assertEquals(SegmentPrefetchPolicy.BudgetState.PAUSED, paused.state)
        assertEquals(0L, paused.bytesPerSec)
    }

    @Test
    fun `预算-带宽缺失走保守值`() {
        val budget = SegmentPrefetchPolicy.budget(
            input(bufferedPositionMs = 30_000L, bandwidthEstimateBps = 0L)
        )
        assertEquals(SegmentPrefetchPolicy.MIN_BUDGET_BPS, budget.bytesPerSec)
    }

    // ===================== 回看与平均片长 =====================

    @Test
    fun `回看窗口按容量15%钳制`() {
        val sec = SegmentPrefetchPolicy.backCacheSeconds(
            configSec = 120,
            cacheSizeBytes = 100L * 1024 * 1024,
            bitrateBps = 8_000_000L,
        )
        // 100MiB × 0.15 × 8 / 8Mbps ≈ 15.7s
        assertTrue(sec in 1..120)
        assertTrue(sec <= 16)
        assertEquals(
            0,
            SegmentPrefetchPolicy.backCacheSeconds(0, 100L * 1024 * 1024, 8_000_000L),
        )
        assertEquals(
            0,
            SegmentPrefetchPolicy.backCacheSeconds(120, 100L * 1024 * 1024, 0L),
        )
    }

    @Test
    fun `平均时长忽略非法值`() {
        val list = listOf(
            SegmentPrefetchPolicy.Segment(4_000L, "a"),
            SegmentPrefetchPolicy.Segment(0L, "b"),
            SegmentPrefetchPolicy.Segment(8_000L, "c"),
        )
        assertEquals(6_000L, SegmentPrefetchPolicy.averageSegmentDurationMs(list))
        assertEquals(0L, SegmentPrefetchPolicy.averageSegmentDurationMs(emptyList()))
    }
}
