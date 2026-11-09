package io.legado.app.ui.book.read.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置项唯一归属（AD-11 / R14）。 */
class TtsSettingsOwnershipTest {

    @Test
    fun `六分组 id 与集合一致且无重复`() {
        val groups = TtsSettingsOwnership.groups(
            hasBook = true,
            lowEndDevice = false,
            localAiVisible = true
        )
        val ids = groups.map { it.id }
        assertEquals(6, ids.size)
        assertEquals(ids.size, ids.distinct().size)
        assertEquals(TtsSettingsOwnership.allIds, ids.toSet())
    }

    @Test
    fun `每个分组都有标签与说明`() {
        TtsSettingsOwnership.groups(true, false, true).forEach {
            assertTrue("分组 ${it.id} 缺标签", it.label.isNotBlank())
            assertTrue("分组 ${it.id} 缺说明", it.summary.isNotBlank())
            assertTrue(TtsSettingsOwnership.isValid(it.id))
        }
    }

    @Test
    fun `无书时不出现角色与分段`() {
        val ids = TtsSettingsOwnership.groups(true, false, true)
        assertTrue(ids.any { it.id == TtsSettingsOwnership.ID_ROLES })
        val noBook = TtsSettingsOwnership.groups(false, false, true).map { it.id }
        assertFalse(TtsSettingsOwnership.ID_ROLES in noBook)
        assertFalse(TtsSettingsOwnership.ID_SEGMENT in noBook)
    }

    @Test
    fun `本地 AI 不可见时整行不出现`() {
        val ids = TtsSettingsOwnership.groups(true, true, false).map { it.id }
        assertFalse(TtsSettingsOwnership.ID_AI in ids)
    }

    @Test
    fun `非法 id 不通过校验`() {
        assertFalse(TtsSettingsOwnership.isValid(""))
        assertFalse(TtsSettingsOwnership.isValid("templates"))
    }
}
