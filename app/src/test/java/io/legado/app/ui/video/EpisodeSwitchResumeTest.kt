package io.legado.app.ui.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选集切换补播判定（真机 2026-11-07：切集后引擎 READY 但黑屏+播放键，需焦点恢复后补 startPlayLogic）
 */
class EpisodeSwitchResumeTest {

    @Test
    fun `切集后播放器被暂停时需要补播`() {
        assertTrue(needResumeAfterPickerSwitch(switched = true, playerPlaying = false))
    }

    @Test
    fun `切集后仍在播放时不应重复起播`() {
        assertFalse(needResumeAfterPickerSwitch(switched = true, playerPlaying = true))
    }

    @Test
    fun `未切集时不补播`() {
        assertFalse(needResumeAfterPickerSwitch(switched = false, playerPlaying = false))
        assertFalse(needResumeAfterPickerSwitch(switched = false, playerPlaying = true))
    }
}
