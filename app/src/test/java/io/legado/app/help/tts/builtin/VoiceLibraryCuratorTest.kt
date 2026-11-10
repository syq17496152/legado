package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 音色库整理（2026-11-10 用户裁定）：值域归一 + 去重 + 归档分组。
 *
 * 现场：K100 真机 282 条里 274 条是无语义序号名，性别/年龄中英混存，
 * 且重复播种/重复导入没有任何收口 ⇒ 列表「乱七八糟」。
 */
class VoiceLibraryCuratorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun wav(seed: Int): ByteArray = PcmAudioUtils.wavBytes(ByteArray(64) { seed.toByte() }, 16000)

    private fun preset(id: String, sid: Int, gender: String, createdAt: Long) = BuiltinVoice(
        id = id,
        name = "珂珂 $sid",
        origin = BuiltinVoice.ORIGIN_PRESET,
        profileId = "hq",
        engine = BuiltinModelProfile.ENGINE_KOKORO,
        gender = gender,
        readOnly = true,
        sid = sid,
        createdAt = createdAt
    )

    @Test
    fun `归一 中文性别年龄转规范值且未定清空`() {
        val voice = BuiltinVoice(
            id = "v1",
            name = "艾舍尔 7",
            gender = "女",
            age = "中年",
            tags = listOf("女", "中年", "未定")
        )
        val normalized = VoiceLibraryCurator.normalize(voice)
        assertEquals("female", normalized.gender)
        assertEquals("middle", normalized.age)
        assertEquals(listOf("female", "middle"), normalized.tags)
    }

    @Test
    fun `预设同档同 sid 重复只保留最早一条`() {
        val store = BuiltinVoiceStore(temp.newFolder("voices"))
        store.upsertAll(
            listOf(
                preset("hq-s5", sid = 5, gender = "female", createdAt = 200),
                preset("hq-s5-old", sid = 5, gender = "female", createdAt = 100),
                preset("hq-s6", sid = 6, gender = "male", createdAt = 300)
            )
        )
        val report = VoiceLibraryCurator.curate(store)
        assertEquals(2, report.total)
        assertEquals(1, report.removedDuplicates)
        assertEquals("hq-s5-old", store.list().first { it.sid == 5 }.id)
    }

    @Test
    fun `同音频同名的导入音色去重 同音频异名只统计不删`() {
        val store = BuiltinVoiceStore(temp.newFolder("voices"))
        // 两段完全相同的音频、同名 ⇒ 应被合并
        store.addVoice(
            name = "温柔女声", pcmWavBytes = wav(1), refText = "稿",
            tags = listOf("温柔"), origin = BuiltinVoice.ORIGIN_ZIP,
            profileId = "clone", gender = "女", id = "a1"
        )
        store.addVoice(
            name = "温柔女声", pcmWavBytes = wav(1), refText = "稿",
            tags = listOf("温柔"), origin = BuiltinVoice.ORIGIN_ZIP,
            profileId = "clone", gender = "女", id = "a2"
        )
        // 同一段音频但改了名 ⇒ 视为用户别名，保留
        store.addVoice(
            name = "温柔女声·别名", pcmWavBytes = wav(1), refText = "稿",
            tags = listOf("温柔"), origin = BuiltinVoice.ORIGIN_ZIP,
            profileId = "clone", gender = "女", id = "a3"
        )
        val report = VoiceLibraryCurator.curate(store)
        assertEquals(2, report.total)
        assertEquals(1, report.removedDuplicates)
        assertEquals(1, report.suspiciousDuplicates)
        assertTrue(store.list().none { it.id == "a2" })
        assertTrue(store.list().any { it.id == "a3" })
    }

    @Test
    fun `归档分组 本档可用在前 女先于男 年龄按档排序`() {
        val voices = listOf(
            preset("hq-s9", sid = 9, gender = "male", createdAt = 1),
            preset("hq-s3", sid = 3, gender = "female", createdAt = 2),
            BuiltinVoice(
                id = "multi-s1", name = "艾舍尔 1", origin = BuiltinVoice.ORIGIN_PRESET,
                profileId = "multi", readOnly = true, sid = 1, createdAt = 3
            )
        )
        val sections = VoiceLibraryCurator.archive(voices, activeProfileId = "hq")
        // 本档可用（hq 的两条）在前；第三条是其他档位
        assertTrue(sections.first().title.startsWith("本档可用"))
        assertTrue(sections.last().title.startsWith("其他档位"))
        // 女声组排在同档男声之前
        val usableTitles = sections.filter { it.title.startsWith("本档可用") }.map { it.title }
        assertEquals("本档可用 · 女声 · 未标年龄（1）", usableTitles[0])
        assertEquals("本档可用 · 男声 · 未标年龄（1）", usableTitles[1])
    }

    @Test
    fun `归档分组 无当前档时只按性别年龄分区`() {
        val voices = listOf(
            BuiltinVoice(id = "v1", name = "A", gender = "female", age = "youth"),
            BuiltinVoice(id = "v2", name = "B", gender = "male", age = "aged")
        )
        val sections = VoiceLibraryCurator.archive(voices, activeProfileId = null)
        assertEquals(2, sections.size)
        assertEquals("女声 · 青年（1）", sections[0].title)
        assertEquals("男声 · 老年（1）", sections[1].title)
    }

    /** 序号名必须按数值排（字符串排序会把 `艾舍尔 10` 提到 `艾舍尔 2` 前面） */
    @Test
    fun `序号名自然排序`() {
        val list = listOf("艾舍尔 10", "艾舍尔 2", "艾舍尔 1")
        assertEquals(
            listOf("艾舍尔 1", "艾舍尔 2", "艾舍尔 10"),
            list.sortedWith { a, b -> VoiceLibraryCurator.naturalCompare(a, b) }
        )
        // 去重基名：机器消歧后缀不算用户命名
        assertEquals("温柔女声", VoiceLibraryCurator.baseName("温柔女声_2"))
        assertEquals("旁白", VoiceLibraryCurator.baseName("旁白"))
    }
}
