package io.legado.app.ui.replace

import io.legado.app.help.ai.AiRuleAssistant.Confidence
import io.legado.app.help.ai.AiRuleAssistant.Hit
import io.legado.app.help.ai.AiRuleAssistant.Kind
import io.legado.app.help.ai.AiRuleAssistant.Suggestion
import io.legado.app.testkit.SourceFileProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 6.2 规则助手"勾选 → 应用 → 拉黑 → 回滚"口径的行为断言。
 *
 * 关键不变量：**只有用户勾选的才应用**；未勾选的 pattern 进黑名单（而不是下次又冒出来）；
 * 摘要必须说清条数，避免"点了应用但不知道生效了几条"。
 */
class AiRuleSelectionTest {

    private fun suggestion(
        name: String,
        pattern: String,
        confidence: Confidence = Confidence.HIGH,
        risk: String? = null,
        hitCount: Int = 1
    ) = Suggestion(
        kind = Kind.REPLACE,
        confidence = confidence,
        name = name,
        pattern = pattern,
        replacement = "",
        reason = "测试",
        risk = risk,
        hitCount = hitCount,
        hits = if (hitCount > 0) listOf(Hit(1, pattern, "")) else emptyList()
    )

    private val high = suggestion("分隔线", "(?m)^===\$")
    private val risky = suggestion("水印", "(?m)^广告\$", risk = "可能删到带句读的正文")
    private val semantic = suggestion("AI 规则", "(?m)^公众号\$", Confidence.MEDIUM)
    private val noHit = suggestion("未命中", "(?m)^没人\$", hitCount = 0)

    @Test
    fun defaultSelected_onlyHighWithoutRiskAndWithHits() {
        val selected = AiRuleSelection.defaultSelected(listOf(high, risky, semantic, noHit))

        assertEquals(setOf(high.stableKey), selected)
    }

    @Test
    fun toggle_addsAndRemovesExplicitSelection() {
        val afterAdd = AiRuleSelection.toggle(setOf(high.stableKey), semantic.stableKey, true)
        assertEquals(setOf(high.stableKey, semantic.stableKey), afterAdd)

        val afterRemove = AiRuleSelection.toggle(afterAdd, high.stableKey, false)
        assertEquals(setOf(semantic.stableKey), afterRemove)
    }

    @Test
    fun chosen_followsCandidateOrderAndIgnoresUnchecked() {
        val items = listOf(high, risky, semantic)
        val selected = setOf(semantic.stableKey, high.stableKey)
        val chosen = AiRuleSelection.chosen(items, selected)

        assertEquals(listOf(high, semantic), chosen)
        assertFalse(chosen.contains(risky))
    }

    @Test
    fun rejectedPatterns_isComplementOfChosenPatterns() {
        val items = listOf(high, risky, semantic)
        val chosen = listOf(high)
        val rejected = AiRuleSelection.rejectedPatterns(items, chosen)

        assertEquals(setOf(risky.pattern, semantic.pattern), rejected)
        assertFalse(rejected.contains(high.pattern))
    }

    @Test
    fun applySummary_namesRuleKindAndCount() {
        assertEquals(
            "已应用 3 条替换规则（可点「回滚上次」撤销）",
            AiRuleSelection.applySummary(Kind.REPLACE, 3)
        )
        assertEquals(
            "已应用 2 条高亮规则（可点「回滚上次」撤销）",
            AiRuleSelection.applySummary(Kind.HIGHLIGHT, 2)
        )
    }

    @Test
    fun rollbackSummary_reportsRestoredAndDeleted() {
        assertEquals(
            "已回滚：还原 2 条，删除新增 1 条",
            AiRuleSelection.rollbackSummary(2, 1)
        )
        assertTrue(AiRuleSelection.rollbackSummary(0, 0).contains("还原 0 条"))
    }

    /**
     * 2026-11-08 真机 FATAL 回归锁：`painterResource` 只支持 vector/位图，
     * 而 `ic_bottom_ai` 是 `<selector>` ⇒ 打开替换净化页即抛 IllegalArgumentException。
     * 同目录配对（G-01）落在本文件。
     */
    @Test
    fun topBarAiIcon_mustReferenceVectorResource() {
        val source = SourceFileProbe.sourceText("ui/replace/ReplaceRuleActivity.kt")
        assertTrue(
            "顶栏 AI 图标必须引用 vector 子资源 ic_bottom_ai_e/_s",
            source.contains("R.drawable.ic_bottom_ai_e") || source.contains("R.drawable.ic_bottom_ai_s")
        )
        assertFalse(
            "禁止再引用 <selector> 资源 ic_bottom_ai（painterResource 会崩溃）",
            Regex("""R\.drawable\.ic_bottom_ai\b""").containsMatchIn(source)
        )
    }
}
