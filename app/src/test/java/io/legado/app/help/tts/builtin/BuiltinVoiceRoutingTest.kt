package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音色 ↔ 档位路由（不变量 I-6 / offline-tts-llm-productization 2.6）。
 * 这些断言钉住三类真实失守：①存量空 engine 被当成「无音色」②多说话人档混入克隆音色
 * ③换声后缓存键不变 ⇒ 播放上一个音色的旧音频。
 */
class BuiltinVoiceRoutingTest {

    private fun vitsMulti(count: Int = 3) = BuiltinModelProfile(
        id = "multi",
        title = "多音色档",
        engine = BuiltinModelProfile.ENGINE_VITS,
        sampleRate = 22050,
        files = listOf("model.onnx", "tokens.txt"),
        speakerCount = count
    )

    private fun cloneProfile() = BuiltinModelProfile(
        id = "clone",
        title = "克隆档",
        engine = BuiltinModelProfile.ENGINE_ZIPVOICE,
        sampleRate = 24000,
        files = listOf("encoder.int8.onnx")
    )

    private fun singleProfile() = BuiltinModelProfile(
        id = "fast",
        title = "极速档",
        engine = BuiltinModelProfile.ENGINE_VITS,
        sampleRate = 22050,
        files = listOf("model.onnx")
    )

    private fun legacyCloneVoice(id: String, name: String) = BuiltinVoice(
        id = id,
        name = name,
        refAudio = "$id/prompt.wav",
        refText = "参考文本",
        origin = BuiltinVoice.ORIGIN_ZIP,
        profileId = "clone"
        // engine 故意留空：模拟 2026-11-03 之前的存量 506/518 条索引
    )

    @Test
    fun `存量空 engine 按 zipvoice 解释，克隆档仍能选中音色`() {
        val legacy = legacyCloneVoice("v1", "沉稳男")
        assertEquals("zipvoice", legacy.engineOrDefault())

        val picked = BuiltinVoiceRouting.pick(cloneProfile(), listOf(legacy), "v1|沉稳男")

        assertNotNull(picked)
        assertEquals("v1", picked!!.id)
    }

    @Test
    fun `多说话人档只认本档 sid 音色，不混入克隆档音色`() {
        val legacy = legacyCloneVoice("v1", "沉稳男")
        val speaker = BuiltinVoice(
            id = "multi-s2", name = "音色 2", profileId = "multi",
            engine = BuiltinModelProfile.ENGINE_VITS, sid = 2, readOnly = true
        )

        val picked = BuiltinVoiceRouting.pick(vitsMulti(), listOf(legacy, speaker), "v1|沉稳男")

        assertEquals("multi-s2", picked!!.id)
        assertEquals(2, picked.sid)
    }

    @Test
    fun `多说话人档没有任何 sid 音色时返回 null（回落引擎默认音色而不是报错）`() {
        val legacy = legacyCloneVoice("v1", "沉稳男")

        val picked = BuiltinVoiceRouting.pick(vitsMulti(), listOf(legacy), "v1|沉稳男")

        assertNull(picked)
    }

    @Test
    fun `同引擎单声档不会被多说话人档误选`() {
        // fast 与 multi 同为 vits 引擎；单声档音色 sid=0 且 profileId=fast
        val singleVoice = BuiltinVoice(
            id = "fast-s0", name = "极速·单人声", profileId = "fast",
            engine = BuiltinModelProfile.ENGINE_VITS
        )

        assertNull(BuiltinVoiceRouting.pick(vitsMulti(), listOf(singleVoice), "fast-s0"))
    }

    @Test
    fun `单声档恒返回 null，音色不参与合成`() {
        val legacy = legacyCloneVoice("v1", "沉稳男")

        assertNull(BuiltinVoiceRouting.pick(singleProfile(), listOf(legacy), "v1|沉稳男"))
    }

    @Test
    fun `未命中绑定键时回落本档首条音色`() {
        val speaker = BuiltinVoice(
            id = "multi-s1", name = "音色 1", profileId = "multi",
            engine = BuiltinModelProfile.ENGINE_VITS, sid = 1
        )

        val picked = BuiltinVoiceRouting.pick(vitsMulti(), listOf(speaker), "不存在的音色")

        assertEquals("multi-s1", picked!!.id)
    }

    // ===== 切档与正在朗读的联动（真机铁证 2026-11-09：切到 hq 后朗读仍送 multi 音色名）=====

    @Test
    fun `切档且正在朗读时必须停下朗读`() {
        assertTrue(
            BuiltinVoiceRouting.mustStopReadingAfterProfileSwitch(
                previousProfileId = "multi",
                nextProfileId = "hq",
                isReading = true
            )
        )
    }

    @Test
    fun `同档重复设置不打断朗读`() {
        assertFalse(
            BuiltinVoiceRouting.mustStopReadingAfterProfileSwitch(
                previousProfileId = "hq",
                nextProfileId = "hq",
                isReading = true
            )
        )
    }

    @Test
    fun `没在朗读或档位未知时不触发停止`() {
        assertFalse(
            BuiltinVoiceRouting.mustStopReadingAfterProfileSwitch("multi", "hq", isReading = false)
        )
        // 尚未落盘过档位（空）⇒ 无法判断旧绑定，不做打断
        assertFalse(
            BuiltinVoiceRouting.mustStopReadingAfterProfileSwitch("", "hq", isReading = true)
        )
        assertFalse(
            BuiltinVoiceRouting.mustStopReadingAfterProfileSwitch("multi", "", isReading = true)
        )
    }

    /**
     * 诊断噪声门禁（MEmu 实测 2026-11-09）：单声档不吃音色，逐段打「音色不可用」会把
     * 真正的跨档错配/参考音频缺失信号淹没，因此只在音色参与合成的档位才告警。
     */
    @Test
    fun `单声档不再告警音色不可用，多声与克隆档才告警`() {
        assertFalse(
            BuiltinVoiceRouting.shouldWarnUnresolvedVoice(singleProfile(), "b6_m_teen|内置·男·少年")
        )
        assertTrue(
            BuiltinVoiceRouting.shouldWarnUnresolvedVoice(vitsMulti(), "hq-s9|别人的音色")
        )
        assertTrue(
            BuiltinVoiceRouting.shouldWarnUnresolvedVoice(cloneProfile(), "clone-x|已删除的音色")
        )
        // 没有请求音色（旁白/默认音）不告警
        assertFalse(BuiltinVoiceRouting.shouldWarnUnresolvedVoice(vitsMulti(), ""))
    }

    @Test
    fun `多说话人缓存键按 sid 分键，换声不会命中旧缓存`() {
        val s1 = BuiltinVoice(
            id = "multi-s1", name = "音色 1", profileId = "multi",
            engine = BuiltinModelProfile.ENGINE_VITS, sid = 1
        )
        val s2 = s1.copy(id = "multi-s2", name = "音色 2", sid = 2)

        assertEquals("sid:1", BuiltinVoiceRouting.voiceKey(vitsMulti(), s1))
        assertEquals("sid:2", BuiltinVoiceRouting.voiceKey(vitsMulti(), s2))
    }

    @Test
    fun `克隆档缓存键按绑定键、单声档固定 single`() {
        val legacy = legacyCloneVoice("v1", "沉稳男")

        assertEquals("v1|沉稳男", BuiltinVoiceRouting.voiceKey(cloneProfile(), legacy))
        assertEquals("single", BuiltinVoiceRouting.voiceKey(singleProfile(), null))
    }
}
