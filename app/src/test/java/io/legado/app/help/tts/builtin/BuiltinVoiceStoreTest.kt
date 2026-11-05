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

    /**
     * 2026-11-05 2.6：多说话人档按 sid 播种内置音色。
     * Kokoro 中文音色从 sid=3 起（0/1/2 是其它语种）⇒ sidStart 必须生效，且性别取自档案区间。
     */
    @Test
    fun `多音色播种从指定 sid 起并按区间标性别`() {
        val store = BuiltinVoiceStore(temp.newFolder("speakers"))
        val added = store.ensureSpeakers(
            profileId = "hq",
            engine = BuiltinModelProfile.ENGINE_KOKORO,
            count = 100,
            namePrefix = "珂珂",
            sidStart = 3,
            genderOf = { sid -> if (sid in 3..57) "女" else if (sid in 58..102) "男" else "" }
        )
        assertEquals(100, added)
        val voices = store.list().filter { it.profileId == "hq" }
        assertEquals(100, voices.size)
        assertEquals((3..102).toList(), voices.map { it.sid }.sorted())
        assertEquals("女", voices.first { it.sid == 3 }.gender)
        assertEquals("男", voices.first { it.sid == 102 }.gender)
        assertEquals(BuiltinModelProfile.ENGINE_KOKORO, voices.first().engineOrDefault())
        // 幂等：再播一次不新增
        assertEquals(0, store.ensureSpeakers(
            profileId = "hq", engine = BuiltinModelProfile.ENGINE_KOKORO,
            count = 100, namePrefix = "珂珂", sidStart = 3))
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
    fun `多说话人档播种 sid 音色且幂等`() {
        val store = BuiltinVoiceStore(temp.newFolder("speakers"))
        val first = store.ensureSpeakers("multi", BuiltinModelProfile.ENGINE_VITS, 3, "音色")
        assertEquals(3, first)
        val seeded = store.list()
        assertEquals(listOf(1, 2, 3), seeded.map { it.sid }.sorted())
        assertTrue(seeded.all { it.profileId == "multi" })
        assertTrue(seeded.all { it.engine == BuiltinModelProfile.ENGINE_VITS })
        assertTrue(seeded.all { it.readOnly })
        // 第二次播种不再新增（用户可能已手工改过名称，不得重复写入）
        assertEquals(0, store.ensureSpeakers("multi", BuiltinModelProfile.ENGINE_VITS, 3, "音色"))
        assertEquals(3, store.list().size)
    }

    @Test
    fun `多说话人档扩容只补缺失 sid`() {
        val store = BuiltinVoiceStore(temp.newFolder("speakers-grow"))
        assertEquals(2, store.ensureSpeakers("multi", BuiltinModelProfile.ENGINE_VITS, 2, "音色"))
        assertEquals(1, store.ensureSpeakers("multi", BuiltinModelProfile.ENGINE_VITS, 3, "音色"))
        assertEquals(listOf(1, 2, 3), store.list().map { it.sid }.sorted())
    }

    @Test
    fun `损坏索引自愈为空索引`() {
        val root = temp.newFolder("broken")
        java.io.File(root, "index.json").writeText("{bad-json")
        val store = BuiltinVoiceStore(root)
        assertTrue(store.list().isEmpty())
    }
}
