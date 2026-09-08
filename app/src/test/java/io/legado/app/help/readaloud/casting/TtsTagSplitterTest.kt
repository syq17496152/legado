package io.legado.app.help.readaloud.casting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TtsTagSplitter 分段契约单测（optimize-tts-engine tasks 2.11）
 * 覆盖：中英文引号集/嵌套外层优先/跨段未闭合=段内闭合/并集覆盖不变量/区间互斥
 */
class TtsTagSplitterTest {

    private val quoteRules = listOf(
        CastingRule(tag = CastingTag.DIALOGUE, matchType = CastingMatchType.BUILTIN_QUOTE)
    )

    @Test
    fun chineseQuotesSplitDialogueAndNarration() {
        val paragraph = "他说：“今天天气不错。”然后走出家门。"
        val segments = TtsTagSplitter.splitParagraph(0, paragraph, quoteRules)
        val dialogue = segments.first { it.tag == CastingTag.DIALOGUE }
        assertEquals("“今天天气不错。”", paragraph.substring(dialogue.offsetInParagraph, dialogue.offsetInParagraph + dialogue.length))
        assertTrue(segments.any { it.tag == CastingTag.NARRATION })
    }

    @Test
    fun englishQuotesRecognized() {
        val paragraph = "He said \"hello world\" and left."
        val segments = TtsTagSplitter.splitParagraph(0, paragraph, quoteRules)
        assertTrue(segments.any { it.tag == CastingTag.DIALOGUE })
    }

    @Test
    fun cornerBracketsRecognized() {
        val paragraph = "「你好。」他说。"
        val segments = TtsTagSplitter.splitParagraph(0, paragraph, quoteRules)
        assertTrue(segments.any { it.tag == CastingTag.DIALOGUE })
    }

    @Test
    fun unclosedQuoteClosesWithinParagraph() {
        val paragraph = "他说：“走"
        val segments = TtsTagSplitter.splitParagraph(0, paragraph, quoteRules)
        // 跨段未闭合=段内不闭合，整段回落旁白（并集覆盖）
        assertTrue(segments.all { it.tag == CastingTag.NARRATION })
    }

    @Test
    fun segmentsCoverWholeParagraph() {
        val paragraph = "前文“中段对白”后文。"
        val segments = TtsTagSplitter.splitParagraph(0, paragraph, quoteRules)
        val covered = segments.sumOf { it.length }
        assertEquals(paragraph.length, covered)
        // 偏移连续无重叠
        val sorted = segments.sortedBy { it.offsetInParagraph }
        var cursor = 0
        sorted.forEach {
            assertEquals(cursor, it.offsetInParagraph)
            cursor += it.length
        }
    }

    @Test
    fun noRulesFallsBackToNarration() {
        val paragraph = "无规则整段"
        val segments = TtsTagSplitter.splitParagraph(0, paragraph, emptyList())
        assertEquals(1, segments.size)
        assertEquals(CastingTag.NARRATION, segments[0].tag)
        assertEquals(paragraph.length, segments[0].length)
    }

    @Test
    fun keywordRuleMatches() {
        val rules = listOf(
            CastingRule(tag = "ai:张三", matchType = CastingMatchType.KEYWORD, pattern = "张三")
        )
        val paragraph = "张三走进了房间。"
        val segments = TtsTagSplitter.splitParagraph(0, paragraph, rules)
        // 关键词命中段 + 余段旁白回落（并集覆盖不变量）
        assertEquals(2, segments.size)
        assertEquals("ai:张三", segments[0].tag)
        assertEquals(CastingTag.NARRATION, segments[1].tag)
    }

    @Test
    fun quoteStripRemovesQuoteChars() {
        val stripped = TtsTagSplitter.stripQuotesForSpeech("“你好。”")
        assertEquals("你好。", stripped)
    }
}
