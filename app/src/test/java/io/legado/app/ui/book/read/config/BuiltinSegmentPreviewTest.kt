package io.legado.app.ui.book.read.config

import io.legado.app.help.readaloud.casting.CharacterVoiceAssignService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T5.5 分镜段级试听（改码配对：`BuiltinSegmentPreview` + `BuiltinTtsUiSupport.segmentVoiceName`）。
 *
 * 断言两件影响体验的事：
 * 1) 试听文本必须截断（长段不会念几十秒），截断时补省略号；
 * 2) 音色解析必须"角色命中就换声、没命中回落默认"（保证点了就有声，不静默失败）。
 */
class BuiltinSegmentPreviewTest {

    @Test
    fun `短文本原样返回 长文本截断并补省略号`() {
        assertEquals("张三说：走吧。", BuiltinSegmentPreview.previewPhrase("  张三说：走吧。  "))
        val long = "这是一段很长的正文".repeat(20)
        val phrase = BuiltinSegmentPreview.previewPhrase(long)
        assertEquals(BuiltinSegmentPreview.PHRASE_LIMIT + 1, phrase.length)
        assertTrue("截断必须补省略号", phrase.endsWith("…"))
        assertTrue("截断前缀须等于原文前缀", phrase.startsWith(long.take(BuiltinSegmentPreview.PHRASE_LIMIT)))
    }

    @Test
    fun `空白文本不发声`() {
        assertEquals("", BuiltinSegmentPreview.previewPhrase("   \n  "))
    }

    @Test
    fun `角色命中时用该角色音色 未命中回落默认音色`() {
        val rows = listOf(
            CharacterVoiceAssignService.RoleVoiceRow(
                characterId = 1, name = "张三", gender = "male", roleLevel = 1,
                voiceName = "b6_m_youth|内置·男·青年", voiceDisplayName = "内置·男·青年"
            ),
            CharacterVoiceAssignService.RoleVoiceRow(
                characterId = 2, name = "李四", gender = "male", roleLevel = 0,
                voiceName = "", voiceDisplayName = ""
            )
        )
        // 命中：张三 ⇒ 张三绑的音色（段级换声生效）
        assertEquals(
            "b6_m_youth|内置·男·青年",
            BuiltinTtsUiSupport.segmentVoiceName("张三", rows, "fallback")
        )
        // 未配声的角色（李四）⇒ 回落默认音色（不静默失败）
        assertEquals("fallback", BuiltinTtsUiSupport.segmentVoiceName("李四", rows, "fallback"))
        // 旁白/未识别（空角色名）⇒ 回落默认音色
        assertEquals("fallback", BuiltinTtsUiSupport.segmentVoiceName("", rows, "fallback"))
        // 角色不存在 ⇒ 回落默认音色
        assertEquals("fallback", BuiltinTtsUiSupport.segmentVoiceName("王五", rows, "fallback"))
    }
}
