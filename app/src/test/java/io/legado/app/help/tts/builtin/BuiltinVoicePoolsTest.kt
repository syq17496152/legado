package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 声音池（T8.16）：tag→池 自动聚合并幂等回写 groupId。
 */
class BuiltinVoicePoolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun voice(id: String, tags: List<String>, createdAt: Long = 0) = BuiltinVoice(
        id = id,
        name = id,
        refAudio = "$id/prompt.wav",
        tags = tags,
        createdAt = createdAt
    )

    @Test
    fun `首个命中 tag 即主池且大小写不敏感`() {
        assertEquals("narration", BuiltinVoicePools.poolOf(voice("a", listOf("narration"))))
        assertEquals("youth", BuiltinVoicePools.poolOf(voice("b", listOf("Youth", "male"))))
        assertEquals("female", BuiltinVoicePools.poolOf(voice("c", listOf("female"))))
        assertEquals("", BuiltinVoicePools.poolOf(voice("d", listOf("zip", "self"))))
    }

    @Test
    fun `按池聚合且成员按创建时间稳定`() {
        val voices = listOf(
            voice("m1", listOf("male", "youth"), createdAt = 3),
            voice("m2", listOf("male", "youth"), createdAt = 1),
            voice("n1", listOf("旁白"), createdAt = 2)
        )
        val groups = BuiltinVoicePools.groups(voices)
        assertTrue(groups.containsKey("youth"))
        assertTrue(groups.containsKey("旁白"))
        assertEquals(listOf("m2", "m1"), groups.getValue("youth").map { it.id })
    }

    @Test
    fun `同步写回 groupId 且重复执行零更新`() {
        val store = BuiltinVoiceStore(temp.newFolder("pool-store"))
        store.addVoice(
            name = "旁白男",
            pcmWavBytes = PcmAudioUtils.wavBytes(ByteArray(64) { 5 }, 16000),
            refText = "稿",
            tags = listOf("旁白", "male"),
            origin = BuiltinVoice.ORIGIN_ZIP,
            profileId = "clone",
            id = "v1"
        )
        assertEquals(1, BuiltinVoicePools.sync(store))
        assertEquals("旁白", store.findById("v1")?.groupId)
        // 幂等：再次同步不再写盘
        assertEquals(0, BuiltinVoicePools.sync(store))
    }
}
