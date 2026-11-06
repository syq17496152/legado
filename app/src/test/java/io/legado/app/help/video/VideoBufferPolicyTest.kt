package io.legado.app.help.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓冲策略单测（design §1.14 B3/B10 / tasks 13.5、13.10）
 */
class VideoBufferPolicyTest {

    @Test
    fun `自动与均衡保持档位基准`() {
        val auto = VideoBufferPolicy.resolve(8_000, 90_000, 800, VideoBufferPolicy.STRATEGY_AUTO)
        val balanced = VideoBufferPolicy.resolve(8_000, 90_000, 800, VideoBufferPolicy.STRATEGY_BALANCED)
        assertEquals(8_000, auto.minBufferMs)
        assertEquals(90_000, auto.maxBufferMs)
        assertEquals(800, auto.bufferForPlaybackMs)
        assertEquals(auto, balanced)
    }

    @Test
    fun `激进 放大maxBuffer 并降低起播阈值`() {
        val p = VideoBufferPolicy.resolve(8_000, 120_000, 800, VideoBufferPolicy.STRATEGY_AGGRESSIVE)
        assertEquals(180_000, p.maxBufferMs)
        assertEquals(500, p.bufferForPlaybackMs)
        assertEquals(8_000, p.minBufferMs)
    }

    @Test
    fun `保守 缩小maxBuffer 并抬高起播阈值`() {
        val p = VideoBufferPolicy.resolve(8_000, 120_000, 500, VideoBufferPolicy.STRATEGY_CONSERVATIVE)
        assertEquals(72_000, p.maxBufferMs)
        assertEquals(1_500, p.bufferForPlaybackMs)
        assertEquals(3_000, p.bufferForPlaybackAfterRebufferMs)
    }

    @Test
    fun `用户显式秒数优先于档位基准`() {
        val p = VideoBufferPolicy.resolve(8_000, 30_000, 500, VideoBufferPolicy.STRATEGY_AUTO, userMaxBufferSec = 60)
        assertEquals(60_000, p.maxBufferMs)
    }

    @Test
    fun `maxBuffer有上下限 且不小于两倍minBuffer`() {
        val big = VideoBufferPolicy.resolve(8_000, 120_000, 500, VideoBufferPolicy.STRATEGY_AGGRESSIVE, userMaxBufferSec = 600)
        assertEquals(300_000, big.maxBufferMs)
        val small = VideoBufferPolicy.resolve(10_000, 30_000, 500, VideoBufferPolicy.STRATEGY_CONSERVATIVE)
        assertEquals(20_000, small.maxBufferMs)
    }

    @Test
    fun `字节上限 按秒数估算`() {
        // 120s × 1MB/s × 1.3 = 156MB
        assertEquals(156_000_000, VideoBufferPolicy.targetBufferBytes(120_000))
        // 30s → 39MB
        assertEquals(39_000_000, VideoBufferPolicy.targetBufferBytes(30_000))
    }

    @Test
    fun `字节上限 封顶与下限`() {
        assertEquals(
            VideoBufferPolicy.MAX_TARGET_BUFFER_BYTES,
            VideoBufferPolicy.targetBufferBytes(600_000)
        )
        assertEquals(16 * 1024 * 1024, VideoBufferPolicy.targetBufferBytes(1_000))
        assertTrue(VideoBufferPolicy.targetBufferBytes(300_000) <= VideoBufferPolicy.MAX_TARGET_BUFFER_BYTES)
    }
}
