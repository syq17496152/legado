package io.legado.app.help.ai

import io.legado.app.help.ai.AiRuleAssistant.Confidence
import io.legado.app.help.ai.AiRuleAssistant.Kind
import io.legado.app.help.ai.AiRuleAssistant.Suggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 6.2 规则助手的**安全不变量**单测。
 *
 * 这里断言的是"会不会误删正文 / 会不会默认勾选 / 能不能回滚入口拿到的候选"，
 * 不是文案：小模型给错规则时，这层分级一旦失守，用户就会一键删掉正文。
 */
class AiRuleAssistantTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val longBody = "夜色压下来的时候，他还在山道上走。风从谷底吹上来，带着湿冷的草木气味。" +
        "村口的老槐树在月光下投出长长的影子，他停下脚步，听了听远处的狗吠。"

    private val proseSample = """
        第1章 归途
        $longBody

        ==========

        本书首发于某某阅读网，请收藏本站。
        更多章节请访问 https://example.com/book/1
        他说：“别回头，前面就是村口了。”
        他一直记得母亲临终前说的那句话。
    """.trimIndent()

    // ── 确定性检测 ──────────────────────────────────────────────────────────

    @Test
    fun detectDeterministic_findsSeparatorUrlAndAdMarkerWithExpectedHits() {
        val list = AiRuleAssistant.detectDeterministic(Kind.REPLACE, proseSample)

        assertEquals("分隔线 / 广告网址行 / 广告水印 三类都应命中", 3, list.size)
        assertTrue(list.all { it.hitCount > 0 })

        val separator = list.first { it.name == "分隔线" }
        val url = list.first { it.name == "广告网址行" }
        val ad = list.first { it.name == "广告水印" }
        assertTrue(separator.defaultChecked)
        assertTrue(url.defaultChecked)
        // 水印行带中文句读 ⇒ 按「可能删到正文」降级，默认不勾选
        assertEquals("risk=${ad.risk} hits=${ad.hits}", Confidence.MEDIUM, ad.confidence)
        assertFalse(ad.defaultChecked)
    }

    @Test
    fun detectDeterministic_separatorRuleDeletesOnlySymbolLines() {
        val separator = AiRuleAssistant.detectDeterministic(Kind.REPLACE, proseSample)
            .first { it.name == "分隔线" }

        assertEquals(Confidence.HIGH, separator.confidence)
        assertEquals("", separator.replacement)
        assertEquals(1, separator.hitCount)
        assertEquals(4, separator.hits.first().lineNo)
    }

    @Test
    fun detectDeterministic_deduplicatesSamePatternAcrossLines() {
        val sample = "====\n----\n***\n只有一行正文"
        val list = AiRuleAssistant.detectDeterministic(Kind.REPLACE, sample)

        assertEquals(1, list.size)
        assertEquals(3, list.first().hitCount)
    }

    // ── 跨章重复行 ──────────────────────────────────────────────────────────

    @Test
    fun detectCrossChapterRepeats_needsAtLeastThreeChapters() {
        val chapters = listOf("同一行水印\n正文一", "同一行水印\n正文二")

        assertTrue(
            AiRuleAssistant.detectCrossChapterRepeats(Kind.REPLACE, chapters, chapters.joinToString("\n")).isEmpty()
        )
    }

    @Test
    fun detectCrossChapterRepeats_flagsLineSeenInAllChapters() {
        val chapters = listOf(
            "某站水印 001\n$longBody 一",
            "某站水印 001\n$longBody 二",
            "某站水印 001\n$longBody 三"
        )
        val sample = chapters.joinToString("\n")
        val list = AiRuleAssistant.detectCrossChapterRepeats(Kind.REPLACE, chapters, sample)

        assertEquals(1, list.size)
        assertEquals(3, list.first().hitCount)
        assertTrue("重复行本身算确定性候选", list.first().defaultChecked)
    }

    @Test
    fun detectCrossChapterRepeats_downgradesRepeatedProseSentence() {
        val chapters = listOf(
            "他说：“别回头。”\n$longBody 一",
            "他说：“别回头。”\n$longBody 二",
            "他说：“别回头。”\n$longBody 三"
        )
        val sample = chapters.joinToString("\n")
        val line = AiRuleAssistant.detectCrossChapterRepeats(Kind.REPLACE, chapters, sample).first()

        assertEquals("重复的口头禅也是正文，必须降级", Confidence.MEDIUM, line.confidence)
        assertFalse(line.defaultChecked)
        assertEquals("可能删到带句读的正文", line.risk)
    }

    // ── 预览与分级 ──────────────────────────────────────────────────────────

    @Test
    fun preview_invalidRegexIsLowConfidenceAndExplained() {
        val broken = Suggestion(
            kind = Kind.REPLACE,
            confidence = Confidence.HIGH,
            name = "坏规则",
            pattern = "([",
            reason = "测试"
        )
        val previewed = AiRuleAssistant.preview(broken, proseSample)

        assertEquals(Confidence.LOW, previewed.confidence)
        assertEquals("正则不合法", previewed.risk)
        assertEquals(0, previewed.hitCount)
    }

    @Test
    fun preview_noHitBecomesLowAndUnchecked() {
        val never = Suggestion(
            kind = Kind.REPLACE,
            confidence = Confidence.HIGH,
            name = "未命中",
            pattern = "(?m)^[ \\t]*!!!广告!!![ \\t]*\$",
            reason = "测试"
        )
        val previewed = AiRuleAssistant.preview(never, proseSample)

        assertEquals(Confidence.LOW, previewed.confidence)
        assertEquals("样章未命中，仅作候选", previewed.risk)
        assertFalse(previewed.defaultChecked)
    }

    @Test
    fun preview_hugeDeletionRatioForcesLow() {
        // 删除 80% 样章 ⇒ 命中范围过大，必须 LOW（不允许默认勾选）
        val sample = "广告广告广告广告广告\n正文"
        val greedy = Suggestion(
            kind = Kind.REPLACE,
            confidence = Confidence.HIGH,
            name = "贪婪规则",
            pattern = "(?m)^[^\\n]*广告[^\\n]*\$",
            reason = "测试"
        )
        val previewed = AiRuleAssistant.preview(greedy, sample)

        assertEquals(Confidence.LOW, previewed.confidence)
        assertEquals("命中范围过大（≥35% 样章）", previewed.risk)
    }

    @Test
    fun preview_replacementRuleIsNotBlockedByProsePunctuation() {
        // 有替换内容（非删除）时不应被"删正文"风险拦截
        val fix = Suggestion(
            kind = Kind.REPLACE,
            confidence = Confidence.HIGH,
            name = "改错别字",
            pattern = "他门",
            replacement = "他们",
            reason = "错别字"
        )
        val previewed = AiRuleAssistant.preview(fix, "他门走了。")

        assertEquals(Confidence.HIGH, previewed.confidence)
        assertEquals(null, previewed.risk)
    }

    // ── AI 输出解析 ──────────────────────────────────────────────────────────

    @Test
    fun parseAiOutput_extractsJsonFromNoisyModelOutput() {
        val raw = """
            好的，下面是建议：
            ```json
            [{"name":"水印","pattern":"(?m)^某站水印[^\\n]*$","replacement":"","reason":"重复水印"}]
            ```
        """.trimIndent()
        val list = AiRuleAssistant.parseAiOutput(raw, Kind.REPLACE, "某站水印 001\n$longBody")

        assertEquals(1, list.size)
        assertEquals("水印", list.first().name)
        assertEquals("AI 生成的语义类规则默认 MEDIUM", Confidence.MEDIUM, list.first().confidence)
        assertFalse("语义类默认不勾选", list.first().defaultChecked)
    }

    @Test
    fun parseAiOutput_dropsInvalidRegexAndBlankPattern() {
        val raw = """[{"name":"坏","pattern":"([","replacement":""},{"name":"空","pattern":"  "}]"""
        val list = AiRuleAssistant.parseAiOutput(raw, Kind.REPLACE, proseSample)

        assertTrue(list.isEmpty())
    }

    @Test
    fun parseAiOutput_deduplicatesSamePattern() {
        val raw = """
            [{"name":"A","pattern":"(?m)^广告$","replacement":""},
             {"name":"B","pattern":"(?m)^广告$","replacement":""}]
        """.trimIndent()
        val list = AiRuleAssistant.parseAiOutput(raw, Kind.REPLACE, "广告\n正文")

        assertEquals(1, list.size)
    }

    @Test
    fun parseAiOutput_returnsEmptyWhenNoJsonArray() {
        assertTrue(AiRuleAssistant.parseAiOutput("没有任何建议", Kind.REPLACE, proseSample).isEmpty())
    }

    // ── 合并顺序 ────────────────────────────────────────────────────────────

    @Test
    fun merge_keepsHigherConfidenceForSamePattern() {
        val low = Suggestion(Kind.REPLACE, Confidence.LOW, "低", "(?m)^广告\$", "", true, "x", hitCount = 1)
        val high = Suggestion(Kind.REPLACE, Confidence.HIGH, "高", "(?m)^广告\$", "", true, "x", hitCount = 1)
        val merged = AiRuleAssistant.merge(listOf(low, high))

        assertEquals(1, merged.size)
        assertEquals(Confidence.HIGH, merged.first().confidence)
        assertEquals("高", merged.first().name)
    }

    @Test
    fun merge_sortsHighBeforeLow() {
        val low = Suggestion(Kind.REPLACE, Confidence.LOW, "低", "(?m)^aaa\$", "", true, "x", hitCount = 5)
        val high = Suggestion(Kind.REPLACE, Confidence.HIGH, "高", "(?m)^bbb\$", "", true, "x", hitCount = 1)
        val merged = AiRuleAssistant.merge(listOf(low, high))

        assertEquals(listOf(Confidence.HIGH, Confidence.LOW), merged.map { it.confidence })
    }

    // ── 黑名单 ──────────────────────────────────────────────────────────────

    @Test
    fun blacklist_roundTripsAndFiltersSuggestions() {
        val file = AiRuleAssistant.blacklistFile(tmp.root, "book-1")
        AiRuleAssistant.saveBlacklist(file, setOf("(?m)^广告\$"))
        val loaded = AiRuleAssistant.loadBlacklist(file)

        assertEquals(setOf("(?m)^广告\$"), loaded)

        val kept = AiRuleAssistant.filterBlacklisted(
            listOf(
                Suggestion(Kind.REPLACE, Confidence.HIGH, "广告", "(?m)^广告\$", "", true, "x"),
                Suggestion(Kind.REPLACE, Confidence.HIGH, "分隔线", "(?m)^===\$", "", true, "x")
            ),
            loaded
        )
        assertEquals(listOf("分隔线"), kept.map { it.name })
    }

    @Test
    fun blacklist_missingFileMeansEmpty() {
        assertTrue(AiRuleAssistant.loadBlacklist(tmp.root.resolve("nope.json")).isEmpty())
    }

    // ── 提示词 ──────────────────────────────────────────────────────────────

    @Test
    fun buildAiPrompt_containsJsonContractAndCrossLineHint() {
        val prompt = AiRuleAssistant.buildAiPrompt(Kind.REPLACE, "样章正文")

        assertTrue(prompt.contains("只输出 JSON 数组"))
        assertTrue(prompt.contains("(?m)"))
        assertTrue(prompt.contains("样章正文"))
    }

    @Test
    fun buildAiPrompt_highlightKindMentionsHighlight() {
        val prompt = AiRuleAssistant.buildAiPrompt(Kind.HIGHLIGHT, "样章正文")

        assertTrue(prompt.contains("高亮规则"))
        assertFalse("高亮提示词不应写成替换为空", prompt.contains("替换为空字符串"))
    }
}
