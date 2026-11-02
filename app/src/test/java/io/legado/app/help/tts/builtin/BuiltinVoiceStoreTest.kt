package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 音色索引（AD-03 / C-3 / T2.1）：重名消歧、稳定 id、删除连带文件、只读内置不可删。
 */
class BuiltinVoiceStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun wav(): ByteArray = PcmAudioUtils.wavBytes(ByteArray(64), 16000)

    @Test
    fun `新增音色可查询且绑定键为 id竖线显示名`() {
        val store = BuiltinVoiceStore(temp.newFolder("voices"))
        val voice = store.addVoice(
            name = "旁白·沉稳男",
            pcmWavBytes = wav(),
            refText = "逐字稿",
            tags = listOf("male"),
            origin = BuiltinVoice.ORIGIN_ZIP,
            profileId = "clone"
        )
        assertNotNull(voice)
        val loaded = store.findById(voice!!.id)
        assertNotNull(loaded)
        assertEquals("旁白·沉稳男", loaded!!.displayName)
        assertTrue(loaded.voiceName.startsWith(loaded.id + "|"))
        assertTrue(store.exists(loaded))
    }

    @Test
    fun `重名自动消歧`() {
        val store = BuiltinVoiceStore(temp.newFolder("dup"))
        val first = store.addVoice("同名", wav(), refText = "稿", tags = emptyList(),
            origin = BuiltinVoice.ORIGIN_ZIP, profileId = "clone")!!
        val second = store.addVoice("同名", wav(), refText = "稿2", tags = emptyList(),
            origin = BuiltinVoice.ORIGIN_ZIP, profileId = "clone")!!
        assertEquals("同名", first.name)
        assertEquals("同名_2", second.name)
    }

    @Test
    fun `删除音色连带清理音频目录`() {
        val store = BuiltinVoiceStore(temp.newFolder("del"))
        val voice = store.addVoice("临时", wav(), refText = "稿", tags = emptyList(),
            origin = BuiltinVoice.ORIGIN_SELF, profileId = "clone")!!
        assertTrue(store.delete(voice.id))
        assertNull(store.findById(voice.id))
        assertTrue(!store.exists(voice))
    }

    @Test
    fun `内置只读音色禁止删除`() {
        val store = BuiltinVoiceStore(temp.newFolder("ro"))
        val voice = store.addVoice("内置", wav(), refText = "稿", tags = emptyList(),
            origin = BuiltinVoice.ORIGIN_PRESET, profileId = "clone", readOnly = true)!!
        assertTrue(!store.delete(voice.id))
    }

    @Test
    fun `批量入库只写一次索引且批量内重名按顺序消歧`() {
        val store = BuiltinVoiceStore(temp.newFolder("batch"))
        val prepared = listOf("同名", "同名", "另一").mapIndexed { index, name ->
            store.prepareVoice(
                name = name,
                pcmWavBytes = wav(),
                refText = "稿$index",
                tags = emptyList(),
                origin = BuiltinVoice.ORIGIN_ZIP,
                profileId = "clone",
                id = "batch-$index"
            )!!
        }
        // prepareVoice 只落音频、不写索引
        assertTrue(store.list().isEmpty())
        val stored = store.upsertAll(prepared)
        assertEquals(3, stored.size)
        assertEquals(listOf("同名", "同名_2", "另一"), stored.map { it.name })
        assertEquals(3, store.list().size)
        assertTrue(store.indexFile().isFile)
    }

    @Test
    fun `批量 upsert 按 id 覆盖既有条目`() {
        val store = BuiltinVoiceStore(temp.newFolder("batch-overwrite"))
        val first = store.addVoice("原名", wav(), refText = "稿", tags = emptyList(),
            origin = BuiltinVoice.ORIGIN_ZIP, profileId = "clone")!!
        val overwritten = store.upsertAll(listOf(first.copy(name = "改名", tags = listOf("male"))))
        assertEquals(1, overwritten.size)
        assertEquals(1, store.list().size)
        assertEquals("改名", store.findById(first.id)?.name)
    }

    @Test
    fun `损坏索引自愈为空索引`() {
        val root = temp.newFolder("broken")
        java.io.File(root, "index.json").writeText("{bad-json")
        val store = BuiltinVoiceStore(root)
        assertTrue(store.list().isEmpty())
    }
}
