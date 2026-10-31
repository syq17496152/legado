package io.legado.app.service

import io.legado.app.help.readaloud.bgm.ReadAloudBgmPlayer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8.4 朗读服务 × 配乐生命周期接线（改码配对：`TTSReadAloudService` 的四处调用）。
 *
 * 两类断言：
 * 1) **行为断言**（带期望值）：开关关闭时配乐门控恒 false ⇒ 朗读照常但无 BGM；
 * 2) **接线断言**：服务源码必须保留 play/pause/playStop/onDestroy 四处调用，
 *    防止后续重构把"关掉后无 BGM / 暂停即静音 / 停止即释放"退回成无人调用的死代码。
 */
class TtsReadAloudServiceBgmHookTest {

    @Test
    fun `开关关闭时配乐门控为假 朗读仍可继续`() {
        // 期望值：false —— 与 DoD「关掉后无 BGM」一一对应
        assertEquals(false, ReadAloudBgmPlayer.shouldPlay(enabled = false, hasTrack = true))
        // 音量换算也必须是确定值（0.22 × 0.6 × 0.5 = 0.066）
        assertEquals(0.066f, ReadAloudBgmPlayer.targetVolume(0.6f, 0.5f), 0.0005f)
    }

    @Test
    fun `朗读服务保留配乐四处生命周期接线`() {
        val source = File(
            "src/main/java/io/legado/app/service/TTSReadAloudService.kt"
        ).let { if (it.exists()) it else File("app/src/main/java/io/legado/app/service/TTSReadAloudService.kt") }
        assertTrue("源文件不存在：${source.absolutePath}", source.exists())
        val text = source.readText()
        // 起播：play() 内
        assertTrue("缺少起播接线", text.contains("ReadAloudBgmPlayer.start(this)"))
        // 暂停：pauseReadAloud 内
        assertTrue("缺少暂停接线", text.contains("ReadAloudBgmPlayer.pause()"))
        // 停止：playStop / onDestroy 各一处 ⇒ 至少出现两次 stop()
        val stopCount = Regex("ReadAloudBgmPlayer\\.stop\\(\\)").findAll(text).count()
        assertTrue("stop 接线应有两处（playStop + onDestroy），实际 $stopCount", stopCount >= 2)
    }
}
