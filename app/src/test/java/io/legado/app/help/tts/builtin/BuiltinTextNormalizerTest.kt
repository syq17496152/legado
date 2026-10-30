package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 简繁归一（T8.2）：简体原样返回；繁体超阈值才转换；正文文本不参与。
 */
class BuiltinTextNormalizerTest {

    @Test
    fun `简体文本比例为零且原样返回`() {
        val text = "他走进房间，看见桌上放着一本书。"
        assertEquals(0.0, BuiltinTextNormalizer.traditionalRatio(text), 0.0001)
        assertFalse(BuiltinTextNormalizer.isTraditional(text))
        assertEquals(text, BuiltinTextNormalizer.ensureSimplified(text))
    }

    @Test
    fun `繁体样本文本超阈值`() {
        val text = "他們說這本書很好看，我們一起來讀吧。"
        val ratio = BuiltinTextNormalizer.traditionalRatio(text)
        assertTrue("繁体占比应超阈值：$ratio", ratio > BuiltinTextNormalizer.TRADITIONAL_THRESHOLD)
        assertTrue(BuiltinTextNormalizer.isTraditional(text))
    }

    @Test
    fun `繁体文本被转换为简体`() {
        val text = "他們說這本書很好看，我們一起來讀吧。"
        val simplified = BuiltinTextNormalizer.ensureSimplified(text)
        assertFalse("不应再残留繁体专用字：$simplified", simplified.contains('們'))
        assertTrue("应转换为简体：$simplified", simplified.contains('们') || simplified.contains('这'))
    }

    @Test
    fun `少量繁体字不触发整段转换`() {
        // 只有 1 个繁体字 / 12 个汉字 ≈ 8%，低于 20%
        val text = "他走进房间，看见桌上放着一個书本。"
        assertEquals(text, BuiltinTextNormalizer.ensureSimplified(text))
    }

    @Test
    fun `空文本安全返回`() {
        assertEquals("", BuiltinTextNormalizer.ensureSimplified(""))
    }
}
