package io.legado.app.ui.replace

import io.legado.app.help.ai.AiRuleAssistant.Kind
import io.legado.app.help.ai.AiRuleAssistant.Suggestion

/**
 * 6.2 规则助手的**选择/应用口径**（纯函数，JVM 可测）。
 *
 * 单独抽出来的原因：这层决定了"默认勾哪些、应用哪几条、哪些进黑名单、给用户什么结论"，
 * 判错会让用户一键应用了没勾的规则，或把想要的规则误拉黑 —— 比 UI 布局更需要测试。
 */
object AiRuleSelection {

    /** 默认勾选：只勾"高置信 + 无风险 + 有命中"（见 [Suggestion.defaultChecked]） */
    fun defaultSelected(items: List<Suggestion>): Set<String> =
        items.filter { it.defaultChecked }.map { it.stableKey }.toSet()

    fun toggle(current: Set<String>, key: String, checked: Boolean): Set<String> =
        if (checked) current + key else current - key

    /** 应用集 = 勾选集对应的候选（顺序跟随候选列表，保证落库顺序稳定） */
    fun chosen(items: List<Suggestion>, selected: Set<String>): List<Suggestion> =
        items.filter { it.stableKey in selected }

    /** 未应用的候选 pattern ⇒ 写入书级黑名单（下次不再建议） */
    fun rejectedPatterns(items: List<Suggestion>, chosen: List<Suggestion>): Set<String> =
        items.map { it.pattern }.toSet() - chosen.map { it.pattern }.toSet()

    fun applySummary(kind: Kind, applied: Int): String {
        val what = if (kind == Kind.REPLACE) "替换规则" else "高亮规则"
        return "已应用 $applied 条$what（可点「回滚上次」撤销）"
    }

    fun rollbackSummary(restored: Int, deleted: Int): String =
        "已回滚：还原 $restored 条，删除新增 $deleted 条"
}
