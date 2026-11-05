package io.legado.app.ui.highlight

import io.legado.app.help.ai.AiRuleAssistant.Confidence
import io.legado.app.help.ai.AiRuleAssistant.Hit
import io.legado.app.help.ai.AiRuleAssistant.Kind
import io.legado.app.help.ai.AiRuleAssistant.Suggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高亮 AI 候选去重闸：同 pattern 已在规则库 ⇒ 不再建议。
 * 否则每次 AI 生成都会新增一条"看起来一样"的高亮规则，匹配器会对同一段文本重复装饰。
 */
class HighlightRuleAiFilterTest {

    private fun suggestion(pattern: String) = Suggestion(
        kind = Kind.HIGHLIGHT,
        confidence = Confidence.MEDIUM,
        name = "AI 规则",
        pattern = pattern,
        replacement = "",
        reason = "测试",
        hitCount = 1,
        hits = listOf(Hit(1, "命中", ""))
    )

    @Test
    fun dropExisting_removesExactPatternOnly() {
        val items = listOf(
            suggestion("(?m)^广告\$"),
            suggestion("(?m)^广告位\$"),
            suggestion("(?m)^分隔线\$")
        )
        val kept = HighlightRuleAiFilter.dropExisting(items, setOf("(?m)^广告\$"))

        assertEquals(2, kept.size)
        assertEquals(listOf("(?m)^广告位\$", "(?m)^分隔线\$"), kept.map { it.pattern })
    }

    @Test
    fun dropExisting_withNoExistingRulesKeepsAll() {
        val items = listOf(suggestion("(?m)^分隔线\$"))

        assertEquals(items, HighlightRuleAiFilter.dropExisting(items, emptySet()))
    }

    @Test
    fun dropExisting_allAlreadyPresentYieldsEmpty() {
        val items = listOf(suggestion("(?m)^分隔线\$"), suggestion("(?m)^广告\$"))
        val kept = HighlightRuleAiFilter.dropExisting(items, setOf("(?m)^分隔线\$", "(?m)^广告\$"))

        assertTrue(kept.isEmpty())
    }
}
