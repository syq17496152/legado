package io.legado.app.help.readaloud.role

/**
 * 剧本仲裁（3.4，纯逻辑）：只把「规则补不上」的段交给模型，其余保持规则结果。
 *
 * 合并优先级（AD-04）：`override` > 规则≥0.90 > local-ai（≥0.80 且命中白名单）> 其余规则。
 * 这里不涉及模型调用/KV 管理（那是 2.x 运行时的事），只负责**组批**与**结果落位**，
 * 便于用单测锁定"什么情况下必须保留规则结果"。
 */
object ScriptArbiter {

    /** 单请求最多段数（AD-04） */
    const val MAX_BATCH_SEGMENTS = 12

    /** 单请求输入预算（token，估算按 1.6 字符/token） */
    const val MAX_BATCH_TOKENS = 1200

    /** 规则结果达到该置信度时，不送模型也不许被模型覆盖 */
    const val RULE_CONFIDENT = 0.90

    /** 模型结果最低置信度（低于此值丢弃，保留规则结果） */
    const val AI_MIN_CONFIDENCE = 0.80

    const val CHARS_PER_TOKEN = 1.6

    /** 模型给出的候选（已由调用方把候选序号映射成角色名） */
    data class AiSuggestion(
        val paragraphIndex: Int,
        val start: Int,
        val end: Int,
        val characterName: String,
        val confidence: Double
    )

    data class MergeResult(
        val segments: List<ScriptSegment>,
        /** 有仲裁需求、但最终仍用规则结果（模型没答/被拒）⇒ 上层可标"降级" */
        val degraded: Boolean,
        val aiApplied: Int,
        val rejectedAi: Int
    )

    /**
     * 组批：只收 [ScriptSegment.needsArbitration] 的段，按段号顺序；同时满足 段数 ≤12 与 token 预算。
     * 单段超预算时也给一个独立批次（宁可多一次请求，也不能丢段）。
     */
    fun batches(
        segments: List<ScriptSegment>,
        textOf: (ScriptSegment) -> String
    ): List<List<ScriptSegment>> {
        val targets = segments.filter { it.needsArbitration }
        if (targets.isEmpty()) return emptyList()
        val result = mutableListOf<List<ScriptSegment>>()
        var current = mutableListOf<ScriptSegment>()
        var tokens = 0.0
        targets.forEach { segment ->
            val cost = estimatedTokens(textOf(segment))
            val exceeds = current.size >= MAX_BATCH_SEGMENTS ||
                (current.isNotEmpty() && tokens + cost > MAX_BATCH_TOKENS)
            if (exceeds) {
                result.add(current)
                current = mutableListOf()
                tokens = 0.0
            }
            current.add(segment)
            tokens += cost
        }
        if (current.isNotEmpty()) result.add(current)
        return result
    }

    /** 估算 token：按字符数 / 1.6（中文场景保守估算，只用来说明"这批是否过大"） */
    fun estimatedTokens(text: String): Double =
        (text.trim().length.toDouble() / CHARS_PER_TOKEN).coerceAtLeast(1.0)

    /**
     * 合并模型结果到剧本。
     *
     * @param whitelist 允许被模型指派的角色名（本书已发现角色 + 本章候选 + TopN）
     * @param suggestions 模型结果（同段多条时取置信度最高的一条）
     */
    fun apply(
        script: ChapterScript,
        suggestions: List<AiSuggestion>,
        whitelist: Set<String>
    ): MergeResult {
        val byKey = suggestions
            .groupBy { keyOf(it.paragraphIndex, it.start, it.end) }
            .mapValues { (_, list) -> list.maxByOrNull { it.confidence }!! }
        var degraded = false
        var applied = 0
        var rejected = 0
        val merged = script.segments.map { segment ->
            if (!segment.needsArbitration) return@map segment
            if (segment.confidence >= RULE_CONFIDENT) return@map segment
            val ai = byKey[keyOf(segment.paragraphIndex, segment.start, segment.end)]
            val candidateName = ai?.characterName?.trim().orEmpty()
            val ok = ai != null &&
                ai.confidence >= AI_MIN_CONFIDENCE &&
                candidateName.isNotBlank() &&
                candidateName in whitelist
            when {
                ok -> {
                    applied++
                    segment.copy(
                        characterName = candidateName,
                        // characterId 由调用方按名字映射；这里只知道名字，未知 id 先清零，避免误绑他人
                        characterId = 0L,
                        confidence = ai.confidence,
                        source = "local-ai",
                        frozen = false
                    )
                }
                ai != null -> {
                    rejected++
                    degraded = true
                    segment
                }
                // 只对"该段确实需要仲裁"的段标记降级；高置信段不会走到这里
                else -> {
                    degraded = true
                    segment
                }
            }
        }
        return MergeResult(
            segments = merged,
            degraded = degraded,
            aiApplied = applied,
            rejectedAi = rejected
        )
    }

    fun keyOf(paragraphIndex: Int, start: Int, end: Int): String = "$paragraphIndex|$start|$end"
}
