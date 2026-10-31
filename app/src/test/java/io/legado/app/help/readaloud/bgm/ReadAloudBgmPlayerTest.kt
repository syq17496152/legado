package io.legado.app.help.readaloud.bgm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8.4 朗读配乐播放器：门控与音量纯函数断言（JVM 可测，不依赖 MediaPlayer）。
 *
 * 设计 DoD「关掉后无 BGM」的判定缝在 [ReadAloudBgmPlayer.shouldPlay]：
 * 开关关闭时恒为 false（无论配乐库有无曲目）。
 */
class ReadAloudBgmPlayerTest {

    @Test
    fun `开关关闭时不出声 即使有可用配乐`() {
        assertFalse(ReadAloudBgmPlayer.shouldPlay(enabled = false, hasTrack = true))
        assertFalse(ReadAloudBgmPlayer.shouldPlay(enabled = false, hasTrack = false))
    }

    @Test
    fun `开关打开但无配乐不出声`() {
        assertFalse(ReadAloudBgmPlayer.shouldPlay(enabled = true, hasTrack = false))
    }

    @Test
    fun `开关打开且有配乐才出声`() {
        assertTrue(ReadAloudBgmPlayer.shouldPlay(enabled = true, hasTrack = true))
    }

    @Test
    fun `目标音量 上限乘以曲目音量乘以全局音量`() {
        // 0.22 × 1.0 × 1.0 = 0.22（设计上限）
        assertEquals(0.22f, ReadAloudBgmPlayer.targetVolume(1.0f, 1.0f), 0.0001f)
        // 曲目默认音量 0.5 ⇒ 减半；全局 50% ⇒ 再减半
        assertEquals(0.055f, ReadAloudBgmPlayer.targetVolume(0.5f, 0.5f), 0.0001f)
        // 静音
        assertEquals(0.0f, ReadAloudBgmPlayer.targetVolume(1.0f, 0.0f), 0.0001f)
    }

    @Test
    fun `目标音量夹紧 防越界参数盖过人声`() {
        // 输入越界（曲目音量 2.0 / 全局 3.0）仍夹紧到 0.22 上限
        assertEquals(0.22f, ReadAloudBgmPlayer.targetVolume(2.0f, 3.0f), 0.0001f)
        // 负值夹到 0
        assertEquals(0.0f, ReadAloudBgmPlayer.targetVolume(-1.0f, 1.0f), 0.0001f)
    }
}
