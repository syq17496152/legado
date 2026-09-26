package io.legado.app.help.ai

import io.legado.app.ui.main.ai.AiChatException

/**
 * AI 文本净化（Q-N4）。
 *
 * 语义拆成三层，**纯逻辑全部可纯 JVM 单测**：
 * 1. 输入归一（[normalizeSelectedText]）：逐行 trim + 丢空行，避免把排版噪声当作内容差异；
 * 2. 输出归一（[normalizeModelOutput]）：剥掉模型偶发的 Markdown 代码围栏；
 * 3. 差异统计（[diffStat]）：以「删除 / 新增」字符数刻画改动；**含新增内容即判为可能被改写**
 *    （与上游 `validate` 口径一致：只有纯删除/同义替换才允许「安全」结论）。
 *
 * 本仓限制与已知上限：
 * - 单次输入上限 [MAX_INPUT_CHARS]，超限直接拒绝（不静默截断，避免用户以为整段都净化了）；
 * - 差异统计优先用 LCS（长度或乘积超预算时退化为「公共前后缀」近似，见 [approximateDiff]）；
 * - 只产出净化结果，不直接改写正文（选区坐标与存储正文坐标系不一致，直接替换会错位）——
 *   用户侧动作为「复制」；升级路径：接入编辑器文本缓冲后即可支持「应用」。
 */
object AiPurifyHelper {

    /** 单次净化输入上限（字符） */
    const val MAX_INPUT_CHARS = 4000

    /** LCS 精确统计的规模预算（超出走近似，避免 O(n·m) 卡 UI 线程/细胞内爆） */
    private const val MAX_LCS_CHARS = 1200
    private const val MAX_LCS_CELLS = 1_000_000L

    /** 差异统计：removed = 原文被删掉的字符数；added = 净化结果新出现的字符数 */
    data class PurifyDiff(
        val removed: Int,
        val added: Int
    ) {
        /** 替换字符数（展示口径，取两者较小值） */
        val replaced: Int get() = minOf(removed, added)

        val changed: Boolean get() = removed > 0 || added > 0
    }

    data class PurifyResult(
        val original: String,
        val purified: String,
        val diff: PurifyDiff
    ) {
        val changed: Boolean get() = diff.changed

        /** 无新增内容 ⇒ 视为安全结果（可能被自动应用）；含新增 ⇒ 可能发生改写 */
        val canAutoApply: Boolean get() = diff.changed && diff.added == 0
    }

    private const val SYSTEM_PROTOCOL =
        "你是一个文本净化器。只输出净化后的正文，不要解释、不要输出 JSON、不要 Markdown 代码块，也不要补充原文没有的内容。"

    private const val USER_INSTRUCTION =
        "请净化下面的文本：删除广告、水印、乱码、无关链接与多余空白，修正明显错别字；" +
            "不要改写原意，不要新增内容。\n\n"

    /** 逐行去空白 + 丢空行（模型对首尾/行内空白很敏感，先归一再比对） */
    internal fun normalizeSelectedText(raw: String): String =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    /** 剥离 ``` 围栏（模型偶发把结果包在代码块里） */
    internal fun normalizeModelOutput(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.substringAfter('\n', "").trimEnd()
            if (text.endsWith("```")) {
                text = text.removeSuffix("```")
            }
        }
        return text.trim()
    }

    /** 差异统计：小文本走精确 LCS，大文本走公共前后缀近似 */
    internal fun diffStat(source: String, cleaned: String): PurifyDiff {
        if (source == cleaned) return PurifyDiff(removed = 0, added = 0)
        val cells = source.length.toLong() * cleaned.length.toLong()
        return if (source.length > MAX_LCS_CHARS || cleaned.length > MAX_LCS_CHARS ||
            cells > MAX_LCS_CELLS
        ) {
            approximateDiff(source, cleaned)
        } else {
            lcsDiff(source, cleaned)
        }
    }

    /** 最长公共子序列（滚动两行）：删除 = 原文长度 − LCS；新增 = 结果长度 − LCS */
    private fun lcsDiff(source: String, cleaned: String): PurifyDiff {
        val columns = cleaned.length
        var previous = IntArray(columns + 1)
        var current = IntArray(columns + 1)
        for (row in 1..source.length) {
            for (column in 1..columns) {
                current[column] = if (source[row - 1] == cleaned[column - 1]) {
                    previous[column - 1] + 1
                } else {
                    maxOf(previous[column], current[column - 1])
                }
            }
            val swap = previous
            previous = current
            current = swap
            current.fill(0)
        }
        val lcs = previous[columns]
        return PurifyDiff(
            removed = source.length - lcs,
            added = cleaned.length - lcs
        )
    }

    /** 退化口径：剥掉公共前后缀后的字符数差（不区分内部替换/新增，故 added 为上限估计） */
    private fun approximateDiff(source: String, cleaned: String): PurifyDiff {
        val limit = minOf(source.length, cleaned.length)
        var prefix = 0
        while (prefix < limit && source[prefix] == cleaned[prefix]) prefix++
        var suffix = 0
        while (suffix < limit - prefix &&
            source[source.length - 1 - suffix] == cleaned[cleaned.length - 1 - suffix]
        ) {
            suffix++
        }
        return PurifyDiff(
            removed = source.length - prefix - suffix,
            added = cleaned.length - prefix - suffix
        )
    }

    /**
     * 调用 AI 净化文本。失败一律抛 [AiChatException]（技术描述由调用方包成用户文案）。
     */
    suspend fun purify(rawText: String): PurifyResult {
        val source = normalizeSelectedText(rawText)
        if (source.isEmpty()) {
            throw AiChatException("Selected text is empty", "")
        }
        if (source.length > MAX_INPUT_CHARS) {
            throw AiChatException("Text is too long (${source.length}/$MAX_INPUT_CHARS)", "")
        }
        val cleaned = normalizeModelOutput(
            AiChatService.generatePlainText(
                systemPrompt = SYSTEM_PROTOCOL,
                userText = USER_INSTRUCTION + source
            )
        )
        if (cleaned.isEmpty()) {
            throw AiChatException("AI returned empty content", "")
        }
        return PurifyResult(
            original = source,
            purified = cleaned,
            diff = diffStat(source, cleaned)
        )
    }
}