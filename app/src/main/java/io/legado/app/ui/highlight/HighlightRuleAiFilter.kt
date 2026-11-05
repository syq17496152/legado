package io.legado.app.ui.highlight

import io.legado.app.help.ai.AiRuleAssistant.Suggestion

/**
 * 高亮规则 AI 候选的**去重闸**（纯函数，JVM 可测）。
 *
 * 为什么需要：高亮规则按 id 存储，AI 每次生成的都是新 id，
 * 同一条 pattern 反复应用会在列表里堆出多个"看起来一样"的高亮规则；
 * 匹配器还会对同一段文本叠加多次装饰，既费性能也难排查。
 * 因此应用前先剔除与既有规则 pattern 完全相同的候选。
 */
object HighlightRuleAiFilter {

    fun dropExisting(items: List<Suggestion>, existingPatterns: Set<String>): List<Suggestion> =
        items.filter { it.pattern !in existingPatterns }
}
