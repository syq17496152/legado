package io.legado.app.help.tts.builtin

/**
 * 音色标签值域归一（AD-12 增强，2026-11-10 用户裁定「282 个音色乱七八糟」）。
 *
 * 问题实证：导入包把性别/年龄写成中文（`女` / `青年` / `未定`），内置播种写英文
 * （`male` / `teen`），历史索引里两种混存 ⇒ 列表只能平铺展示、按性别筛时中文条目落不到桶里，
 * 池名还会把内部英文值（narration/youth）直接暴露给用户。
 *
 * 口径（与 [io.legado.app.help.readaloud.casting.CharacterVoiceAssignService] 的配声归一保持一致）：
 * - 性别：`male` / `female` / 空（未知）
 * - 年龄：`teen`（童/少年）/ `youth`（青年）/ `middle`（中年）/ `aged`（老年）/ 空（未知）
 * - "未定"/"未知"/`unknown` 一律归一为空串：未知必须是"没有值"，不能是一个值。
 *
 * 纯函数（JVM 可测），只做值域转换，不改音频、不改 id。
 */
object VoiceTagNormalizer {

    const val GENDER_MALE = "male"
    const val GENDER_FEMALE = "female"

    const val AGE_TEEN = "teen"
    const val AGE_YOUTH = "youth"
    const val AGE_MIDDLE = "middle"
    const val AGE_AGED = "aged"

    /** 未知值的各种写法（含中文"未定"）：归一后必须是空串 */
    private val UNKNOWN_WORDS = setOf(
        "", "未定", "未知", "不详", "无", "空", "-", "—", "n/a", "na", "unknown", "none", "null"
    )

    /** 年龄归档展示顺序（UI 分组与筛选共用，避免各处自定义顺序） */
    val AGE_ORDER = listOf(AGE_TEEN, AGE_YOUTH, AGE_MIDDLE, AGE_AGED)

    fun gender(raw: String): String {
        val text = raw.trim().lowercase()
        if (text in UNKNOWN_WORDS) return ""
        return when {
            text.contains("女") || text == "female" || text == "woman" || text == "f" -> GENDER_FEMALE
            text.contains("男") || text == "male" || text == "man" || text == "m" -> GENDER_MALE
            else -> ""
        }
    }

    fun age(raw: String): String {
        val text = raw.trim().lowercase()
        if (text in UNKNOWN_WORDS) return ""
        return when {
            text.contains("少年") || text.contains("少女") || text.contains("童") ||
                text.contains("child") || text.contains("teen") -> AGE_TEEN
            text.contains("青年") || text.contains("年轻") || text.contains("youth") ||
                text.contains("young") || text.contains("adult") -> AGE_YOUTH
            text.contains("中年") || text.contains("middle") -> AGE_MIDDLE
            text.contains("老年") || text.contains("年迈") || text.contains("长辈") ||
                text.contains("爷爷") || text.contains("奶奶") || text.contains("外公") ||
                text.contains("外婆") || text.contains("翁") ||
                text.contains("aged") || text.contains("elder") || text.contains("old") ||
                text.contains("senior") -> AGE_AGED
            else -> ""
        }
    }

    /** 从名称/标签文本推断年龄（只在文本里真的出现年龄词时才返回，不猜） */
    fun ageFromText(text: String): String = age(text)

    fun genderLabel(value: String): String = when (value) {
        GENDER_MALE -> "男"
        GENDER_FEMALE -> "女"
        else -> ""
    }

    fun ageLabel(value: String): String = when (value) {
        AGE_TEEN -> "少年"
        AGE_YOUTH -> "青年"
        AGE_MIDDLE -> "中年"
        AGE_AGED -> "老年"
        else -> ""
    }

    /** 池名展示：内部英文值 → 中文（池名本身也可能是中文，原样返回） */
    fun poolLabel(pool: String): String = when (pool.trim().lowercase()) {
        "narration", "旁白" -> "旁白"
        "teen", "少年" -> "少年"
        "youth", "青年" -> "青年"
        "middle", "中年" -> "中年"
        "aged", "老年" -> "老年"
        "male", "男声", "男" -> "男声"
        "female", "女声", "女" -> "女声"
        else -> pool
    }

    /**
     * 归一 tags：剔除未知值、把中英混用的性别/年龄替换成规范值、去重保序。
     * 例：`["女","青年","narration","未定"]` + gender=女 age=青年
     *   ⇒ `["female","youth","narration"]`
     */
    fun tags(raw: List<String>, gender: String, age: String): List<String> {
        val normalizedGender = gender(gender)
        val normalizedAge = age(age)
        val result = LinkedHashSet<String>()
        raw.forEach { tag ->
            val trimmed = tag.trim()
            if (trimmed.isBlank() || trimmed.lowercase() in UNKNOWN_WORDS) return@forEach
            val asGender = gender(trimmed)
            val asAge = age(trimmed)
            val asMood = mood(trimmed)
            when {
                asGender.isNotBlank() -> result.add(asGender)
                asAge.isNotBlank() -> result.add(asAge)
                asMood.isNotBlank() -> result.add(asMood)
                else -> result.add(trimmed)
            }
        }
        if (normalizedGender.isNotBlank()) result.add(normalizedGender)
        if (normalizedAge.isNotBlank()) result.add(normalizedAge)
        return result.toList()
    }

    /**
     * 性格（气质）归一：与配声的 MOOD_GROUPS 同口径（中英词都落到同一个 key）。
     * 未命中的文本原样返回，保证用户自定义标签不被吃掉。
     */
    fun mood(raw: String): String {
        val text = raw.trim().lowercase()
        if (text.isBlank()) return ""
        // 直传已归一 key（如 female 之外的 gentle）时原样命中，避免二次归一丢失
        if (text in MOOD_LABELS) return text
        return MOOD_KEYWORDS.firstOrNull { (_, words) -> words.any { text.contains(it) } }?.first.orEmpty()
    }

    fun moodLabel(key: String): String = MOOD_LABELS[key].orEmpty()

    /** 性格维度展示顺序 */
    val MOOD_ORDER = listOf("gentle", "cool", "calm", "lively", "majestic", "husky", "childish")

    private val MOOD_KEYWORDS = listOf(
        "gentle" to listOf("温柔", "柔和", "甜美", "软糯", "亲和"),
        "cool" to listOf("清冷", "冷漠", "淡漠", "冰冷", "高冷"),
        "calm" to listOf("沉稳", "稳重", "低沉", "成熟", "厚重", "冷静"),
        "lively" to listOf("活泼", "元气", "俏皮", "灵动", "欢快", "开朗"),
        "majestic" to listOf("威严", "浑厚", "庄重", "磁性"),
        "husky" to listOf("沙哑", "烟嗓", "沧桑"),
        "childish" to listOf("稚嫩", "童声", "奶声", "幼态")
    )

    private val MOOD_LABELS = mapOf(
        "gentle" to "温柔",
        "cool" to "清冷",
        "calm" to "沉稳",
        "lively" to "活泼",
        "majestic" to "威严",
        "husky" to "沙哑",
        "childish" to "稚嫩"
    )
}
