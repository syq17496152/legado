package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-P1-2 高亮规则系统单元测试
 * 验证 HighlightRuleMatcher 的正则/字面量匹配 + 超时保护 + 非法正则跳过
 *
 * 验证点：
 * - 正则匹配正确产出半开区间 [start, end)
 * - 字面量匹配不重叠
 * - 非法正则静默跳过，不抛异常
 * - 空文本/空规则返回 emptyList
 * - 零宽匹配步进 1，不产出
 */
class HighlightRuleMatcherTest {

    private val style = HighlightStyle(textColor = 0xFFFF0000.toInt())

    @Test
    fun regexMatch_producesHalfOpenInterval() {
        // 正常用例：正则匹配 "对话" 文本中的引号内容
        val text = "她说：“你好”。然后走了。"
        val rule = HighlightRuleMatcher.Rule(
            id = "1",
            pattern = "“[^”]+”",
            isRegex = true,
            style = style
        )
        val matches = HighlightRuleMatcher.match(text, listOf(rule))
        assertEquals("应匹配到 1 处", 1, matches.size)
        val m = matches[0]
        assertEquals("start 应为 3", 3, m.start)
        assertEquals("end 应为 7", 7, m.end)
        assertEquals("匹配内容应为 “你好”", "“你好”", text.substring(m.start, m.end))
    }

    @Test
    fun dialogueLimit400_longDialogueMatches() {
        // F3/2.5：内置对话正则放宽 120→400——长对话（150 字引号内容）应整段命中
        val longQuote = "“" + "很长的对话内容".repeat(25) + "”"
        assertTrue("构造应超 120 字", longQuote.length > 120)
        val text = "他说：$longQuote 然后离开。"
        val rule = HighlightRuleMatcher.Rule(
            id = "dialog_default",
            pattern = "“[^”\n]{1,400}”|\"[^\"\n]{1,400}\"|「[^」\n]{1,400}」|『[^』\n]{1,400}』",
            isRegex = true,
            style = style
        )
        val matches = HighlightRuleMatcher.match(text, listOf(rule))
        assertEquals("放宽后长对话应命中", 1, matches.size)
        assertEquals(longQuote, text.substring(matches[0].start, matches[0].end))
    }

    @Test
    fun dialogueLimit400_overLongStillBounded_noReDoS() {
        // F3/2.5+3.2：超 400 字引号整段不命中（限长防 ReDoS，优雅降级为不高亮，登记后续"长度上限配置"），
        // 且匹配耗时受控不挂死
        val hugeQuote = "“" + "超".repeat(1500) + "”"
        val text = "开始：$hugeQuote 结束。"
        val rule = HighlightRuleMatcher.Rule(
            id = "dialog_default",
            pattern = "“[^”\n]{1,400}”|\"[^\"\n]{1,400}\"|「[^」\n]{1,400}」|『[^』\n]{1,400}』",
            isRegex = true,
            style = style
        )
        val start = System.currentTimeMillis()
        val matches = HighlightRuleMatcher.match(text, listOf(rule))
        val elapsed = System.currentTimeMillis() - start
        assertTrue("超限引号应优雅不命中（非崩溃）", matches.isEmpty())
        assertTrue("匹配耗时受控 (${elapsed}ms)", elapsed < 3000)
    }

    @Test
    fun literalMatch_doesNotOverlap() {
        // 边界用例：字面量匹配不重叠
        val text = "aaa aaa aaa"
        val rule = HighlightRuleMatcher.Rule(
            id = "1",
            pattern = "aaa",
            isRegex = false,
            style = style
        )
        val matches = HighlightRuleMatcher.match(text, listOf(rule))
        assertEquals("应匹配到 3 处（不重叠）", 3, matches.size)
        assertEquals("第 1 处 start=0", 0, matches[0].start)
        assertEquals("第 2 处 start=4", 4, matches[1].start)
        assertEquals("第 3 处 start=8", 8, matches[2].start)
    }

    @Test
    fun invalidRegex_silentlySkipped() {
        // 异常用例：非法正则静默跳过，不抛异常
        val text = "测试文本"
        val invalidRule = HighlightRuleMatcher.Rule(
            id = "1",
            pattern = "[unclosed",  // 非法正则
            isRegex = true,
            style = style
        )
        val validRule = HighlightRuleMatcher.Rule(
            id = "2",
            pattern = "测试",
            isRegex = false,
            style = style
        )
        val matches = HighlightRuleMatcher.match(text, listOf(invalidRule, validRule))
        assertEquals("非法正则跳过，仅有效规则匹配", 1, matches.size)
        assertEquals("匹配来自有效规则", "2", matches[0].ruleId)
    }

    @Test
    fun emptyTextOrRules_returnsEmptyList() {
        // 边界用例：空文本/空规则返回 emptyList
        val rule = HighlightRuleMatcher.Rule(
            id = "1",
            pattern = "a",
            isRegex = false,
            style = style
        )
        assertEquals("空文本返回空列表", emptyList<HighlightRuleMatcher.RuleMatch>(), HighlightRuleMatcher.match("", listOf(rule)))
        assertEquals("空规则返回空列表", emptyList<HighlightRuleMatcher.RuleMatch>(), HighlightRuleMatcher.match("abc", emptyList()))
    }

    @Test
    fun zeroWidthMatch_stepForwardWithoutProduce() {
        // 边界用例：零宽匹配步进 1，不产出
        val text = "abc"
        val rule = HighlightRuleMatcher.Rule(
            id = "1",
            pattern = "(?=b)",  // 零宽断言
            isRegex = true,
            style = style
        )
        val matches = HighlightRuleMatcher.match(text, listOf(rule))
        assertTrue("零宽匹配不产出，结果应为空", matches.isEmpty())
    }

    @Test
    fun multipleRules_allMatched() {
        // 正常用例：多规则全部匹配
        val text = "Hello 世界 123"
        val rules = listOf(
            HighlightRuleMatcher.Rule(id = "1", pattern = "Hello", isRegex = false, style = style),
            HighlightRuleMatcher.Rule(id = "2", pattern = "\\d+", isRegex = true, style = style)
        )
        val matches = HighlightRuleMatcher.match(text, rules)
        assertEquals("应匹配到 2 处", 2, matches.size)
        assertEquals("第 1 处 ruleId=1", "1", matches[0].ruleId)
        assertEquals("第 2 处 ruleId=2", "2", matches[1].ruleId)
    }

    //region B15 捕获组样式模板解析

    private val boldRed = HighlightStyle(bold = true, textColor = 0xFFFF0000.toInt())
    private val italic = HighlightStyle(italic = true)

    @Test
    fun matchWithTemplate_regexGroupStyles_producesSubSpans() {
        // 正常用例：带模板的正则命中按捕获组产出子样式段
        val text = "他说：你好世界"
        val rule = HighlightRuleMatcher.Rule(
            id = "1",
            pattern = "([你您])好(世界)",
            isRegex = true,
            style = style,
            replacement = "<b><font color=\"red\">$1</font></b><i>$2</i>"
        )
        val matches = HighlightRuleMatcher.matchWithTemplate(text, listOf(rule))
        assertEquals("应命中 1 处", 1, matches.size)
        val m = matches[0]
        assertEquals("主命中 start=3", 3, m.start)
        assertEquals("主命中 end=7", 7, m.end)
        assertEquals("主命中样式不变", style, m.style)
        assertEquals("应产出 2 个子样式段", 2, m.subSpans.size)

        val span1 = m.subSpans[0]
        assertEquals("组1 start=3", 3, span1.start)
        assertEquals("组1 end=4", 4, span1.end)
        assertEquals("组1 内容=你", "你", text.substring(span1.start, span1.end))
        assertTrue("组1 bold", span1.style.bold)
        assertEquals("组1 颜色", 0xFFFF0000.toInt(), span1.style.textColor)

        val span2 = m.subSpans[1]
        assertEquals("组2 start=5", 5, span2.start)
        assertEquals("组2 end=7", 7, span2.end)
        assertEquals("组2 内容=世界", "世界", text.substring(span2.start, span2.end))
        assertTrue("组2 italic", span2.style.italic)
    }

    @Test
    fun matchWithTemplate_plainGroup_noSubSpans() {
        // 边界用例：裸 $N（无样式标签）不产出子样式段
        val text = "你好世界"
        val rule = HighlightRuleMatcher.Rule(
            id = "1",
            pattern = "(你好)(世界)",
            isRegex = true,
            style = style,
            replacement = "$1 $2"
        )
        val matches = HighlightRuleMatcher.matchWithTemplate(text, listOf(rule))
        assertEquals("应命中 1 处", 1, matches.size)
        assertTrue("无样式组不产出子段", matches[0].subSpans.isEmpty())
    }

    @Test
    fun matchWithTemplate_emptyReplacement_behavesLikeMatch() {
        // 边界用例：空模板退化为普通匹配
        val text = "你好世界"
        val rule = HighlightRuleMatcher.Rule(
            id = "1",
            pattern = "你好",
            isRegex = false,
            style = style,
            replacement = ""
        )
        val matches = HighlightRuleMatcher.matchWithTemplate(text, listOf(rule))
        assertEquals("应命中 1 处", 1, matches.size)
        assertTrue("无模板无子段", matches[0].subSpans.isEmpty())
    }

    @Test
    fun matchWithTemplate_isDotAll_matchesAcrossNewline() {
        // 正常用例：isDotAll 使 . 匹配换行
        val text = "第一行\n第二行"
        val dotAllRule = HighlightRuleMatcher.Rule(
            id = "1",
            pattern = "第一行.第二行",
            isRegex = true,
            style = style,
            isDotAll = true
        )
        val dotAllMatches = HighlightRuleMatcher.matchWithTemplate(text, listOf(dotAllRule))
        assertEquals("dotAll 跨行匹配 1 处", 1, dotAllMatches.size)
        assertEquals("跨行命中 start=0", 0, dotAllMatches[0].start)
        assertEquals("跨行命中 end=7", 7, dotAllMatches[0].end)

        val plainRule = dotAllRule.copy(isDotAll = false)
        val plainMatches = HighlightRuleMatcher.matchWithTemplate(text, listOf(plainRule))
        assertTrue("非 dotAll 不跨行", plainMatches.isEmpty())
    }

    @Test
    fun match_doesNotProduceSubSpans() {
        // 回归：原 match() 不解析模板，不产出子样式段
        val text = "你好世界"
        val rule = HighlightRuleMatcher.Rule(
            id = "1",
            pattern = "(你好)(世界)",
            isRegex = true,
            style = style,
            replacement = "<b>$1</b>"
        )
        val matches = HighlightRuleMatcher.match(text, listOf(rule))
        assertEquals("应命中 1 处", 1, matches.size)
        assertTrue("match() 无子段", matches[0].subSpans.isEmpty())
    }

    //endregion
}
