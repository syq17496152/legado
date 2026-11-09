package io.legado.app.constant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 离线听书引导的两个偏好键（offline-tts-ux-consolidation 2.7）。
 *
 * 为什么单独锁字符串值：这两个键会随 `config.xml` 进备份（`BackupSelectorConfig`），
 * 改名即等于老用户"已引导/已跳过"状态丢失；键值写错还会与其它键冲突。
 */
class PreferKeyOfflineTtsOnboardTest {

    @Test
    fun `引导状态键固定且互不冲突`() {
        assertEquals("offlineTtsOnboarded", PreferKey.offlineTtsOnboarded)
        assertEquals("offlineTtsOnboardSkipCount", PreferKey.offlineTtsOnboardSkipCount)
        assertNotEquals(PreferKey.offlineTtsOnboarded, PreferKey.offlineTtsOnboardSkipCount)
    }
}
