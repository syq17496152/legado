package io.legado.app.help.readaloud.casting

import io.legado.app.data.entities.TtsCastingTemplate
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

/**
 * 选角模板规则匹配类型（AD-09 范式模板配置层）
 */
object CastingMatchType {
    const val BUILTIN_QUOTE = "builtin_quote"
    const val REGEX = "regex"
    const val KEYWORD = "keyword"
}

/**
 * 选角分段 tag 词汇表（§1.8-D-2 定型项1：与 NG/C 绑定 TargetType 同构，禁自造第二套）
 * narration/dialogue 本期使用；character/cast_role/thought 为 AI 分镜预留扩展位
 */
object CastingTag {
    const val NARRATION = "narration"
    const val DIALOGUE = "dialogue"
    const val DIALOGUE_MALE = "dialogue_male"
    const val DIALOGUE_FEMALE = "dialogue_female"

    /** AI 角色 tag 前缀（§1.8-D-2：命名空间隔离，防与保留字撞名） */
    const val AI_PREFIX = "ai:"

    /** 保留字：不可用作自定义角色名 tag */
    val RESERVED = setOf(NARRATION, DIALOGUE_MALE, DIALOGUE_FEMALE)

    fun isReserved(tag: String): Boolean = tag in RESERVED
}

/**
 * 韵律参数（§1.8-D-3/AD-07：0=跟随全局不下发，非 0 参与缓存键维度）
 */
data class CastingProsody(
    // 0=跟随全局；有效范围 0.5~2.0
    val rate: Float = 0f,
    val pitch: Float = 0f,
    val volume: Float = 0f
) {
    val valid: Boolean
        get() = rate > 0f || pitch > 0f || volume > 0f
}

/**
 * 单条分段规则：tag × 匹配器 × 声源 × 韵律
 */
data class CastingRule(
    val tag: String = CastingTag.NARRATION,
    // builtin_quote（内置引号规则）/regex/keyword
    val matchType: String = CastingMatchType.BUILTIN_QUOTE,
    // regex/keyword 的表达式；builtin_quote 忽略
    val pattern: String = "",
    // 声源（SpeechRoute JSON）：支持 engineType=current 哨兵=跟随当前生效路由
    val sourceJson: String = "",
    val prosody: CastingProsody = CastingProsody()
) {
    val source: SpeechRoute
        get() = SpeechRoute.fromJson(sourceJson)

    /** 声源有效性：sourceJson 解析后须可定位（含哨兵 current 视为有效） */
    val sourceValid: Boolean
        get() {
            if (sourceJson.isBlank()) return false
            val r = source
            return r.engineType == SpeechRoute.ENGINE_SYSTEM &&
                r.engineValue == CURRENT_SENTINEL ||
                r.engineValue.isNotBlank()
        }

    companion object {
        /** 当前路由哨兵（§3.5.1）：解析时由 TtsCastingStore 替换为实际生效路由 */
        const val CURRENT_SENTINEL = "current"

        fun fromJson(json: String): CastingRule? {
            return GSON.fromJsonObject<CastingRule>(json).getOrNull()
        }
    }
}

/**
 * 选角模板运行时模型（§3.5.1）
 * schemaVersion：模型演进锚点（§1.8-D-2 定型项3），结构变更时递增
 */
data class CastingRuleSet(
    val templateId: String = "",
    val name: String = "",
    val builtin: Boolean = false,
    val schemaVersion: Int = SCHEMA_VERSION,
    val rules: List<CastingRule> = emptyList(),
    // 全局兜底声源：规则未命中/声源失败段级回退目标
    val fallbackSourceJson: String = ""
) {
    /** 同通道校验（§3.5.6①）：rules 内全部声源须同一消费路径（engineType 一致） */
    fun sameChannel(): Boolean {
        val types = rules.map { it.source.engineType }.filter { it.isNotBlank() }.distinct()
        return types.size <= 1
    }

    companion object {
        const val SCHEMA_VERSION = 1

        /** 内置旁白/对白双声模板 id */
        const val BUILTIN_DUAL_VOICE = "builtin_dual_voice"
        const val BUILTIN_MALE_FEMALE = "builtin_male_female"
        const val BUILTIN_MULTITTS_PASSTHROUGH = "builtin_multitts_passthrough"
        const val BUILTIN_MONO = "builtin_mono"

        fun fromEntity(entity: TtsCastingTemplate): CastingRuleSet? {
            if (entity.rulesJson.isBlank()) return null
            val rules = runCatching {
                GSON.fromJsonObject<CastingRulesWrapper>(entity.rulesJson).getOrNull()?.rules
            }.getOrNull() ?: return null
            if (rules.isEmpty()) return null
            return CastingRuleSet(
                templateId = entity.id,
                name = entity.name,
                builtin = entity.builtin,
                schemaVersion = runCatching {
                    GSON.fromJsonObject<CastingRulesWrapper>(entity.rulesJson)
                        .getOrNull()?.schemaVersion ?: SCHEMA_VERSION
                }.getOrDefault(SCHEMA_VERSION),
                rules = rules,
                fallbackSourceJson = entity.fallbackSourceJson
            )
        }
    }

    /** rulesJson 包装结构（含 schemaVersion，导入容错=忽略未知字段） */
    data class CastingRulesWrapper(
        val schemaVersion: Int = SCHEMA_VERSION,
        val rules: List<CastingRule> = emptyList()
    )
}
