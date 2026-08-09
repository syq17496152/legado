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

    /** 由实体映射而来的纯规则 */
    data class Rule(
        val id: String,
        val pattern: String,
        val isRegex: Boolean,
        val style: HighlightStyle,
        val timeoutMs: Long = 3000L,
        val replacement: String = "",
        val isDotAll: Boolean = false
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

    fun match(text: String, rules: List<Rule>): List<RuleMatch> {
        if (text.isEmpty() || rules.isEmpty()) return emptyList()
        val out = ArrayList<RuleMatch>()
        for (rule in rules) {
            if (rule.pattern.isEmpty()) continue
            if (rule.isRegex) matchRegex(text, rule, out) else matchLiteral(text, rule, out)
        }
        return out
    }

    /**
     * B15 带模板解析变体：与 match() 行为一致，额外依据 replacement 解析捕获组样式，
     * 为每条命中产出组内子样式段 subSpans（现有 match() 不替换、无 subSpans）。
     */
    fun matchWithTemplate(text: String, rules: List<Rule>): List<RuleMatch> {
        if (text.isEmpty() || rules.isEmpty()) return emptyList()
        val out = ArrayList<RuleMatch>()
        for (rule in rules) {
            if (rule.pattern.isEmpty()) continue
            if (rule.isRegex) matchRegex(text, rule, out, withTemplate = true) else matchLiteral(text, rule, out)
        }
        return out
    }

    private fun matchLiteral(text: String, rule: Rule, out: MutableList<RuleMatch>) {
        val p = rule.pattern
        var from = 0
        while (from <= text.length) {
            val i = text.indexOf(p, from)
            if (i < 0) break
            out.add(RuleMatch(i, i + p.length, rule.id, rule.style))
            from = i + p.length // 不重叠
        }
    }

    private fun matchRegex(
        text: String,
        rule: Rule,
        out: MutableList<RuleMatch>,
        withTemplate: Boolean = false
    ) {
        val regex = try {
            if (rule.isDotAll) Regex(rule.pattern, RegexOption.DOT_MATCHES_ALL) else Regex(rule.pattern)
        } catch (_: Exception) {
            return // 非法正则直接跳过该规则
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
                val subSpans = if (groupStyles.isEmpty()) emptyList()
                else buildSubSpans(mr, groupStyles)
                out.add(RuleMatch(s, e, rule.id, rule.style, subSpans))
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
