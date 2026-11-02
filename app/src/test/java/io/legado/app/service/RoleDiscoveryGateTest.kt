package io.legado.app.service

import io.legado.app.help.readaloud.role.OfflineCharacterDiscovery
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章开始角色累积门槛（TTSReadAloudService 钩子契约）：
 * 仅内置多角色链路生效、书标识有效、本章有正文时，才触发一次离线 NER 角色发现（fail-open）。
 */
class RoleDiscoveryGateTest {

    @Test
    fun `内置多角色且有正文时触发累积`() {
        assertTrue(
            OfflineCharacterDiscovery.shouldDiscoverAtChapterStart(
                builtinActive = true,
                bookKey = "work:test/book",
                paragraphs = listOf("张三说：“来了。”")
            )
        )
    }

    @Test
    fun `非内置链路不触发`() {
        assertFalse(
            OfflineCharacterDiscovery.shouldDiscoverAtChapterStart(
                builtinActive = false,
                bookKey = "work:test/book",
                paragraphs = listOf("张三说：“来了。”")
            )
        )
    }

    @Test
    fun `空书标识或空章节不触发`() {
        assertFalse(
            OfflineCharacterDiscovery.shouldDiscoverAtChapterStart(
                builtinActive = true,
                bookKey = "   ",
                paragraphs = listOf("张三说：“来了。”")
            )
        )
        assertFalse(
            OfflineCharacterDiscovery.shouldDiscoverAtChapterStart(
                builtinActive = true,
                bookKey = "work:test/book",
                paragraphs = emptyList()
            )
        )
    }
}
