package io.legado.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IF-04 迟到结束事件判定（起播时长口径）。
 *
 * 真实回归：单 URL 短视频播完点播放键 → 新播放 128ms 后被旧 AUTO_COMPLETE 判定 Stop（黑屏）。
 * 判据不依赖位置 API（非播放态可能返回 0，会误伤正常播完推进）。
 */
class CompletionGuardTest {

    @Test
    fun `正在播放时的结束事件视为陈旧`() {
        assertTrue(isStaleCompletion(sinceSetupMs = 60_000L, isPlaying = true))
    }

    @Test
    fun `起播不足阈值时的结束事件视为陈旧`() {
        assertTrue(isStaleCompletion(sinceSetupMs = 128L, isPlaying = false))
    }

    @Test
    fun `起播超过阈值且未在播则允许推进`() {
        assertFalse(isStaleCompletion(sinceSetupMs = 5_000L, isPlaying = false))
    }

    @Test
    fun `阈值边界按不小于处理`() {
        assertFalse(isStaleCompletion(sinceSetupMs = MIN_SETUP_AGE_MS, isPlaying = false))
        assertTrue(isStaleCompletion(sinceSetupMs = MIN_SETUP_AGE_MS - 1, isPlaying = false))
    }
}
