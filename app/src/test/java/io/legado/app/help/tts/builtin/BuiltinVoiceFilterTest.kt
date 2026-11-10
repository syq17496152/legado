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

    /** 2026-11-10 归档：年龄筛选必须同时兼容中文库值与英文规范值 */
    @Test
    fun `年龄筛选兼容中文与规范值并支持未标注`() {
        val youth = BuiltinVoiceFilter.apply(
            voices,
            BuiltinVoiceFilter.Criteria(age = VoiceTagNormalizer.AGE_YOUTH)
        )
        assertEquals(listOf("a"), youth.map { it.id })
        val aged = BuiltinVoiceFilter.apply(
            voices,
            BuiltinVoiceFilter.Criteria(age = VoiceTagNormalizer.AGE_AGED)
        )
        assertEquals(listOf("d"), aged.map { it.id })
        val unlabeled = BuiltinVoiceFilter.apply(
            voices,
            BuiltinVoiceFilter.Criteria(age = BuiltinVoiceFilter.Criteria.UNLABELED)
        )
        assertEquals(listOf("c"), unlabeled.map { it.id })
    }

    /** 性格归档：音色 tags 里的性格词归一后参与筛选（中英同口径） */
    @Test
    fun `性格筛选按归一后的气质桶匹配`() {
        val withMood = voices.map { voice ->
            voice.copy(tags = if (voice.id == "b") listOf("清冷") else voice.tags)
        }
        val cool = BuiltinVoiceFilter.apply(
            withMood,
            BuiltinVoiceFilter.Criteria(mood = "cool")
        )
        assertEquals(listOf("b"), cool.map { it.id })
        assertEquals(setOf("gentle"), BuiltinVoiceFilter.moodsOf(voice("x", "x", tags = listOf("温柔"))))
    }

    /**
     * 用户实测「内置 6 个音色试听都是同一个音色」：根因是列表跨档混排，而多说话人档只认带 sid 的音色。
     * 这里锁定口径：clone 档认 zipvoice 参考音色；hq 档只认同档 sid>0 的内置音色。
     */
    @Test
    fun `按当前档位判断音色是否可用`() {
        val cloneProfile = BuiltinModelProfile(
            id = "clone", title = "高质量克隆档", engine = BuiltinModelProfile.ENGINE_ZIPVOICE, sampleRate = 24000
        )
        val hqProfile = BuiltinModelProfile(
            id = "hq", title = "高质量音色档", engine = BuiltinModelProfile.ENGINE_KOKORO,
            sampleRate = 24000, speakerCount = 100
        )
        val zipVoice = voice("b6_m_teen", "内置·男·少年")
        val kokoroVoice = voice("hq-s3", "内置 3").copy(
            profileId = "hq", engine = BuiltinModelProfile.ENGINE_KOKORO, sid = 3
        )

        assertEquals("clone 档认参考音色", true, BuiltinVoiceFilter.usableIn(cloneProfile, zipVoice))
        assertEquals("clone 档不认多说话人音色", false, BuiltinVoiceFilter.usableIn(cloneProfile, kokoroVoice))
        assertEquals("hq 档只认同档 sid 音色", true, BuiltinVoiceFilter.usableIn(hqProfile, kokoroVoice))
        assertEquals("hq 档不认克隆音色（这是「听起来一样」的根因）", false, BuiltinVoiceFilter.usableIn(hqProfile, zipVoice))
    }

    @Test
    fun `摘要带当前档可用数`() {
        val cloneProfile = BuiltinModelProfile(
            id = "clone", title = "高质量克隆档", engine = BuiltinModelProfile.ENGINE_ZIPVOICE, sampleRate = 24000
        )
        val mixed = voices + BuiltinVoice(
            id = "hq-s3", name = "内置 3", profileId = "hq",
            engine = BuiltinModelProfile.ENGINE_KOKORO, sid = 3
        )
        // 5 个音色里 4 个是 clone 档（engine 空 ⇒ 归一 zipvoice），1 个是 hq
        assertEquals("5 个音色 · 本档可用 4", BuiltinVoiceFilter.summaryForProfile(cloneProfile, mixed, 5))
        assertEquals("5 个音色", BuiltinVoiceFilter.summaryForProfile(null, mixed, 5))
    }
}
