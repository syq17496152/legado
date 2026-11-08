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

    /**
     * 该音色在当前档位是否参与合成（与 [BuiltinVoiceRouting.pick] 同口径，不变量 I-6）。
     *
     * 为什么必须有这条判定：音色列表是**跨档混排**的（实测 612 个里只有 100 个 Kokoro 音色
     * 在 hq 档可用），点不可用音色时引擎会静默回落到默认音色 ⇒ 用户听到"所有音色都是同一个声音"。
     * 列表/试听必须据此给出明确提示，不能再静默合成。
     */
    fun usableIn(profile: BuiltinModelProfile, voice: BuiltinVoice): Boolean =
        BuiltinVoiceRouting.pick(profile, listOf(voice), voice.voiceName) != null

    /** 列表头部摘要：检索结果 + 当前档可用数（让"点了没变化"有可解释的原因） */
    fun summaryForProfile(
        profile: BuiltinModelProfile?,
        voices: List<BuiltinVoice>,
        shown: Int
    ): String {
        val base = summary(voices.size, shown)
        if (profile == null) return base
        val usable = voices.count { usableIn(profile, it) }
        return "$base · 本档可用 $usable"
    }
}
