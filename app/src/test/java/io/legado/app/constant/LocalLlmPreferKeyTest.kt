package io.legado.app.constant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.2 端侧 LLM 偏好键：键名是持久化契约（改名 = 用户设置丢失），必须锁死。
 */
class LocalLlmPreferKeyTest {

    @Test
    fun `端侧 LLM 偏好键名固定且互不重复`() {
        assertEquals("localLlmEnabled", PreferKey.localLlmEnabled)
        assertEquals("localLlmProfileId", PreferKey.localLlmProfileId)
        assertEquals("localLlmSpeedTokPerSec", PreferKey.localLlmSpeedTokPerSec)
        assertEquals("localLlmUnsupportedReason", PreferKey.localLlmUnsupportedReason)
        assertEquals("aiTaskPolicy", PreferKey.aiTaskPolicy)

        val keys = listOf(
            PreferKey.localLlmEnabled,
            PreferKey.localLlmProfileId,
            PreferKey.localLlmSpeedTokPerSec,
            PreferKey.localLlmUnsupportedReason,
            PreferKey.aiTaskPolicy
        )
        assertTrue("键名不得重复", keys.toSet().size == keys.size)
        assertTrue("键名不得为空", keys.none { it.isBlank() })
    }
}
