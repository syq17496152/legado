package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2026-11-10 真机缺陷「对话朗读每句结尾多读一个耶」的回归用例。
 *
 * 根因：Kokoro 中文前端把词表外符号（～ # * · ☆ 零宽字符…）映射成未知音素并补一个音节。
 * 这些用例锁死「送引擎前必然剔除装饰符号、保留正常句读与文字」。
 */
class BuiltinSpeechTextSanitizerTest {

    @Test
    fun `句尾装饰符号被剔除`() {
        assertEquals("你来了。", BuiltinSpeechTextSanitizer.sanitize("你来了。～"))
        assertEquals("你来了。", BuiltinSpeechTextSanitizer.sanitize("你来了。~"))
        assertEquals("你来了。", BuiltinSpeechTextSanitizer.sanitize("你来了。#"))
        assertEquals("你来了。", BuiltinSpeechTextSanitizer.sanitize("你来了。*"))
        assertEquals("你来了。", BuiltinSpeechTextSanitizer.sanitize("你来了。☆"))
        assertEquals("你来了。", BuiltinSpeechTextSanitizer.sanitize("你来了。·"))
        assertEquals("你来了。", BuiltinSpeechTextSanitizer.sanitize("你来了。•"))
        assertEquals("你来了。", BuiltinSpeechTextSanitizer.sanitize("你来了。♪"))
        assertEquals("你来了。", BuiltinSpeechTextSanitizer.sanitize("你来了。※"))
    }

    @Test
    fun `不可见字符被剔除`() {
        assertEquals("你来了。", BuiltinSpeechTextSanitizer.sanitize("你来了。\u200b"))
        assertEquals("你来了。", BuiltinSpeechTextSanitizer.sanitize("\ufeff你来了。"))
    }

    @Test
    fun `括号类符号被剔除`() {
        assertEquals("你来了。", BuiltinSpeechTextSanitizer.sanitize("你来了。）"))
        assertEquals("你来了。", BuiltinSpeechTextSanitizer.sanitize("你来了。】"))
        assertEquals("你来了。", BuiltinSpeechTextSanitizer.sanitize("你来了。」"))
        assertEquals("他笑了", BuiltinSpeechTextSanitizer.sanitize("（他笑了）"))
    }

    @Test
    fun `正常句读与引号保留`() {
        val text = "“你来了？”他问。"
        assertEquals(text, BuiltinSpeechTextSanitizer.sanitize(text))
        val english = "Hello, world! 版本 2.0 — ok…"
        assertEquals(english, BuiltinSpeechTextSanitizer.sanitize(english))
    }

    @Test
    fun `纯符号清洗为空且计数正确`() {
        assertEquals("", BuiltinSpeechTextSanitizer.sanitize("～☆※"))
        assertEquals(3, BuiltinSpeechTextSanitizer.removedCount("～☆※"))
        assertEquals(0, BuiltinSpeechTextSanitizer.removedCount("你好，世界。"))
        assertEquals(1, BuiltinSpeechTextSanitizer.removedCount("你好～"))
    }

    @Test
    fun `空文本安全`() {
        assertEquals("", BuiltinSpeechTextSanitizer.sanitize(""))
        assertEquals(0, BuiltinSpeechTextSanitizer.removedCount(""))
    }
}
