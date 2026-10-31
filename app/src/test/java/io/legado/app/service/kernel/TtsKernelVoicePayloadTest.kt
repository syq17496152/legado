package io.legado.app.service.kernel

import io.legado.app.help.tts.builtin.BuiltinVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `builtin_voice_list` 对外字段契约（T8.9 客户端实调回归）：
 * 必须带 `voiceName`（id|显示名）复合键，否则 `builtin_assign_voices` 拿不到可直接回传的值。
 */
class TtsKernelVoicePayloadTest {

    private val voice = BuiltinVoice(
        id = "b6_m_youth",
        name = "内置·男·青年",
        refAudio = "b6_m_youth/prompt.wav",
        refText = "稿",
        origin = BuiltinVoice.ORIGIN_PRESET,
        gender = "male",
        age = "youth",
        tags = listOf("male", "youth"),
        readOnly = true
    )

    @Test
    fun `音色行必须含可直接回传的 voiceName 复合键`() {
        val payload = TtsKernel.builtinVoicePayload(voice)
        assertEquals("b6_m_youth", payload["id"])
        // voiceName = id|显示名（与 TtsVoiceRef / 选角模板口径一致）
        assertEquals("b6_m_youth|内置·男·青年", payload["voiceName"])
        assertEquals("内置·男·青年", payload["name"])
        assertEquals("preset", payload["origin"])
        assertEquals("male", payload["gender"])
        assertEquals(true, payload["readOnly"])
    }

    @Test
    fun `标签过滤仍按性别年龄与标签命中`() {
        assertTrue(TtsKernel.builtinVoiceMatches(voice, "male"))
        assertTrue(TtsKernel.builtinVoiceMatches(voice, "youth"))
        assertTrue(TtsKernel.builtinVoiceMatches(voice, "  "))
        // 不相关关键词不命中（避免"全部命中"式假过滤）
        assertEquals(false, TtsKernel.builtinVoiceMatches(voice, "female"))
    }

    @Test
    fun `预合成章节区间按 1 基校验并转 0 基`() {
        // 1..3 章（含）⇒ 内部 0..2
        assertEquals(0 to 2, TtsKernel.prebuildRange(1, 3))
        assertEquals(4 to 4, TtsKernel.prebuildRange(5, 5))
        // 非法区间必须显式抛错（防 0 基/倒序静默入队）
        runCatching { TtsKernel.prebuildRange(0, 1) }.let { assertTrue(it.isFailure) }
        runCatching { TtsKernel.prebuildRange(3, 1) }.let { assertTrue(it.isFailure) }
    }
}
