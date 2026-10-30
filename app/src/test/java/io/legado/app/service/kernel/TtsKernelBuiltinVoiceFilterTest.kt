package io.legado.app.service.kernel

import io.legado.app.help.tts.builtin.BuiltinVoice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置音色过滤（T8.9 builtin_voice_list）：性别/年龄/标签 三处命中，空关键词全通过。
 */
class TtsKernelBuiltinVoiceFilterTest {

    private val voice = BuiltinVoice(
        id = "b6_f_youth",
        name = "内置·女·青年",
        gender = "female",
        age = "youth",
        tags = listOf("female", "youth", "narration")
    )

    @Test
    fun `空关键词全部命中`() {
        assertTrue(TtsKernel.builtinVoiceMatches(voice, ""))
        assertTrue(TtsKernel.builtinVoiceMatches(voice, "   "))
    }

    @Test
    fun `性别 年龄 标签任一命中即可`() {
        assertTrue(TtsKernel.builtinVoiceMatches(voice, "female"))
        assertTrue(TtsKernel.builtinVoiceMatches(voice, "YOUTH"))
        assertTrue(TtsKernel.builtinVoiceMatches(voice, "narration"))
        assertFalse(TtsKernel.builtinVoiceMatches(voice, "male"))
        assertFalse(TtsKernel.builtinVoiceMatches(voice, "aged"))
    }
}
