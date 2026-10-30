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
    }
}
