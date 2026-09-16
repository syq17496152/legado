package io.legado.app.help

import io.legado.app.constant.AppLog
import io.legado.app.utils.CssStyleParser
import io.legado.app.utils.CssStyleParser.toHighlightStyle

/**
 * F-P1-2 高亮规则系统（借鉴阅读T）
 * 关键词/正则高亮匹配(纯函数, 无 Android 依赖, JVM 可测)。
 * 输入文本的字符偏移即章内 pos(由 HighlightTextBuilder 保证),输出区间可直接当 Range 用。
 *
 * B15 高亮捕获组样式：新增 matchWithTemplate 变体（现有 match() 保留不替换），
 * 依据 replacement 模板解析捕获组($N)样式，产出组内子样式段 subSpans。
 */
object HighlightRuleMatcher {

    /** R12.1 规则作用域：取值与 `HighlightRule.TARGET_*` 一致（0/1/2） */
    const val SCOPE_ALL = 0
    const val SCOPE_TITLE = 1
    const val SCOPE_BODY = 2

    /** 由实体映射而来的纯规则 */
    data class Rule(
        val id: String,
        val pattern: String,
        val isRegex: Boolean,
        val style: HighlightStyle,
        val timeoutMs: Long = 3000L,
        val replacement: String = "",
        val isDotAll: Boolean = false,
        /** R12.1 作用域：全部 / 仅标题 / 仅正文 */
        val targetScope: Int = SCOPE_ALL
    )

    /** 组内子样式段：整条命中 [start,end) 内部再分区段 */
    data class SubSpan(val start: Int, val end: Int, val style: HighlightStyle)

    /** 一条命中: 半开区间 [start,end) + 来源规则 id + 样式 + B15 组内子样式段 */
    data class RuleMatch(
        val start: Int,
        val end: Int,
        val ruleId: String,
        val style: HighlightStyle,
        val subSpans: List<SubSpan> = emptyList()
    )

    /**
     * §9.5.6 整章匹配结果：命中集 + **是否因超出整章总预算而降级**。
     * `truncated=true` 表示「已停下剩余规则」，调用方应复用上次结果或在下次调用补齐，而非把半成品缓存。
     */
    data class MatchOutcome(
        val matches: List<RuleMatch>,
        val truncated: Boolean
    )

    /**
     * @param titleFlags R12.1：章节文本的逐字符标题行标记（长度应与 text 一致）；
     *                   传 null 表示不做作用域过滤（等价于全部规则 SCOPE_ALL）
     */
    fun match(
        text: String,
        rules: List<Rule>,
        titleFlags: BooleanArray? = null
    ): List<RuleMatch> = matchWithBudget(text, rules, titleFlags).matches

    /**
     * B15 带模板解析变体：与 match() 行为一致，额外依据 replacement 解析捕获组样式，
     * 为每条命中产出组内子样式段 subSpans（现有 match() 不替换、无 subSpans）。
     */
    fun matchWithTemplate(
        text: String,
        rules: List<Rule>,
        titleFlags: BooleanArray? = null
    ): List<RuleMatch> = matchWithBudget(text, rules, titleFlags, withTemplate = true).matches

    /**
     * §9.5.6：**整章总预算**匹配（取代「逐规则 3s 累加」）。
     *
     * @param deadlineMs 本章匹配的绝对截止时刻（`System.currentTimeMillis()` 口径）；
     *                   默认 [Long.MAX_VALUE] = 不设预算（既有调用点行为不变）
     * @param withTemplate 是否解析捕获组子样式（B15）
     *
     * 行为：逐规则开始前检查截止时刻，越界即**停剩余规则**并置 `truncated=true`；
     * 单规则超时 = `min(规则自身 timeout, 剩余预算)`，避免单条规则吃完整个预算。
     */
    fun matchWithBudget(
        text: String,
        rules: List<Rule>,
        titleFlags: BooleanArray? = null,
        deadlineMs: Long = Long.MAX_VALUE,
        withTemplate: Boolean = false,
    ): MatchOutcome {
        if (text.isEmpty() || rules.isEmpty()) return MatchOutcome(emptyList(), false)
        val out = ArrayList<RuleMatch>()
        var truncated = false
        for (rule in rules) {
            if (rule.pattern.isEmpty()) continue
            val remain = deadlineMs - System.currentTimeMillis()
            if (remain <= 0L) {
                truncated = true
                break
            }
            val before = out.size
            val budgeted = if (rule.timeoutMs > remain) rule.copy(timeoutMs = remain) else rule
            if (budgeted.isRegex) {
                matchRegex(text, budgeted, out, withTemplate = withTemplate, titleFlags = titleFlags)
            } else {
                matchLiteral(text, budgeted, out, titleFlags = titleFlags)
            }
            // F3/2.7 诊断：仅零命中规则单条输出（防逐规则刷屏），供"编辑正则不生效"排查
            if (out.size == before) {
                runCatching {
                    AppLog.putDebugWithTag(
                        AppLog.TAG_HIGHLIGHT_STYLE,
                        "规则${rule.id} isRegex=${rule.isRegex} 零命中 patternLen=${rule.pattern.length}",
                        level = AppLog.Level.DEBUG
                    )
                }
            }
        }
        return MatchOutcome(out, truncated)
    }

    /**
     * R12.1 作用域判定：命中区间 [start,end) 是否允许该规则生效。
     * - SCOPE_ALL 或未提供标记 → 允许
     * - SCOPE_TITLE → 区间内须出现标题行字符
     * - SCOPE_BODY  → 区间内不得出现标题行字符（纯正文才允许，避免标题与正文混排区间被误标）
     */
    private fun scopeAllows(
        rule: Rule,
        start: Int,
        end: Int,
        titleFlags: BooleanArray?
    ): Boolean {
        if (rule.targetScope == SCOPE_ALL || titleFlags == null) return true
        var hasTitle = false
        var i = if (start < 0) 0 else start
        val last = if (end > titleFlags.size) titleFlags.size else end
        while (i < last) {
            if (titleFlags[i]) {
                hasTitle = true
                break
            }
            i++
        }
        return when (rule.targetScope) {
            SCOPE_TITLE -> hasTitle
            SCOPE_BODY -> !hasTitle
            else -> true
        }
    }

    /**
     * F3/2.6：字面量模式含正则元字符的一次性提醒登记（进程内每规则至多一次，防刷屏）。
     * §9.5.6：改为**有界 + 线程安全**——匹配可能被多线程触发，且规则集可被用户扩到上千条，
     * 无上限的 Set 会持续增长并在并发 `add` 时抛 `ConcurrentModificationException`。
     */
    private const val LITERAL_WARN_CAP = 200
    private val literalMetaWarned: MutableSet<String> =
        java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    /** 线程安全的「首次登记」判定；超过上限后不再登记（避免无界增长） */
    private fun markLiteralWarned(ruleId: String): Boolean {
        if (literalMetaWarned.size >= LITERAL_WARN_CAP) return false
        return literalMetaWarned.add(ruleId)
    }

    private fun matchLiteral(
        text: String,
        rule: Rule,
        out: MutableList<RuleMatch>,
        titleFlags: BooleanArray? = null
    ) {
        val p = rule.pattern
        // F3/2.6：isRegex=false 时按字面量整串匹配——内容含正则元字符多半是用户写了正则但没开开关，
        // 这是"编辑正则不生效"的高频根因，进程内对每规则提示一次
        if (rule.id !in literalMetaWarned &&
            p.any { it in "\\{}[]|()*+?^$" } &&
            text.contains(p) &&
            markLiteralWarned(rule.id)
        ) {
            runCatching {
                AppLog.putDebugWithTag(
                        AppLog.TAG_HIGHLIGHT_STYLE,
                        "规则${rule.id} 为字面量匹配（isRegex=false）且内容含正则元字符——若意图为正则请在编辑器开启正则开关",
                        level = AppLog.Level.DEBUG
                    )
            }
        }
        var from = 0
        while (from <= text.length) {
            val i = text.indexOf(p, from)
            if (i < 0) break
            // R12.1：作用域不匹配的命中直接丢弃（位置推进仍按原逻辑）
            if (scopeAllows(rule, i, i + p.length, titleFlags)) {
                out.add(RuleMatch(i, i + p.length, rule.id, rule.style))
            }
            from = i + p.length // 不重叠
        }
    }

    private fun matchRegex(
        text: String,
        rule: Rule,
        out: MutableList<RuleMatch>,
        withTemplate: Boolean = false,
        titleFlags: BooleanArray? = null
    ) {
        val regex = try {
            if (rule.isDotAll) Regex(rule.pattern, RegexOption.DOT_MATCHES_ALL) else Regex(rule.pattern)
        } catch (_: Exception) {
            // F3/2.7：非法正则静默跳过改为 WARN 留痕（真机诊断"写了正则却不生效"）
            runCatching {
                AppLog.putDebugWithTag(
                    AppLog.TAG_HIGHLIGHT_STYLE,
                    "规则${rule.id} 非法正则已跳过 patternLen=${rule.pattern.length}",
                    level = AppLog.Level.WARN
                )
            }
            return
        }
        val groupStyles = if (withTemplate && rule.replacement.isNotBlank()) {
            CssStyleParser.extractGroupStyles(rule.replacement)
        } else {
            emptyMap()
        }
        val deadline = System.currentTimeMillis() + rule.timeoutMs.coerceAtLeast(1)
        var idx = 0
        while (idx <= text.length) {
            val mr = regex.find(text, idx) ?: break
            val s = mr.range.first
            val e = mr.range.last + 1
            if (e > s) {
                // R12.1：作用域不匹配的命中直接丢弃（步进仍按匹配末端推进）
                if (scopeAllows(rule, s, e, titleFlags)) {
                    val subSpans = if (groupStyles.isEmpty()) emptyList()
                    else buildSubSpans(mr, groupStyles)
                    out.add(RuleMatch(s, e, rule.id, rule.style, subSpans))
                }
                idx = e
            } else {
                idx = s + 1 // 零宽匹配: 步进 1, 不产出
            }
            if (System.currentTimeMillis() > deadline) {
                runCatching {
                    AppLog.putDebugWithTag(
                        AppLog.TAG_HIGHLIGHT_STYLE,
                        "高亮规则匹配超时 ${rule.id}",
                        level = AppLog.Level.WARN
                    )
                }
                break // 超时保护
            }
        }
    }

    /** 依据模板解析出的组样式，把正则命中按组映射为组内子样式段 */
    private fun buildSubSpans(mr: MatchResult, groupStyles: Map<Int, CssStyleParser.CssStyle>): List<SubSpan> {
        val spans = ArrayList<SubSpan>(groupStyles.size)
        for ((groupIndex, cssStyle) in groupStyles) {
            val group = mr.groups[groupIndex] ?: continue
            if (group.range.isEmpty()) continue
            val gs = group.range.first
            val ge = group.range.last + 1
            if (ge <= gs) continue
            val style = cssStyle.toHighlightStyle()
            if (!style.isEmpty) {
                spans.add(SubSpan(gs, ge, style))
            }
        }
        return spans
    }
}
