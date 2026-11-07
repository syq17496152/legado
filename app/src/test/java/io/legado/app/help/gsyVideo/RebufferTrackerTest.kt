package io.legado.app.help.gsyVideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 稳态卡顿计时（design §1.14 #6 / tasks §13.8）
 */
class RebufferTrackerTest {

    private var now = 0L
    private val tracker = RebufferTracker { now }

    @Test
    fun `未开始计时时结束返回null`() {
        assertNull(tracker.onBufferingEnd())
    }

    @Test
    fun `正常缓冲返回真实时长`() {
        now = 1_000L
        tracker.onBufferingStart()
        now = 1_800L
        assertEquals(800L, tracker.onBufferingEnd())
    }

    @Test
    fun `seek 引发的缓冲不计入且不影响下一次`() {
        tracker.markSeek()
        now = 1_000L
        tracker.onBufferingStart()
        now = 1_500L
        assertNull(tracker.onBufferingEnd())

        now = 2_000L
        tracker.onBufferingStart()
        now = 2_300L
        assertEquals(300L, tracker.onBufferingEnd())
    }

    @Test
    fun `重复结束不会重复计数`() {
        now = 1_000L
        tracker.onBufferingStart()
        now = 1_200L
        assertEquals(200L, tracker.onBufferingEnd())
        assertNull(tracker.onBufferingEnd())
    }

    @Test
    fun `reset 丢弃进行中的计时`() {
        now = 1_000L
        tracker.onBufferingStart()
        tracker.reset()
        now = 2_000L
        assertNull(tracker.onBufferingEnd())
    }

    @Test
    fun `时钟回拨按0毫秒处理`() {
        now = 1_000L
        tracker.onBufferingStart()
        now = 500L
        assertEquals(0L, tracker.onBufferingEnd())
    }
}
