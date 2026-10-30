package io.legado.app.service

import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 朗读服务角色绑定口径（G-01 配对 TTSReadAloudService 改动）：
 * 角色库/离线归因必须用 BookIdentity 键，模板偏好键才是 raw bookUrl —— 两者混用会让角色查不到。
 */
class TTSReadAloudServiceTest {

    @Test
    fun `角色绑定键为 work 身份键而非 raw bookUrl`() {
        val book = Book(
            bookUrl = "file:///storage/emulated/0/Download/duo.txt",
            name = "duo-role-test",
            author = "test"
        )
        assertEquals("work:test/duo-role-test", readAloudRoleBookKey(book))
        assertNotEquals(book.bookUrl, readAloudRoleBookKey(book))
    }

    @Test
    fun `无名无作者时回落 bookUrl`() {
        val book = Book(bookUrl = "file:///x", name = "", author = "")
        assertEquals("file:///x", readAloudRoleBookKey(book))
    }

    @Test
    fun `空书返回空键`() {
        assertEquals("", readAloudRoleBookKey(null))
    }

    @Test
    fun `旁白音色优先命中本书 narrator 否则回落首音`() {
        val voices = listOf(
            "b6_m_teen|内置·男·少年" to "内置·男·少年",
            "b6_f_youth|内置·女·青年" to "内置·女·青年"
        )
        assertEquals(
            "b6_f_youth|内置·女·青年" to "内置·女·青年",
            pickNarratorVoice(voices, "内置·女·青年")
        )
        // narrator 未配置 / 配了但表里没有 → 一律回落首音，保证每段都显式 setVoice（防音色泄漏）
        assertEquals("b6_m_teen|内置·男·少年" to "内置·男·少年", pickNarratorVoice(voices, null))
        assertEquals("b6_m_teen|内置·男·少年" to "内置·男·少年", pickNarratorVoice(voices, "未知音色"))
        assertNull(pickNarratorVoice(emptyList(), "内置·男·少年"))
    }

    @Test
    fun `角色徽章标签：角色名优先，旁白段回落旁白，未知留空`() {
        assertEquals("张三", readAloudRoleLabel("dialogue", "张三"))
        assertEquals("旁白", readAloudRoleLabel("narration", ""))
        // 未识别对白不冒充当旁白（AD-15 不猜）
        assertEquals("", readAloudRoleLabel("dialogue", ""))
    }

    @Test
    fun `角色标签写入徽章展示态`() {
        io.legado.app.help.readaloud.ReadAloudRoleBadge.update(readAloudRoleLabel("dialogue", "张三"))
        assertEquals("张三", io.legado.app.help.readaloud.ReadAloudRoleBadge.state.value)
        io.legado.app.help.readaloud.ReadAloudRoleBadge.clear()
        assertEquals("", io.legado.app.help.readaloud.ReadAloudRoleBadge.state.value)
    }
}
