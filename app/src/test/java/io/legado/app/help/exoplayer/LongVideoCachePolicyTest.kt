package io.legado.app.help.exoplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tasks §13.3：长视频缓存分流矩阵（长视频不写、短视频照写、未知按短视频）。
 */
class LongVideoCachePolicyTest {

    @Test
    fun `长视频不边播边写`() {
        assertFalse(shouldWriteCache(durationMs = 40 * 60 * 1000L))
        assertFalse(shouldWriteCache(LONG_VIDEO_CACHE_THRESHOLD_MS))
    }

    @Test
    fun `短视频保持边下边存`() {
        assertTrue(shouldWriteCache(durationMs = 5_000L))
        assertTrue(shouldWriteCache(durationMs = 20 * 60 * 1000L - 1))
    }

    @Test
    fun `时长未知按短视频处理`() {
        assertTrue(shouldWriteCache(durationMs = 0L))
        assertTrue(shouldWriteCache(durationMs = -1L))
    }
}
