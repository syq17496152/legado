package io.legado.app.help.readaloud.casting

import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.tts.builtin.BuiltinVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配声算法（T5.4）：性别优先、同书稳定、撞音避让、旁白优先 narration 标签。
 */
class CharacterVoiceAssignServiceTest {

    private fun character(id: Long, name: String, gender: String, level: Int = BookCharacter.ROLE_NORMAL) =
        BookCharacter(id = id, name = name, gender = gender, roleLevel = level, identity = "")

    private fun voice(id: String, gender: String, age: String, tags: List<String> = emptyList()) =
        BuiltinVoice(
            id = id,
            name = "内置·${if (gender == "male") "男" else "女"}·$age",
            refAudio = "$id/prompt.wav",
            refText = "稿",
            tags = tags,
            origin = BuiltinVoice.ORIGIN_PRESET,
            gender = gender,
            age = age,
            readOnly = true
        )

    private val voices = listOf(
        voice("b6_m_teen", "male", "teen", listOf("male", "teen", "narration")),
        voice("b6_m_youth", "male", "youth"),
        voice("b6_f_youth", "female", "youth", listOf("female", "youth", "narration")),
        voice("b6_f_aged", "female", "aged")
    )

    @Test
    fun `性别一致优先且不撞音`() {
        val result = CharacterVoiceAssignService.plan(
            characters = listOf(
                character(1, "林凡", BookCharacter.GENDER_MALE, BookCharacter.ROLE_MAIN),
                character(2, "苏婉", BookCharacter.GENDER_FEMALE, BookCharacter.ROLE_MAIN),
                character(3, "王猛", BookCharacter.GENDER_MALE)
            ),
            voices = voices
        )
        assertEquals(3, result.assignments.size)
        val byName = result.assignments.associateBy { it.characterName }
        assertTrue(byName.getValue("林凡").voiceName.contains("男"))
        assertTrue(byName.getValue("苏婉").voiceName.contains("女"))
        // 三名角色分配到的音色互不相同（撞音避让）
        assertEquals(3, result.assignments.map { it.voiceName }.distinct().size)
    }

    @Test
    fun `旁白优先 narration 标签`() {
        val result = CharacterVoiceAssignService.plan(
            characters = listOf(character(1, "林凡", BookCharacter.GENDER_MALE)),
            voices = voices
        )
        assertTrue(result.narratorVoice.isNotBlank())
        assertTrue(result.narratorVoice.contains("|"))
    }

    @Test
    fun `角色多于音色时允许撞音并标记冲突`() {
        val onlyOne = listOf(voices.first { it.gender == "male" })
        val result = CharacterVoiceAssignService.plan(
            characters = listOf(
                character(1, "甲", BookCharacter.GENDER_MALE),
                character(2, "乙", BookCharacter.GENDER_MALE)
            ),
            voices = onlyOne
        )
        assertEquals(2, result.assignments.size)
        assertTrue(result.conflicts.isNotEmpty())
    }

    @Test
    fun `无音色返回空且不崩溃`() {
        val result = CharacterVoiceAssignService.plan(
            characters = listOf(character(1, "林凡", BookCharacter.GENDER_MALE)),
            voices = emptyList()
        )
        assertTrue(result.assignments.isEmpty())
        assertFalse(result.conflicts.isNotEmpty())
    }

    @Test
    fun `固定绑定优先于性别年龄打分 T8-17`() {
        // 张三为男，但固定绑定指定女声音色 → 必须尊重固定绑定（用户手改过）
        val female = voices.first { it.gender == "female" }
        val result = CharacterVoiceAssignService.plan(
            characters = listOf(character(1, "张三", BookCharacter.GENDER_MALE)),
            voices = voices,
            fixedVoiceMap = mapOf("张三" to female.name)
        )
        assertEquals(1, result.assignments.size)
        assertEquals(female.voiceName, result.assignments.first().voiceName)
        assertEquals("固定绑定", result.assignments.first().reason)
    }

    @Test
    fun `固定绑定值为不存在的音色时回落到打分`() {
        val result = CharacterVoiceAssignService.plan(
            characters = listOf(character(1, "张三", BookCharacter.GENDER_MALE)),
            voices = voices,
            fixedVoiceMap = mapOf("张三" to "不存在的音色")
        )
        assertEquals(1, result.assignments.size)
        assertTrue(result.assignments.first().reason != "固定绑定")
    }

    @Test
    fun `角色配音行是否已配声只看音色字段`() {
        assertTrue(CharacterVoiceAssignService.RoleVoiceRow(1, "张三", "male", 0, "a|男", "男").configured)
        assertTrue(CharacterVoiceAssignService.RoleVoiceRow(2, "李四", "male", 0, "", "旁白男").configured)
        assertFalse(CharacterVoiceAssignService.RoleVoiceRow(3, "王五", "", 0, "", "").configured)
    }

    @Test
    fun `角色配音页撞音判定只针对重复绑定`() {
        val rows = listOf(
            CharacterVoiceAssignService.RoleVoiceRow(1, "张三", "male", 0, "a|男", "男"),
            CharacterVoiceAssignService.RoleVoiceRow(2, "李四", "male", 0, "a|男", "男"),
            CharacterVoiceAssignService.RoleVoiceRow(3, "王五", "female", 0, "b|女", "女"),
            CharacterVoiceAssignService.RoleVoiceRow(4, "旁白", "", 0, "", "")
        )
        assertEquals(setOf("a|男"), CharacterVoiceAssignService.conflictingVoices(rows))
        // 空音色不参与撞音（未配声的角色不应被标红）
        assertFalse(CharacterVoiceAssignService.conflictingVoices(rows).contains(""))
    }
}
