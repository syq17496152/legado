package io.legado.app.help.gsyVideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 稳态卡顿计时（design §1.14 #6 / tasks §13.8）
 *
 * 用例取自 2026-11-07 真机日志：拖动进度后连续出现 count=1..4、singleMs=0/11/8/2 的误判，
 * 且 count=2 时错误弹出「切换线路」提示 ⇒ 稳定期与最小可感知时长必须锁定。
 */
class RebufferTrackerTest {

    private var now = 0L
    private val tracker = RebufferTracker(clock = { now })

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
    fun `seek 稳定期内的多段抖动全部不计入`() {
        now = 1_000L
        tracker.markSeek()

        now = 1_050L
        tracker.onBufferingStart()
        now = 1_050L
        assertNull(tracker.onBufferingEnd())

        now = 1_300L
        tracker.onBufferingStart()
        now = 1_311L
        assertNull(tracker.onBufferingEnd())

        now = 2_000L
        tracker.onBufferingStart()
        now = 2_008L
        assertNull(tracker.onBufferingEnd())
    }

    @Test
    fun `buffering 先于 seek 上报时同样不计入`() {
        now = 1_000L
        tracker.onBufferingStart()
        now = 1_050L
        tracker.markSeek()
        now = 1_200L
        assertNull(tracker.onBufferingEnd())
    }

    @Test
    fun `seek 稳定期结束后的真实卡顿正常计入`() {
        now = 1_000L
        tracker.markSeek()

        now = 3_000L
        tracker.onBufferingStart()
        now = 4_000L
        assertEquals(1_000L, tracker.onBufferingEnd())
    }

    @Test
    fun `小于最小可感知时长的抖动不计入`() {
        now = 1_000L
        tracker.onBufferingStart()
        now = 1_150L
        assertNull(tracker.onBufferingEnd())
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
    fun `reset 丢弃进行中的计时与 seek 稳定期`() {
        now = 1_000L
        tracker.markSeek()
        tracker.onBufferingStart()
        tracker.reset()

        now = 2_000L
        assertNull(tracker.onBufferingEnd())

        // reset 后稳定期同样失效：新会话的首段真实卡顿正常计入
        now = 5_000L
        tracker.onBufferingStart()
        now = 5_500L
        assertEquals(500L, tracker.onBufferingEnd())
    }

    @Test
    fun `时钟回拨不产生负时长`() {
        now = 1_000L
        tracker.onBufferingStart()
        now = 500L
        assertNull(tracker.onBufferingEnd())
    }
}
