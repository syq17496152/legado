package io.legado.app.ui.video

import io.legado.app.constant.EventBus
import io.legado.app.help.video.EntryContext
import io.legado.app.help.video.EntryModeResolver
import io.legado.app.help.video.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 入口播放模式提示（design §12.5 / AD-10）
 *
 * 锁定两件事：
 * 1. Intent extra 解析只接受 [PlaybackMode] 的精确名称，脏值/大小写不符一律回落 null（不崩、不猜）；
 * 2. 显式 hint 优先于列表上下文推导，未注入时按「多项列表=FEED / 其他=SERIES」判定。
 */
class VideoModeHintTest {

    @Test
    fun `未注入时返回null以回落上下文推导`() {
        assertNull(parsePlaybackModeHint(null))
    }

    @Test
    fun `播放器浮层提示事件非空且与错误事件区分`() {
        assertEquals("videoPlayTip", EventBus.VIDEO_PLAY_TIP)
        assertTrue(EventBus.VIDEO_PLAY_TIP != EventBus.VIDEO_PLAY_ERROR)
    }

    @Test
    fun `合法名称解析为对应模式`() {
        assertEquals(PlaybackMode.FEED, parsePlaybackModeHint("FEED"))
        assertEquals(PlaybackMode.SERIES, parsePlaybackModeHint("SERIES"))
        assertEquals(PlaybackMode.SINGLE, parsePlaybackModeHint("SINGLE"))
    }

    @Test
    fun `大小写不符或脏值返回null`() {
        assertNull(parsePlaybackModeHint("series"))
        assertNull(parsePlaybackModeHint(""))
        assertNull(parsePlaybackModeHint("NEXT_EPISODE"))
    }

    @Test
    fun `显式hint优先于列表上下文`() {
        val context = EntryContext(listSize = 5, isSeriesEntry = false, modeHint = PlaybackMode.SINGLE)
        assertEquals(PlaybackMode.SINGLE, EntryModeResolver.resolve(context))
    }

    @Test
    fun `多项列表推导为FEED`() {
        assertEquals(
            PlaybackMode.FEED,
            EntryModeResolver.resolve(EntryContext(listSize = 3, isSeriesEntry = false))
        )
    }

    @Test
    fun `单元素或详情入口推导为SERIES`() {
        assertEquals(
            PlaybackMode.SERIES,
            EntryModeResolver.resolve(EntryContext(listSize = 1, isSeriesEntry = false))
        )
        assertEquals(
            PlaybackMode.SERIES,
            EntryModeResolver.resolve(EntryContext(listSize = 0, isSeriesEntry = true))
        )
    }
}
