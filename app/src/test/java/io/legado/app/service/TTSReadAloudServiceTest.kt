package io.legado.app.service

import io.legado.app.help.readaloud.speech.SpeechRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 5.6 朗读引擎回退决策（纯函数）：包缺失提前回退、强制默认不重复提示、HTTP 路由不探测。
 */
class TTSReadAloudServiceTest {

    @Test
    fun `引擎包缺失时回退默认并给出可读原因`() {
        val (engine, notice) = TTSReadAloudService.resolveInitEngine(
            engineType = SpeechRoute.ENGINE_SYSTEM,
            engineValue = "com.gone.tts",
            packageInstalled = false,
            forceDefault = false,
            fallbackInit = false,
            label = "某引擎"
        )
        assertEquals("", engine)
        assertTrue(notice.orEmpty().contains("某引擎"))
        assertTrue(notice.orEmpty().contains("未安装"))
    }

    @Test
    fun `引擎包存在时按原值创建且不提示`() {
        val (engine, notice) = TTSReadAloudService.resolveInitEngine(
            SpeechRoute.ENGINE_SYSTEM, "com.alive.tts", true, false, false, "某引擎"
        )
        assertEquals("com.alive.tts", engine)
        assertNull(notice)
    }

    @Test
    fun `显示名缺失时提示改用目标包名`() {
        // speakerName 为空时 label 回落「系统默认」；提示若仍用它会出现"「系统默认」未安装"的矛盾文案
        val (_, notice) = TTSReadAloudService.resolveInitEngine(
            SpeechRoute.ENGINE_SYSTEM, "com.gone.tts", false, false, false, "系统默认"
        )
        assertTrue(notice.orEmpty().contains("com.gone.tts"))
        assertTrue(!notice.orEmpty().contains("「系统默认」"))
    }

    @Test
    fun `强制默认或已回退时不再重复提示`() {
        val forced = TTSReadAloudService.resolveInitEngine(
            SpeechRoute.ENGINE_SYSTEM, "com.gone.tts", false, true, false, "某引擎"
        )
        assertEquals("", forced.first)
        assertNull(forced.second)

        val alreadyFellBack = TTSReadAloudService.resolveInitEngine(
            SpeechRoute.ENGINE_SYSTEM, "com.gone.tts", false, false, true, "某引擎"
        )
        assertEquals("", alreadyFellBack.first)
        assertNull(alreadyFellBack.second)
    }

    @Test
    fun `HTTP 路由不探测包存在性`() {
        val (engine, notice) = TTSReadAloudService.resolveInitEngine(
            SpeechRoute.ENGINE_HTTP, "123", false, false, false, "某引擎"
        )
        assertEquals("123", engine)
        assertNull(notice)
    }

    /** 未识别对白沿用上一句角色音色（防止同一段对话里"角色音→旁白音"跳变） */
    @Test
    fun `未识别对白沿用上一句角色音色`() {
        val last = io.legado.app.help.readaloud.casting.TtsVoiceRef(
            voiceId = "角色音", displayName = "角色音", channel = "system"
        )
        val current = io.legado.app.help.readaloud.casting.TtsVoiceRef(
            voiceId = "旁白音", displayName = "旁白音", channel = "system"
        )
        val picked = stableDialogueVoice(
            isNarration = false, hasRole = false, lastDialogueVoice = last, current = current
        )
        assertEquals("角色音", picked?.voiceId)
    }

    /** 已识别对白：使用本次归因到的角色音色（不沿用上一句） */
    @Test
    fun `已识别对白使用新角色音色`() {
        val last = io.legado.app.help.readaloud.casting.TtsVoiceRef("旧角色音", "旧角色音", "system")
        val current = io.legado.app.help.readaloud.casting.TtsVoiceRef("新角色音", "新角色音", "system")
        val picked = stableDialogueVoice(
            isNarration = false, hasRole = true, lastDialogueVoice = last, current = current
        )
        assertEquals("新角色音", picked?.voiceId)
    }

    /** 旁白段不受稳定策略影响：始终使用旁白音色 */
    @Test
    fun `旁白段始终使用旁白音色`() {
        val last = io.legado.app.help.readaloud.casting.TtsVoiceRef("角色音", "角色音", "system")
        val current = io.legado.app.help.readaloud.casting.TtsVoiceRef("旁白音", "旁白音", "system")
        val picked = stableDialogueVoice(
            isNarration = true, hasRole = false, lastDialogueVoice = last, current = current
        )
        assertEquals("旁白音", picked?.voiceId)
    }

    /** 还没有任何角色音色时保持原值（不凭空造音色） */
    @Test
    fun `无历史角色音色时保持原值`() {
        val current = io.legado.app.help.readaloud.casting.TtsVoiceRef("旁白音", "旁白音", "system")
        assertNull(
            stableDialogueVoice(false, hasRole = false, lastDialogueVoice = null, current = null)
        )
        assertEquals(
            "旁白音",
            stableDialogueVoice(false, hasRole = false, lastDialogueVoice = null, current = current)?.voiceId
        )
    }
}
