package io.legado.app.help.readaloud.role

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 固定绑定（C-12 / AD-15⑤ / T8.14）：CloneTTS 同 schema 读写、书级覆盖、cue 学习。
 */
class BookAliasStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = BookAliasStore(temp.newFolder("rules"))

    @Test
    fun `写入后按角色名可查到音色`() {
        val store = store()
        store.writeBook(
            "book-a",
            FixedRoles(roles = mapOf("张三" to "内置·男·青年"), narrator = "内置·女·青年")
        )
        assertEquals("内置·男·青年", store.voiceFor("book-a", "张三"))
        assertEquals("内置·女·青年", store.narratorVoice("book-a"))
    }

    @Test
    fun `书级覆盖全局`() {
        val store = store()
        store.writeGlobal(FixedRoles(roles = mapOf("张三" to "全局音色")))
        store.writeBook("book-b", FixedRoles(roles = mapOf("张三" to "本书音色")))
        assertEquals("本书音色", store.voiceFor("book-b", "张三"))
        assertEquals("全局音色", store.voiceFor("book-c", "张三"))
    }

    @Test
    fun `CloneTTS 特殊键可解析男女主角音色`() {
        val store = store()
        store.writeBook(
            "book-d",
            FixedRoles(maleLead = "林凡", maleLeadVoice = "男声", femaleLead = "苏婉", femaleLeadVoice = "女声")
        )
        assertEquals("男声", store.voiceFor("book-d", "林凡"))
        assertEquals("女声", store.voiceFor("book-d", "苏婉"))
    }

    @Test
    fun `cue 学习可读可清`() {
        val store = store()
        store.learnCue("book-e", "淡淡道", "李四")
        assertEquals("李四", store.cuesFor("book-e")["淡淡道"])
        store.clearBook("book-e")
        assertTrue(store.cuesFor("book-e").isEmpty())
        assertNull(store.voiceFor("book-e", "李四"))
    }

    @Test
    fun `空文件与坏文件不崩溃`() {
        val file = java.io.File(temp.newFolder("bad"), "fixed_roles.json")
        file.writeText("{bad")
        val store = BookAliasStore(file.parentFile!!)
        assertNotNull(store.readGlobal())
        assertTrue(store.readGlobal().isEmpty)
    }
}
