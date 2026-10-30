package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 音色库检索（T6.4）：搜索命中（名称/标签/年龄）+ 性别 + 未打标 + 来源筛选。
 */
class BuiltinVoiceFilterTest {

    private fun voice(
        id: String,
        name: String,
        gender: String = "",
        age: String = "",
        tags: List<String> = emptyList(),
        origin: String = BuiltinVoice.ORIGIN_ZIP
    ) = BuiltinVoice(id = id, name = name, gender = gender, age = age, tags = tags, origin = origin)

    private val voices = listOf(
        voice("a", "旁白男", gender = "男", age = "青年", tags = listOf("旁白")),
        voice("b", "清冷女声", gender = "female", age = "少年", tags = listOf("女声")),
        voice("c", "未标音色", gender = "", age = "", tags = emptyList(), origin = BuiltinVoice.ORIGIN_SELF),
        voice("d", "内置·男·老年", gender = "male", age = "老年", origin = BuiltinVoice.ORIGIN_PRESET)
    )

    @Test
    fun `空条件返回全部`() {
        assertEquals(voices.size, BuiltinVoiceFilter.apply(voices, BuiltinVoiceFilter.Criteria()).size)
    }

    @Test
    fun `按名称或标签搜索`() {
        val byName = BuiltinVoiceFilter.apply(voices, BuiltinVoiceFilter.Criteria(query = "旁白"))
        assertEquals(listOf("a"), byName.map { it.id })
        val byTag = BuiltinVoiceFilter.apply(voices, BuiltinVoiceFilter.Criteria(query = "女声"))
        assertEquals(listOf("b"), byTag.map { it.id })
        val byAge = BuiltinVoiceFilter.apply(voices, BuiltinVoiceFilter.Criteria(query = "老年"))
        assertEquals(listOf("d"), byAge.map { it.id })
    }

    @Test
    fun `性别筛选兼容中英文取值`() {
        assertEquals(
            listOf("a", "d"),
            BuiltinVoiceFilter.apply(voices, BuiltinVoiceFilter.Criteria(gender = BuiltinVoiceFilter.Gender.MALE))
                .map { it.id }
        )
        assertEquals(
            listOf("b"),
            BuiltinVoiceFilter.apply(voices, BuiltinVoiceFilter.Criteria(gender = BuiltinVoiceFilter.Gender.FEMALE))
                .map { it.id }
        )
        assertEquals(
            listOf("c"),
            BuiltinVoiceFilter.apply(voices, BuiltinVoiceFilter.Criteria(gender = BuiltinVoiceFilter.Gender.UNKNOWN))
                .map { it.id }
        )
    }

    @Test
    fun `未打标与来源筛选可叠加`() {
        val untaggedSelf = BuiltinVoiceFilter.apply(
            voices,
            BuiltinVoiceFilter.Criteria(untaggedOnly = true, origin = BuiltinVoice.ORIGIN_SELF)
        )
        assertEquals(listOf("c"), untaggedSelf.map { it.id })
    }

    @Test
    fun `按声音池筛选 T8-16`() {
        val withPool = voices.map { voice ->
            voice.copy(groupId = if (voice.id == "a" || voice.id == "d") "male" else "")
        }
        val males = BuiltinVoiceFilter.apply(
            withPool,
            BuiltinVoiceFilter.Criteria(poolId = "male")
        )
        assertEquals(listOf("a", "d"), males.map { it.id })
    }

    @Test
    fun `摘要文案区分是否过滤`() {
        assertEquals("4 个音色", BuiltinVoiceFilter.summary(4, 4))
        assertEquals("显示 2 / 共 4 个音色", BuiltinVoiceFilter.summary(4, 2))
    }
}
