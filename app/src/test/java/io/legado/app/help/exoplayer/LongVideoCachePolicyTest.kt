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

    @Test
    fun `缓存写决策日志_变化必打_稳定态抽样`() {
        // 决策变化（长视频 on→off）必须打，否则关键事件在低分片数场景不可见
        assertTrue(shouldLogCacheWriteDecision(changed = true, count = 88))
        // 起播前 30 次必打（覆盖「请求是否逐个创建 sink」的诊断需求）
        assertTrue(shouldLogCacheWriteDecision(changed = false, count = 1))
        assertTrue(shouldLogCacheWriteDecision(changed = false, count = 30))
        // 稳定态：第 31 次不打，第 40 次抽样打
        assertFalse(shouldLogCacheWriteDecision(changed = false, count = 31))
        assertTrue(shouldLogCacheWriteDecision(changed = false, count = 40))
        assertFalse(shouldLogCacheWriteDecision(changed = false, count = 41))
    }
}
