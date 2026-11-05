package io.legado.app.help.ai

import io.legado.app.help.llm.LlmRuntime
import io.legado.app.help.readaloud.role.ScriptLlm
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import java.io.File
import java.util.regex.Pattern

/**
 * 6.2 AI 规则助手（design AD-09）：先生成**草稿**，用户勾选确认后才落库，且始终可回滚。
 *
 * 本文件只放**纯逻辑**（JVM 单测可覆盖）：噪声检测 / 置信分级 / 命中预览 / AI 提示词与解析 / 黑名单。
 * 落库与草稿操作在 UI 层（`AiRuleAssistantDialog`）里调用 `ReplaceRuleKernel` / `HighlightRuleDraftStore`，
 * 避免 `help` 层反向依赖 `ui` 层。
 *
 * 安全模型的落地口径：
 * - **确定性**（分隔线 / 整行网址 / 固定广告标记 / 跨章重复行）默认勾选；
 * - **语义类**（AI 生成）一律 `MEDIUM` 起步，默认不勾选；
 * - 命中行含句读或删除比例 ≥35% ⇒ 强制降级（最多 MEDIUM，必要时 LOW），并写明 `risk`。
 */
object AiRuleAssistant {

    enum class Kind { REPLACE, HIGHLIGHT }

    enum class Confidence { HIGH, MEDIUM, LOW }

    /** 预览命中的一行：行号 + 被匹配的文本 + 替换后的内容（空串表示删除） */
    data class Hit(val lineNo: Int, val matched: String, val replacement: String)

    data class Suggestion(
        val kind: Kind,
        val confidence: Confidence,
        val name: String,
        val pattern: String,
        val replacement: String = "",
        val isRegex: Boolean = true,
        val reason: String = "",
        /** 风险提示（非空 ⇒ 一律不可默认勾选） */
        val risk: String? = null,
        val hitCount: Int = 0,
        val hits: List<Hit> = emptyList()
    ) {
        val stableKey: String get() = "$kind|$pattern|$replacement"

        /** 默认勾选 = 高置信 + 无风险 + 确实命中（失败即"有产出但不误伤"的底线） */
        val defaultChecked: Boolean
            get() = confidence == Confidence.HIGH && risk == null && hitCount > 0
    }

    /** AI 输出条目（字段名与提示词里的 JSON 契约一一对应） */
    private data class AiItem(
        val name: String? = null,
        val pattern: String? = null,
        val replacement: String? = null,
        val reason: String? = null
    )

    private const val MAX_LINE_CHARS = 120
    private const val MAX_HIT_CHARS = 80
    private const val MAX_MATCHES = 500
    private const val MAX_SUGGESTIONS = 12
    private const val MAX_PATTERN_CHARS = 200
    private const val MAX_REPLACEMENT_CHARS = 120
    private const val PROMPT_SAMPLE_CHARS = 2600

    /** 整行只有排版符号（分隔线）：删除安全度最高 */
    private val SEPARATOR_LINE = Regex("^[ \\t]*[-=_*·—#~]{3,}[ \\t]*$")

    private val URL_IN_LINE = Regex(
        "(?:https?://|www\\.)[^\\s\"'<>]+",
        RegexOption.IGNORE_CASE
    )

    /** 固定广告/导流水印开头（只放通用词，不放具体站点名） */
    private val AD_MARKERS = listOf(
        "本书由", "本书首发", "请收藏本站", "求收藏", "求推荐票", "求月票", "投推荐票",
        "最新章节", "章节内容来自", "手机用户请", "百度搜索", "请记住本书", "首发地址", "本章未完"
    )

    private val PROSE_PUNCTUATION = charArrayOf('。', '！', '？', '，', '；', '：', '“', '”', '‘', '’', '…', '、')

    // ==================================================== 确定性检测

    /**
     * 从样章里找"确定性噪声"。
     *
     * @param sample 主样章（当前章/相邻章拼接，用于命中预览）
     * @param chapterTexts 多章正文（用于跨章重复行；少于 3 章时不产生重复行候选）
     */
    fun detectDeterministic(
        kind: Kind,
        sample: String,
        chapterTexts: List<String> = emptyList()
    ): List<Suggestion> {
        val result = ArrayList<Suggestion>()
        val seen = HashSet<String>()

        fun add(suggestion: Suggestion) {
            if (suggestion.pattern.isBlank()) return
            if (!seen.add(suggestion.stableKey)) return
            result.add(preview(suggestion, sample))
        }

        sample.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.length > MAX_LINE_CHARS) return@forEach
            if (SEPARATOR_LINE.matches(line)) {
                add(
                    Suggestion(
                        kind = kind,
                        confidence = Confidence.HIGH,
                        name = "分隔线",
                        pattern = "(?m)^[ \\t]*[-=_*·—#~]{3,}[ \\t]*\$",
                        replacement = "",
                        isRegex = true,
                        reason = "整行只有排版符号，不是正文"
                    )
                )
            }
            if (URL_IN_LINE.containsMatchIn(line) && line.length <= 60) {
                add(
                    Suggestion(
                        kind = kind,
                        confidence = Confidence.HIGH,
                        name = "广告网址行",
                        pattern = "(?m)^[^\\n]{0,80}(?:https?://|www\\.)[^\\n]{0,200}\$",
                        replacement = "",
                        isRegex = true,
                        reason = "整行含网址，多为导流广告"
                    )
                )
            }
            val marker = AD_MARKERS.firstOrNull { line.startsWith(it) }
            if (marker != null) {
                add(
                    Suggestion(
                        kind = kind,
                        confidence = Confidence.HIGH,
                        name = "广告水印",
                        pattern = "(?m)^[ \\t]*(?:" + AD_MARKERS.joinToString("|") + ")[^\\n]{0,80}\$",
                        replacement = "",
                        isRegex = true,
                        reason = "以「$marker」开头，常见于盗版水印"
                    )
                )
            }
        }

        detectCrossChapterRepeats(kind, chapterTexts, sample).forEach { add(it) }
        return merge(result)
    }

    /**
     * 跨章重复行：同一行在 ≥3 章出现，且长度 ≤40。
     *
     * 注意：重复本身不等于广告（口头禅/章节固定开头也会重复），因此后续 [preview] 会按
     * "是否含句读"再做一次降级，避免把正文句子当噪声删掉。
     */
    fun detectCrossChapterRepeats(
        kind: Kind,
        chapterTexts: List<String>,
        sample: String
    ): List<Suggestion> {
        if (chapterTexts.size < 3) return emptyList()
        val counts = HashMap<String, Int>()
        chapterTexts.forEach { text ->
            text.lines()
                .map { it.trim() }
                .filter { it.length in 2..40 }
                .distinct()
                .forEach { line -> counts[line] = (counts[line] ?: 0) + 1 }
        }
        return counts.entries
            .filter { it.value >= 3 }
            .sortedByDescending { it.value }
            .take(MAX_SUGGESTIONS)
            .map { (line, count) ->
                preview(
                    Suggestion(
                        kind = kind,
                        confidence = Confidence.HIGH,
                        name = "跨章重复行",
                        pattern = "(?m)^" + Pattern.quote(line) + "[ \\t]*\$",
                        replacement = "",
                        isRegex = true,
                        reason = "在 $count 章重复出现"
                    ),
                    sample
                )
            }
            .filter { it.hitCount > 0 }
    }

    // ==================================================== 预览与分级

    /** 用样章试跑规则，填 `hitCount`/`hits`，并按风险降级 */
    fun preview(suggestion: Suggestion, sample: String, maxHits: Int = 3): Suggestion {
        if (suggestion.pattern.isBlank()) return suggestion
        val regex = runCatching { Regex(suggestion.pattern) }.getOrNull()
            ?: return suggestion.copy(
                confidence = Confidence.LOW,
                risk = "正则不合法",
                hitCount = 0,
                hits = emptyList()
            )
        var count = 0
        var matchedChars = 0
        val hits = ArrayList<Hit>(maxHits)
        var from = 0
        while (from <= sample.length) {
            val match = regex.find(sample, from) ?: break
            count++
            matchedChars += match.value.length
            if (count <= maxHits) {
                val lineNo = 1 + sample.substring(0, match.range.first).count { it == '\n' }
                hits.add(
                    Hit(
                        lineNo = lineNo,
                        matched = match.value.take(MAX_HIT_CHARS).trim(),
                        replacement = suggestion.replacement
                    )
                )
            }
            if (count >= MAX_MATCHES) break
            from = if (match.range.last >= match.range.first) match.range.last + 1 else match.range.first + 1
        }
        return grade(suggestion.copy(hitCount = count, hits = hits), sample, matchedChars)
    }

    /** 风险降级：删正文/删太多 ⇒ 不可默认勾选；未命中 ⇒ LOW */
    private fun grade(suggestion: Suggestion, sample: String, matchedChars: Int): Suggestion {
        if (suggestion.hitCount == 0) {
            return suggestion.copy(
                confidence = Confidence.LOW,
                risk = suggestion.risk ?: "样章未命中，仅作候选"
            )
        }
        var confidence = suggestion.confidence
        var risk = suggestion.risk
        if (suggestion.replacement.isEmpty()) {
            if (suggestion.hits.any { containsProse(it.matched) }) {
                risk = risk ?: "可能删到带句读的正文"
                if (confidence == Confidence.HIGH) confidence = Confidence.MEDIUM
            }
            if (sample.isNotEmpty() && matchedChars * 100 / sample.length >= 35) {
                risk = "命中范围过大（≥35% 样章）"
                confidence = Confidence.LOW
            }
        }
        return suggestion.copy(confidence = confidence, risk = risk)
    }

    /** 含句读且像中文句子的片段 —— 这种内容永远不能"静默删除" */
    private fun containsProse(text: String): Boolean {
        if (text.count { it in '\u4e00'..'\u9fa5' } < 4) return false
        return PROSE_PUNCTUATION.any { it in text }
    }

    /** 去重（同 pattern+replacement 取置信更高、命中更多的一条），按置信降序 */
    fun merge(items: List<Suggestion>): List<Suggestion> {
        val byKey = LinkedHashMap<String, Suggestion>()
        items.forEach { item ->
            val old = byKey[item.stableKey]
            if (old == null || rank(item) > rank(old)) {
                byKey[item.stableKey] = item
            }
        }
        return byKey.values
            .sortedWith(compareByDescending<Suggestion> { rank(it) }.thenByDescending { it.hitCount })
            .take(MAX_SUGGESTIONS)
    }

    private fun rank(item: Suggestion): Int = when (item.confidence) {
        Confidence.HIGH -> 3
        Confidence.MEDIUM -> 2
        Confidence.LOW -> 1
    }

    // ==================================================== AI 生成

    fun buildAiPrompt(kind: Kind, sample: String): String {
        val target = if (kind == Kind.REPLACE) {
            "生成「替换规则」：把噪声文本替换为空字符串。"
        } else {
            "生成「高亮规则」：pattern 为正则，命中内容会被高亮显示（replacement 一般为空字符串）。"
        }
        val text = sample.take(PROMPT_SAMPLE_CHARS)
        return buildString {
            append("你是文本清洗助手，只输出 JSON 数组，不要解释、不要 Markdown 代码块。\n")
            append(target).append('\n')
            append("每项字段：name（规则名，≤8 字）、pattern（Java 正则）、replacement（替换内容）、reason（原因）。\n")
            append("要求：\n")
            append("1) 只针对可确定的噪声（广告网址、公众号/水印、求票、重复分隔线等）；\n")
            append("2) 正则运行在整章文本上（含换行），跨行匹配请加 (?m) 前缀；\n")
            append("3) 绝不生成会删除正文句子的规则；宁缺毋滥，最多 6 条；没有就输出 []。\n")
            append("样章：\n")
            append(text)
        }
    }

    /** 解析模型输出：截取首个 `[` 到末个 `]`，逐项校验正则并把语义类固定为 MEDIUM */
    fun parseAiOutput(raw: String, kind: Kind, sample: String): List<Suggestion> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val json = raw.substring(start, end + 1)
        val items = GSON.fromJsonArray<AiItem>(json).getOrNull() ?: return emptyList()
        val result = ArrayList<Suggestion>()
        items.forEach { item ->
            val pattern = item.pattern?.trim().orEmpty()
            if (pattern.isEmpty() || pattern.length > MAX_PATTERN_CHARS) return@forEach
            if (runCatching { Regex(pattern) }.isFailure) return@forEach
            val replacement = item.replacement?.trim().orEmpty().take(MAX_REPLACEMENT_CHARS)
            val suggestion = Suggestion(
                kind = kind,
                confidence = Confidence.MEDIUM,
                name = (item.name?.trim().orEmpty().ifBlank { "AI 规则" }).take(12),
                pattern = pattern,
                replacement = replacement,
                isRegex = true,
                reason = item.reason?.trim().orEmpty().ifBlank { "模型建议" }
            )
            result.add(preview(suggestion, sample))
        }
        return merge(result)
    }

    /**
     * 调模型生成候选（失败/不 ready 一律返回空，调用方据此提示原因，不抛异常）。
     */
    suspend fun aiSuggestions(
        llm: ScriptLlm,
        kind: Kind,
        sample: String,
        predictLength: Int = 768
    ): List<Suggestion> {
        if (!llm.ready()) return emptyList()
        llm.setSystemPrompt("你是严格的文本清洗助手，输出必须是合法 JSON 数组。")
        val result = llm.generate(buildAiPrompt(kind, sample), predictLength)
        if (result.errorCode != null || result.text.isBlank()) return emptyList()
        return parseAiOutput(result.text, kind, sample)
    }

    /** AI 失败时给用户一句能懂的话（不暴露内部错误码） */
    fun aiFailureHint(errorCode: LlmRuntime.LlmErrorCode?): String = when (errorCode) {
        null -> "本地 AI 没有返回可用规则（可能是本次没提到噪声）"
        LlmRuntime.LlmErrorCode.TIMEOUT -> "本地 AI 生成超时，可稍后重试或改用「仅规则扫描」"
        LlmRuntime.LlmErrorCode.NOT_LOADED -> "本地 AI 模型还没加载，请先到「朗读设置 → 本地 AI」启用模型"
        LlmRuntime.LlmErrorCode.UNSUPPORTED_ARCH -> "当前 cpu 架构不支持本地 AI"
        LlmRuntime.LlmErrorCode.EMPTY_OUTPUT -> "本地 AI 输出为空，可重试或改用规则扫描"
        else -> "本地 AI 暂不可用（$errorCode），已保留规则扫描结果"
    }

    // ==================================================== 书级黑名单

    /** 用户拒绝/手改过的 pattern 不再建议（AD-09） */
    fun loadBlacklist(file: File): Set<String> =
        runCatching { GSON.fromJsonArray<String>(file.readText()).getOrThrow().toSet() }
            .getOrDefault(emptySet())

    fun saveBlacklist(file: File, patterns: Set<String>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(GSON.toJson(patterns.toList()))
        }
    }

    fun filterBlacklisted(
        items: List<Suggestion>,
        blacklist: Set<String>
    ): List<Suggestion> = items.filter { it.pattern !in blacklist }

    fun blacklistFile(dir: File, bookKey: String): File =
        File(dir, "blacklist_${bookKey.hashCode()}.json")
}
