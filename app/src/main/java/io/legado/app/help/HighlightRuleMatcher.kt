package io.legado.app.help

/**
 * F-P1-2 高亮规则系统（借鉴阅读T）
 * 关键词/正则高亮匹配(纯函数, 无 Android 依赖, JVM 可测)。
 * 输入文本的字符偏移即章内 pos(由 HighlightTextBuilder 保证),输出区间可直接当 Range 用。
 */
object HighlightRuleMatcher {

    /** 由实体映射而来的纯规则 */
    data class Rule(
        val id: String,
        val pattern: String,
        val isRegex: Boolean,
        val style: HighlightStyle,
        val timeoutMs: Long = 3000L
    )

    /** 一条命中: 半开区间 [start,end) + 来源规则 id + 样式 */
    data class RuleMatch(val start: Int, val end: Int, val ruleId: String, val style: HighlightStyle)

    fun match(text: String, rules: List<Rule>): List<RuleMatch> {
        if (text.isEmpty() || rules.isEmpty()) return emptyList()
        val out = ArrayList<RuleMatch>()
        for (rule in rules) {
            if (rule.pattern.isEmpty()) continue
            if (rule.isRegex) matchRegex(text, rule, out) else matchLiteral(text, rule, out)
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

    private fun matchRegex(text: String, rule: Rule, out: MutableList<RuleMatch>) {
        val regex = try {
            Regex(rule.pattern)
        } catch (_: Exception) {
            return // 非法正则直接跳过该规则
        }
        val deadline = System.currentTimeMillis() + rule.timeoutMs.coerceAtLeast(1)
        var idx = 0
        while (idx <= text.length) {
            val mr = regex.find(text, idx) ?: break
            val s = mr.range.first
            val e = mr.range.last + 1
            if (e > s) {
                out.add(RuleMatch(s, e, rule.id, rule.style))
                idx = e
            } else {
                idx = s + 1 // 零宽匹配: 步进 1, 不产出
            }
            if (System.currentTimeMillis() > deadline) break // 超时保护
        }
    }
}
