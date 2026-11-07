package io.legado.app.ui.video

import io.legado.app.help.video.PlaybackMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §12.6/§12.9：竖滑翻页能力判定。
 *
 * 真机回归：收藏直入已进入 SERIES，但 ViewPager 仍可翻页 ⇒ 上滑切到「另一部作品」（FEED 行为），
 * 与矩阵 #8「SERIES=下一集」不符。修复后 SERIES 禁翻页，上滑交由 dispatchVerticalSwitch 分派。
 */
class PagerInputPolicyTest {

    @Test
    fun `SERIES 禁止翻页`() {
        assertFalse(pagerUserInputEnabled(PlaybackMode.SERIES, isSinglePage = false, listSize = 20))
    }

    @Test
    fun `FEED 多篇允许翻页`() {
        assertTrue(pagerUserInputEnabled(PlaybackMode.FEED, isSinglePage = false, listSize = 20))
    }

    @Test
    fun `FEED 单篇不允许翻页`() {
        assertFalse(pagerUserInputEnabled(PlaybackMode.FEED, isSinglePage = false, listSize = 1))
    }

    @Test
    fun `末集降级为 SINGLE 后仍有列表可继续上滑`() {
        assertTrue(pagerUserInputEnabled(PlaybackMode.SINGLE, isSinglePage = false, listSize = 3))
        assertFalse(pagerUserInputEnabled(PlaybackMode.SINGLE, isSinglePage = false, listSize = 1))
    }

    @Test
    fun `书源与单URL单页恒禁翻页`() {
        assertFalse(pagerUserInputEnabled(PlaybackMode.FEED, isSinglePage = true, listSize = 20))
        assertFalse(pagerUserInputEnabled(PlaybackMode.SINGLE, isSinglePage = true, listSize = 20))
    }

    /**
     * 手势接管必须与翻页能力严格互补。
     *
     * 真机铁证（2026-11-07）：SERIES 禁翻页后上滑零日志、零响应——因为 ViewPager 不消费，
     * 手势层又只在 `book != null` 时才回调。若不互补，SERIES 的上滑会彻底失效。
     */
    @Test
    fun `手势接管与翻页能力严格互补`() {
        assertTrue(gestureHandlesVerticalFling(PlaybackMode.SERIES, isSinglePage = false, listSize = 20))
        assertTrue(gestureHandlesVerticalFling(PlaybackMode.FEED, isSinglePage = false, listSize = 1))
        assertTrue(gestureHandlesVerticalFling(PlaybackMode.SINGLE, isSinglePage = true, listSize = 20))
        assertFalse(gestureHandlesVerticalFling(PlaybackMode.FEED, isSinglePage = false, listSize = 20))
        assertFalse(gestureHandlesVerticalFling(PlaybackMode.SINGLE, isSinglePage = false, listSize = 3))
    }
}
