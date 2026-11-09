package io.legado.app.ui.book.read.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 术语单源（AD-07 / R9）：映射完整、白名单不误伤。 */
class TtsUxTermsTest {

    @Test
    fun `旧词全部映射到新词`() {
        val m = TtsUxTerms.legacyMap
        assertEquals(TtsUxTerms.LISTEN, m["朗读"])
        assertEquals(TtsUxTerms.VOICE, m["发言人"])
        assertEquals(TtsUxTerms.SEGMENT, m["分镜"])
        assertEquals(TtsUxTerms.PREBUILD, m["批量预合成"])
        assertEquals(TtsUxTerms.ONBOARDING, m["开书向导"])
        assertTrue(m.size >= 10)
    }

    @Test
    fun `白名单文案不算旧词`() {
        assertFalse(TtsUxTerms.containsLegacy("系统 TTS 设置"))
        assertFalse(TtsUxTerms.containsLegacy("HTTP TTS 模型"))
        assertFalse(TtsUxTerms.containsLegacy("导入音色包"))
    }

    @Test
    fun `旧词可被识别出来`() {
        assertTrue(TtsUxTerms.containsLegacy("朗读设置"))
        assertTrue(TtsUxTerms.containsLegacy("分镜编辑"))
        assertTrue(TtsUxTerms.containsLegacy("批量预合成"))
    }

    @Test
    fun `新词自身不触发旧词判定`() {
        listOf(
            TtsUxTerms.LISTEN,
            TtsUxTerms.PROFILE,
            TtsUxTerms.VOICE,
            TtsUxTerms.ROLE_VOICE,
            TtsUxTerms.CASTING,
            TtsUxTerms.SEGMENT,
            TtsUxTerms.ONBOARDING
        ).forEach { assertFalse(it, TtsUxTerms.containsLegacy(it)) }
    }

    @Test
    fun `空白文案不误判`() {
        assertFalse(TtsUxTerms.containsLegacy(""))
        assertFalse(TtsUxTerms.containsLegacy("   "))
    }
}
