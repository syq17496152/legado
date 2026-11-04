package io.legado.app.help.readaloud.role

/**
 * 剧本提示词模板（3.3）：system 放"候选 + 格式 + few-shot"，user 每批只放待判段。
 *
 * 与 AD-04 的 KV 复用配套：system 每章只 prefill 一次，批次只追加 user（省 token）。
 * 输出格式固定为 `{"a":[{"i":段号,"n":候选序号,"c":置信}]}`，由 [ScriptLlmCodec] 解析。
 */
object ScriptPromptTemplates {

    /** 候选角色最多放几个（超过则模型难对齐，准确率反而下降） */
    const val MAX_CANDIDATES = 40

    fun system(candidates: List<String>, examples: Int = 3): String {
        val safe = candidates.map { it.trim() }.filter { it.isNotBlank() }
            .distinct().take(MAX_CANDIDATES)
        val candidateLines = safe.mapIndexed { index, name -> "$index. $name" }.joinToString("\n")
        val shots = (1..examples).joinToString("\n") { i ->
            "{\"a\":[{\"i\":${i - 1},\"n\":0,\"c\":0.9}]}"
        }
        return buildString {
            appendLine("你是有声书段落归属器。只输出 JSON，不要解释。")
            appendLine("任务：给每个需要判定的段选择最可能的说话人；旁白段与拿不准的段直接跳过，不要输出。")
            appendLine("候选角色（用序号，不要写名字）：")
            appendLine(candidateLines)
            appendLine("输出格式：{\"a\":[{\"i\":段号,\"n\":候选序号,\"c\":0到1置信}]}")
            appendLine("规则：i 是本次输入的行号（从 0 开始）；同一段只输出一条；不确定就不要输出该段。")
            appendLine("示例：")
            append(shots)
        }.trim()
    }

    /** 每批 user：`序号. 原文`（序号必须与模型输出里的 i 一致） */
    fun user(batch: List<Pair<ScriptSegment, String>>): String = buildString {
        appendLine("请判定以下段落：")
        batch.forEachIndexed { index, (_, text) ->
            appendLine("$index. ${text.trim()}")
        }
    }.trim()

    /** 输入裁剪：单段过长会挤爆 token 预算，按字符截断（不切半个引号） */
    fun clip(text: String, maxChars: Int = 300): String {
        val trimmed = text.trim()
        if (trimmed.length <= maxChars) return trimmed
        return trimmed.take(maxChars - 1) + "…"
    }
}
