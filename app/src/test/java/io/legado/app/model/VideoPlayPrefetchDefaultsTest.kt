package io.legado.app.model

import io.legado.app.help.exoplayer.SegmentPrefetchPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频预取/回看默认值契约（video-buffer-prefetch-v2）。
 *
 * 默认值变更会直接影响用户流量/存储，必须由测试锁定：
 * - 分片预取默认开启；
 * - 长视频回看窗口默认 120s；0 = 关闭（回基线）；
 * - 回看窗口受缓存容量钳制。
 */
class VideoPlayPrefetchDefaultsTest {

    @Test
    fun `预取默认开启`() {
        assertTrue(VideoPlay.DEFAULT_SEGMENT_PREFETCH_ENABLED)
    }

    @Test
    fun `回看窗口默认120秒`() {
        assertEquals(120, VideoPlay.DEFAULT_LONG_VIDEO_BACK_CACHE_SEC)
    }

    @Test
    fun `回看窗口0表示关闭`() {
        assertEquals(
            0,
            SegmentPrefetchPolicy.backCacheSeconds(
                configSec = 0,
                cacheSizeBytes = 100L * 1024 * 1024,
                bitrateBps = 8_000_000L,
            ),
        )
    }

    @Test
    fun `默认窗口在默认缓存容量下可用`() {
        val sec = SegmentPrefetchPolicy.backCacheSeconds(
            configSec = VideoPlay.DEFAULT_LONG_VIDEO_BACK_CACHE_SEC,
            cacheSizeBytes = 100L * 1024 * 1024,
            bitrateBps = 8_000_000L,
        )
        assertTrue(sec > 0)
        assertTrue(sec <= VideoPlay.DEFAULT_LONG_VIDEO_BACK_CACHE_SEC)
    }

    @Test
    fun `倒计时预热按当前档位取片数`() {
        assertEquals(
            20,
            io.legado.app.help.video.AdvanceCountdown.warmupPlan(
                SegmentPrefetchPolicy.NetworkTier.ULTRA, false, 100
            ).count
        )
        assertEquals(
            3,
            io.legado.app.help.video.AdvanceCountdown.warmupPlan(
                SegmentPrefetchPolicy.NetworkTier.WEAK, false, 100
            ).count
        )
    }

    @Test
    fun `好网大缓存下整集模式启用`() {
        val input = SegmentPrefetchPolicy.Input(
            segments = (0 until 300).map {
                SegmentPrefetchPolicy.Segment(6_000L, "s$it")
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
        )
        assertEquals(
            SegmentPrefetchPolicy.PrefetchState.FULL_EPISODE,
            SegmentPrefetchPolicy.stateOf(
                input,
                SegmentPrefetchPolicy.NetworkTier.EXTREME,
            )
        )
    }
}
