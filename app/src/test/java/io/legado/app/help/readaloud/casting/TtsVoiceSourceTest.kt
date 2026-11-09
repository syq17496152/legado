package io.legado.app.help.readaloud.casting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 声源适配层数据契约（G-01 配对 TtsVoiceSource 改动）：
 * T8.7 角色徽章靠 `TtsVoiceRef.roleName` per-utterance 透传给引擎，默认必须为空（不影响既有调用方）。
 */
class TtsVoiceSourceTest {

    @Test
    fun `voiceRef 默认不带角色名`() {
        val ref = TtsVoiceRef(voiceId = "b6_m_teen|内置·男·少年", displayName = "内置·男·少年")
        assertTrue(ref.roleName.isEmpty())
        assertEquals(TtsVoiceRef.CHANNEL_SYSTEM, ref.channel)
    }

    @Test
    fun `copy 携带角色名保留音色键`() {
        val ref = TtsVoiceRef(voiceId = "b6_m_youth|内置·男·青年", displayName = "内置·男·青年")
            .copy(roleName = "张三")
        assertEquals("b6_m_youth|内置·男·青年", ref.voiceId)
        assertEquals("张三", ref.roleName)
    }

    @Test
    fun `透传键名固定`() {
        // 键名是跨进程约定（朗读服务写 Bundle、引擎服务读 SynthesisRequest.params），改动即断链
        assertEquals("io.legado.builtin.roleName", TtsVoiceRef.EXTRA_ROLE_NAME)
        assertEquals("io.legado.builtin.voiceName", TtsVoiceRef.EXTRA_VOICE_NAME)
    }

    /**
     * 逐段音色必须走 Bundle 兜底（真机铁证 2026-11-09）：
     * reading 链路只调 setVoice，框架静默不生效 ⇒ 多角色各段全落到引擎默认音（同一声音）。
     * 源码断言：utterance 组装 Bundle 时必须写入 EXTRA_VOICE_NAME。
     */
    @Test
    fun `朗读 utterance 必须把音色键写进 Bundle`() {
        val src = io.legado.app.testkit.SourceFileProbe
            .sourceText("help/readaloud/casting/TtsVoiceSource.kt")
        assertTrue(
            "Bundle 必须写 EXTRA_VOICE_NAME（否则多角色朗读各段同声）",
            src.contains("putString(TtsVoiceRef.EXTRA_VOICE_NAME")
        )
        assertTrue(
            "只在 voiceId 非空时写，避免空键覆盖引擎默认音",
            src.contains("if (!voice?.voiceId.isNullOrBlank())")
        )
    }
}
