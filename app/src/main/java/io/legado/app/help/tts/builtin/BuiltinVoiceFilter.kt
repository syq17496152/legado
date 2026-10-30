package io.legado.app.help.tts.builtin

/**
 * 音色库检索（T6.4）：搜索（名称/别名 id/标签）+ 性别筛选 + 未打标筛选。
 * 纯函数（可单测），列表页只管把结果渲染出来。
 */
object BuiltinVoiceFilter {

    enum class Gender { ALL, MALE, FEMALE, UNKNOWN }

    data class Criteria(
        val query: String = "",
        val gender: Gender = Gender.ALL,
        val untaggedOnly: Boolean = false,
        val origin: String = "",
        /** 声音池（T8.16）：空=全部；非空按 groupId 精确匹配 */
        val poolId: String = ""
    )

    /** 性别归一：兼容 male/female 与 男/女，空值与"未定"视为未知 */
    fun genderOf(voice: BuiltinVoice): Gender {
        val raw = voice.gender.trim()
        return when {
            raw.contains("女") || raw.equals("female", true) -> Gender.FEMALE
            raw.contains("男") || raw.equals("male", true) -> Gender.MALE
            else -> Gender.UNKNOWN
        }
    }

    fun apply(voices: List<BuiltinVoice>, criteria: Criteria): List<BuiltinVoice> {
        val query = criteria.query.trim()
        return voices.filter { voice ->
            val matchesQuery = query.isBlank() ||
                    voice.name.contains(query, true) ||
                    voice.id.contains(query, true) ||
                    voice.tags.any { it.contains(query, true) } ||
                    voice.age.contains(query, true)
            val matchesGender = criteria.gender == Gender.ALL || genderOf(voice) == criteria.gender
            val matchesUntagged = !criteria.untaggedOnly || genderOf(voice) == Gender.UNKNOWN
            val matchesOrigin = criteria.origin.isBlank() || voice.origin == criteria.origin
            val matchesPool = criteria.poolId.isBlank() || voice.groupId == criteria.poolId
            matchesQuery && matchesGender && matchesUntagged && matchesOrigin && matchesPool
        }
    }

    /** 检索结果摘要（列表页头部文案，纯函数可单测） */
    fun summary(total: Int, shown: Int): String =
        if (total == shown) "$total 个音色" else "显示 $shown / 共 $total 个音色"
}
