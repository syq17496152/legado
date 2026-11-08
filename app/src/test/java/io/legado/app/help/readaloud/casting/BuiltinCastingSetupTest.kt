package io.legado.app.help.readaloud.casting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置多角色模板准备（G-01 配对 BuiltinCastingSetup 改动）：
 * assets 是否含目标模板是"能否激活"的前置条件，缺失即必须以失败文案告知用户（不能静默单声）。
 */
class BuiltinCastingSetupTest {

    private val assetLike = """
        [
          {"id":"builtin_dual_voice","name":"旁白/对白双声"},
          {"id": "builtin_immersive_multi", "name": "沉浸多角色（内置）"}
        ]
    """.trimIndent()

    @Test
    fun `assets 含内置多角色模板`() {
        assertTrue(BuiltinCastingSetup.templatePresent(assetLike))
        assertTrue(BuiltinCastingSetup.templatePresent(assetLike, "builtin_dual_voice"))
    }

    @Test
    fun `缺失模板或空文本判定为不存在`() {
        assertFalse(BuiltinCastingSetup.templatePresent("""[{"id":"builtin_mono"}]"""))
        assertFalse(BuiltinCastingSetup.templatePresent(""))
        assertFalse(BuiltinCastingSetup.templatePresent(null))
        assertFalse(BuiltinCastingSetup.templatePresent(assetLike, ""))
        // 前缀相同的另一个 id 不能被误判
        assertFalse(BuiltinCastingSetup.templatePresent("""[{"id":"builtin_immersive_multi_v2"}]"""))
    }

    /**
     * per-utterance Bundle 的两个键必须互不相同（否则音色键会覆盖角色名，通知徽章/音色都错），
     * 且统一用 `io.legado.builtin.` 前缀（避免与 framework 自带键冲突）。
     */
    @Test
    fun `音色键与角色名键互不冲突`() {
        assertTrue(TtsVoiceRef.EXTRA_VOICE_NAME.startsWith("io.legado.builtin."))
        assertTrue(TtsVoiceRef.EXTRA_ROLE_NAME.startsWith("io.legado.builtin."))
        assertFalse(TtsVoiceRef.EXTRA_VOICE_NAME == TtsVoiceRef.EXTRA_ROLE_NAME)
    }
}
