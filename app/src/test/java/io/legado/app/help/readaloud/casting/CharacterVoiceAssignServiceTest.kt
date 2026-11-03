package io.legado.app.help.readaloud.casting

import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.readaloud.speech.SpeechRoute
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

    private fun voice(
        id: String,
        gender: String,
        age: String,
        tags: List<String> = emptyList(),
        name: String = "内置·${if (gender == "male") "男" else "女"}·$age"
    ) =
        BuiltinVoice(
            id = id,
            name = name,
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
    fun `主角关键词优先挑音色 T8-13`() {
        // 旁白先占用 narration 男声；剩下"男主"角色应先挑到匹配的男声，而不是让 roleLevel 更高的配角先拿
        val result = CharacterVoiceAssignService.plan(
            characters = listOf(
                character(1, "张三", BookCharacter.GENDER_MALE, BookCharacter.ROLE_MAIN),
                character(2, "男主·陆沉", BookCharacter.GENDER_MALE)
            ),
            voices = voices
        )
        val byName = result.assignments.associateBy { it.characterName }
        assertEquals(listOf("男主·陆沉", "张三"), result.assignments.map { it.characterName })
        assertTrue(byName.getValue("男主·陆沉").voiceName.contains("男"))
        assertFalse(byName.getValue("男主·陆沉").conflict)
        // 撞音避让：配角不得复用主角刚拿到的音色
        assertTrue(byName.getValue("张三").voiceName != byName.getValue("男主·陆沉").voiceName)
    }

    /** 导入包（506 音色）常见：gender 写英文、名字里没有「男/女」⇒ 也必须按性别分配 */
    @Test
    fun `英文性别元数据下仍按性别分配`() {
        val imported = listOf(
            voice("v_narr", "male", "youth", listOf("narration"), name = "旁白"),
            voice("v_m1", "male", "youth", name = "低沉"),
            voice("v_f1", "female", "youth", name = "清亮"),
            voice("v_f2", "female", "aged", name = "温柔")
        )
        val result = CharacterVoiceAssignService.plan(
            characters = listOf(
                character(1, "林凡", BookCharacter.GENDER_MALE, BookCharacter.ROLE_MAIN),
                character(2, "苏婉", BookCharacter.GENDER_FEMALE, BookCharacter.ROLE_MAIN)
            ),
            voices = imported
        )
        val byName = result.assignments.associateBy { it.characterName }
        assertEquals("v_m1|低沉", byName.getValue("林凡").voiceName)
        assertEquals("v_f1|清亮", byName.getValue("苏婉").voiceName)
        assertTrue(byName.getValue("苏婉").reason.contains("性别一致"))
    }

    /** 音色元数据归一化：中英文与 tags 统一到 male/female + teen/youth/middle/aged */
    @Test
    fun `音色性别年龄归一化兼容中英文与标签`() {
        assertEquals("male", CharacterVoiceAssignService.voiceTraitsOf(voice("a", "male", "youth")).gender)
        assertEquals("female", CharacterVoiceAssignService.voiceTraitsOf(voice("b", "Female", "")).gender)
        assertEquals("male", CharacterVoiceAssignService.voiceTraitsOf(voice("c", "", "", listOf("male"), name = "嗓音")).gender)
        assertEquals("female", CharacterVoiceAssignService.voiceTraitsOf(voice("d", "女声", "")).gender)
        assertEquals("teen", CharacterVoiceAssignService.voiceTraitsOf(voice("e", "male", "少年")).age)
        assertEquals("youth", CharacterVoiceAssignService.voiceTraitsOf(voice("f", "female", "青年")).age)
        assertEquals("middle", CharacterVoiceAssignService.voiceTraitsOf(voice("g", "male", "中年")).age)
        assertEquals("aged", CharacterVoiceAssignService.voiceTraitsOf(voice("h", "female", "老年")).age)
    }

    /** 年龄明确冲突要扣分：老年角色不得优先拿到少年音色（即使性别相同） */
    @Test
    fun `年龄冲突扣分避免老年角色配少年音`() {
        val agedVoice = voice("v_aged", "male", "老年", name = "苍老")
        val teenVoice = voice("v_teen", "male", "少年", name = "清越")
        val result = CharacterVoiceAssignService.plan(
            characters = listOf(character(1, "老者", BookCharacter.GENDER_MALE)),
            voices = listOf(teenVoice, agedVoice)
        )
        assertEquals(1, result.assignments.size)
        assertEquals("v_aged|苍老", result.assignments.first().voiceName)
    }

    /** 用户手动点「重新配声」必须重算既有绑定，否则算法升级对老用户永不生效 */
    @Test
    fun `手动重新配声必须覆盖既有绑定`() {
        assertTrue(CharacterVoiceAssignService.manualAssignOverwrites())
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

    /** 气质归一化：角色性格与音色标签共用同一套分组，才能做"性格契合" */
    @Test
    fun `气质关键词归一化覆盖中英文与标签`() {
        assertEquals(setOf("cool"), CharacterVoiceAssignService.moodsFromText("清冷孤傲"))
        assertTrue(CharacterVoiceAssignService.moodsFromText("温柔体贴").contains("gentle"))
        assertTrue(CharacterVoiceAssignService.moodsFromText("沉稳冷静").contains("calm"))
        assertTrue(CharacterVoiceAssignService.moodsFromText("").isEmpty())
    }

    /** 性别年龄相同时，用性格气质区分：清冷角色优先拿清冷音色，而不是排在前面的普通音色 */
    @Test
    fun `性格契合优先于同性别同年龄音色`() {
        val narratorVoice = voice(
            "v_narr", "female", "youth", listOf("female", "youth", "narration"), name = "旁白"
        )
        val coldVoice = voice(
            "v_cold", "female", "youth", listOf("female", "youth", "清冷"), name = "冷月"
        )
        val plainVoice = voice(
            "v_plain", "female", "youth", listOf("female", "youth"), name = "清音"
        )
        val character = BookCharacter(
            id = 1,
            name = "苏婉",
            gender = BookCharacter.GENDER_FEMALE,
            roleLevel = BookCharacter.ROLE_MAIN,
            personality = "清冷孤傲，言语淡漠"
        )
        val result = CharacterVoiceAssignService.plan(
            characters = listOf(character),
            voices = listOf(narratorVoice, plainVoice, coldVoice)
        )
        assertEquals(1, result.assignments.size)
        // 旁白占用 narration 音色后，角色应在"普通女声"与"清冷女声"中选清冷那个
        assertEquals("v_cold|冷月", result.assignments.first().voiceName)
        assertTrue(result.assignments.first().reason.contains("性格契合"))
    }

    /** 性格 → 年龄倾向兜底：导入包缺气质标签时，至少让「性格」对年龄档位有弱影响 */
    @Test
    fun `性格倾向映射到年龄档位`() {
        assertEquals("youth", CharacterVoiceAssignService.moodAgeAffinity(setOf("lively")))
        assertEquals("youth", CharacterVoiceAssignService.moodAgeAffinity(setOf("cool")))
        assertEquals("middle", CharacterVoiceAssignService.moodAgeAffinity(setOf("calm")))
        assertEquals("aged", CharacterVoiceAssignService.moodAgeAffinity(setOf("majestic")))
        assertEquals("", CharacterVoiceAssignService.moodAgeAffinity(emptySet()))
    }

    /** 角色年龄未知 + 性格威严 → 偏向老年档音色（同性别同时长优先级不变，仅弱加分区分） */
    @Test
    fun `年龄未知时性格威严偏向成熟音色`() {
        val narrator = voice(
            "v_narr", "male", "youth", listOf("male", "youth", "narration"), name = "旁白"
        )
        val youthVoice = voice("v_y", "male", "youth", name = "清越")
        val agedVoice = voice("v_a", "male", "aged", name = "苍劲")
        val character = BookCharacter(
            id = 1,
            name = "陆沉",
            gender = BookCharacter.GENDER_MALE,
            roleLevel = BookCharacter.ROLE_MAIN,
            personality = "威严庄重"
        )
        val result = CharacterVoiceAssignService.plan(
            characters = listOf(character),
            voices = listOf(narrator, youthVoice, agedVoice)
        )
        assertEquals(1, result.assignments.size)
        assertEquals("v_a|苍劲", result.assignments.first().voiceName)
    }

    /** 未绑定判定：空 JSON 与 toneID 为空都算未绑定（播放期会回落旁白音） */
    @Test
    fun `未绑定音色判定覆盖空路由与空音色`() {
        assertTrue(CharacterVoiceAssignService.needsAutoVoice(""))
        val blankRoute = SpeechRoute(
            engineType = SpeechRoute.ENGINE_SYSTEM, engineValue = "pkg",
            speakerName = "", toneID = "", source = SpeechRoute.SOURCE_AUTO
        )
        assertTrue(CharacterVoiceAssignService.needsAutoVoice(blankRoute.toJson()))
        val boundRoute = SpeechRoute(
            engineType = SpeechRoute.ENGINE_SYSTEM, engineValue = "pkg",
            speakerName = "音色A", toneID = "v_a|音色A", source = SpeechRoute.SOURCE_AUTO
        )
        assertFalse(CharacterVoiceAssignService.needsAutoVoice(boundRoute.toJson()))
    }

    /** 自动补声不得抢走已绑定角色占用的音色（否则老角色下次开播又换声） */
    @Test
    fun `自动补声避开已占用音色`() {
        val taken = voice("v_taken", "male", "youth", listOf("male", "youth"), name = "已占用")
        val free = voice("v_free", "male", "youth", listOf("male", "youth"), name = "空闲")
        val result = CharacterVoiceAssignService.plan(
            characters = listOf(character(1, "陆沉", BookCharacter.GENDER_MALE)),
            voices = listOf(taken, free),
            reservedVoiceIds = setOf(taken.voiceName)
        )
        assertEquals(1, result.assignments.size)
        assertEquals(free.voiceName, result.assignments.first().voiceName)
    }

    /** 5.2 性别硬约束：男角色即使男声已用完，也**绝不**退到女声（宁可撞音，也不配错性别） */
    @Test
    fun `同性音色不足时撞音也不配异性`() {
        val maleOnly = listOf(
            voice("m1", "male", "youth", name = "唯一男声"),
            voice("f1", "female", "youth", name = "女声甲"),
            voice("f2", "female", "aged", name = "女声乙")
        )
        val result = CharacterVoiceAssignService.plan(
            characters = listOf(
                character(1, "林凡", BookCharacter.GENDER_MALE),
                character(2, "王猛", BookCharacter.GENDER_MALE)
            ),
            voices = maleOnly
        )
        assertEquals(2, result.assignments.size)
        // 两人都必须是男声（第二个被迫撞音）
        assertTrue(result.assignments.all { it.voiceName.contains("唯一男声") })
        assertTrue(result.assignments.any { it.conflict })
    }

    /** 库里完全没有同性音色时才允许回退，并且必须把回退写进 reason（便于用户理解为什么配了异性音） */
    @Test
    fun `同性音色为空时回退并在原因里标注`() {
        val result = CharacterVoiceAssignService.plan(
            characters = listOf(character(1, "林凡", BookCharacter.GENDER_MALE)),
            voices = listOf(voice("f1", "female", "youth", name = "女声甲"))
        )
        assertEquals(1, result.assignments.size)
        assertTrue(result.assignments.first().voiceName.contains("女声甲"))
        assertTrue(result.assignments.first().reason.contains("同性音色已用尽"))
    }

    /** 性别未知的音色不受硬约束影响（无法证明是异性，仍可参与，避免库标签缺失时无人可配） */
    @Test
    fun `性别未知音色仍可参与配声`() {
        val unknown = BuiltinVoice(
            id = "u1", name = "未标注音色", refAudio = "u1/prompt.wav", refText = "稿",
            origin = BuiltinVoice.ORIGIN_PRESET, readOnly = true
        )
        val result = CharacterVoiceAssignService.plan(
            characters = listOf(character(1, "林凡", BookCharacter.GENDER_MALE)),
            voices = listOf(unknown)
        )
        assertEquals(1, result.assignments.size)
        assertEquals(unknown.voiceName, result.assignments.first().voiceName)
    }

    /** 分池标签必须与角色定位一致（报告/排查依赖它） */
    @Test
    fun `分池按主角配角龙套标注`() {
        val result = CharacterVoiceAssignService.plan(
            characters = listOf(
                character(1, "林凡", BookCharacter.GENDER_MALE, BookCharacter.ROLE_MAIN),
                character(2, "王猛", BookCharacter.GENDER_MALE, BookCharacter.ROLE_IMPORTANT),
                character(3, "路人甲", BookCharacter.GENDER_MALE, BookCharacter.ROLE_NORMAL)
            ),
            voices = listOf(
                voice("m1", "male", "youth", name = "男声一"),
                voice("m2", "male", "aged", name = "男声二"),
                voice("m3", "male", "teen", name = "男声三")
            )
        )
        val byName = result.assignments.associateBy { it.characterName }
        assertEquals(CharacterVoiceAssignService.POOL_LEAD, byName.getValue("林凡").pool)
        assertEquals(CharacterVoiceAssignService.POOL_SUPPORT, byName.getValue("王猛").pool)
        assertEquals(CharacterVoiceAssignService.POOL_EXTRA, byName.getValue("路人甲").pool)
    }
}
