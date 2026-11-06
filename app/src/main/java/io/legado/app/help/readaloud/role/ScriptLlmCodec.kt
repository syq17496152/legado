package io.legado.app.help.readaloud.role

import com.google.gson.JsonParser

/**
 * 端侧模型输出编解码（3.3）：只接受紧凑 JSON `{"a":[{"i":段号,"n":候选序号,"c":置信}]}`。
 *
 * 为什么要这么窄：模型自由生成中文角色名会带来同音/异体/加戏三类错配（AD-04），
 * 所以模型只输出**段号 + 候选序号 + 置信度**，名字映射在本地完成；解析失败一律当"没答"，
 * 由 [ScriptArbiter] 保留规则结果并标降级。
 */
object ScriptLlmCodec {

    data class RawItem(val segmentIndex: Int, val candidateIndex: Int, val confidence: Double)

    /** 解析：容忍 ```json 围栏与前后闲话；结构不合法返回空列表（不抛异常） */
    fun parse(raw: String): List<RawItem> {
        val text = strip(raw)
        if (text.isBlank()) return emptyList()
        return runCatching {
            val root = JsonParser.parseString(text)
            if (!root.isJsonObject) return emptyList()
            val array = root.asJsonObject.getAsJsonArray("a") ?: return emptyList()
            array.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                val obj = element.asJsonObject
                val i = runCatching { obj.get("i").asInt }.getOrNull() ?: return@mapNotNull null
                val n = runCatching { obj.get("n").asInt }.getOrNull() ?: return@mapNotNull null
                if (i < 0 || n < 0) return@mapNotNull null
                // c 允许缺省/越界：模型自报置信度不可靠（见 effectiveConfidence），不能因为少一个 c
                // 就把整条建议丢掉（真机上很容易表现为"模型答了但 ai=0"）。
                val c = runCatching { obj.get("c").asDouble }.getOrNull() ?: MODEL_CONFIDENCE_FALLBACK
                RawItem(i, n, effectiveConfidence(c))
            }
        }.getOrDefault(emptyList())
    }

    /** 模型置信度不可采信时的固定值（见 [effectiveConfidence]） */
    const val MODEL_CONFIDENCE_FALLBACK = 0.85

    /**
     * 模型自报置信度 → 仲裁可用的置信度。
     *
     * 为什么不能直接用：POC 与真机都实测 Qwen3 的 `c` **不可采信**（`poc-20261105.md` 结论：
     * 仲裁只能认候选序号 n），取值会退化为 0/1，也会落在 0~0.8 的中间区间。
     * 若原样透传，凡低于 [ScriptArbiter.AI_MIN_CONFIDENCE]=0.80 的值都会被仲裁拒绝
     * ⇒ 模型答了也永远 `ai=0`（真机表现：`items=1 mapped=1` 但 `applied=0 rejected=1`）。
     *
     * 口径：只采信 `[AI_MIN_CONFIDENCE, 1.0]` 区间内的自报值（保留模型"很确信"的信息用于排序），
     * 其余（含 0、中间低值、越界值）一律归一为固定可信值 —— 低于阈值的值保留下来毫无收益，
     * 只会把"模型已给出判断"变成"永远不生效"。
     */
    fun effectiveConfidence(raw: Double): Double =
        if (raw >= ScriptArbiter.AI_MIN_CONFIDENCE && raw <= 1.0) raw else MODEL_CONFIDENCE_FALLBACK

    /**
     * 把模型输出落位到批次内：
     * @param batch 该批次送出的段（顺序即模型看到的段号）
     * @param candidates 本次候选角色名（模型只给序号）
     * 越界段号/候选序号 ⇒ 丢弃（不猜名字）。
     */
    fun toSuggestions(
        items: List<RawItem>,
        batch: List<ScriptSegment>,
        candidates: List<String>
    ): List<ScriptArbiter.AiSuggestion> {
        // 下标基准自适应：提示词要求 0 起，但真机模型经常按 1 起回答（段号=批大小、候选号=候选数）。
        // 只在出现「恰好越界一位」（== size）这种**确定性证据**时才判定整批 1 起；
        // 明显越界（> size，多为模型幻觉）不参与判定，避免把整批带偏。
        val oneBased = items.any {
            it.segmentIndex == batch.size || it.candidateIndex == candidates.size
        }
        return items.mapNotNull { item ->
            val i = if (oneBased) item.segmentIndex - 1 else item.segmentIndex
            val n = if (oneBased) item.candidateIndex - 1 else item.candidateIndex
            val segment = batch.getOrNull(i) ?: return@mapNotNull null
            val name = candidates.getOrNull(n)?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            ScriptArbiter.AiSuggestion(
                paragraphIndex = segment.paragraphIndex,
                start = segment.start,
                end = segment.end,
                characterName = name,
                confidence = effectiveConfidence(item.confidence)
            )
        }
    }

    /** 去掉 ```json / ``` 围栏与首尾闲话，只保留最外层对象 */
    internal fun strip(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```")
            val end = text.lastIndexOf("```")
            if (end >= 0) text = text.substring(0, end)
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end >= start) text.substring(start, end + 1) else ""
    }

    /**
     * GBNF grammar（llama.cpp）：把输出约束在 `{"a":[{"i":N,"n":M,"c":0.x}]}`，
     * 从源头消灭"模型输出解释性中文"导致的 JSON 非法。
     */
    fun gbnf(candidateCount: Int): String {
        val maxCandidate = (candidateCount - 1).coerceAtLeast(0)
        val candidate = if (maxCandidate <= 9) "[0-$maxCandidate]" else "(0|[1-9][0-9]?)"
        return """
root ::= "{\"a\":[" items "]}"
items ::= item ("," item)*
item ::= "{\"i\":" integer ",\"n\":" candidate ",\"c\":" confidence "}"
integer ::= [0-9]+
candidate ::= $candidate
confidence ::= "0" | "1" | "0." [0-9] [0-9]?
        """.trimIndent()
    }
}
