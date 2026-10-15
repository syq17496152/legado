package io.legado.app.ui.book.read

/**
 * 模板面（WebView）正文**复用闸**的纯函数（HL-3 接线）。
 *
 * 为什么单列成纯函数：原判据内联在 `ReadBookActivity.loadTemplateContentAwait` 的长条件里，
 * 既无法单测、也是「改规则/划线没反应」这类缺陷的高发点 —— 依据只有两条**版本号**进门，
 * 用户可感的失败模式却是"点了没反应"。抽出后可对**真值表**逐条钉住（见配对用例）。
 *
 * ⚠️ 两条版本号必须**同时**相同才可复用：
 * · `rulesVersion` —— 高亮规则（`ReadBook.highlightRulesVersion`）
 * · `manualVersion` —— **手动划线**（`ReadBook.highlightsVersion`，HL-3 新增）
 * 漏掉后者 ⇒ 划一条线后仍走"同章且后端在场"的早退分支，模板正文不重装饰、划线看不见。
 */
internal object TemplateReuseGate {

    fun shouldReuse(
        templateActive: Boolean,
        preparedIndex: Int,
        chapterIndex: Int,
        backendPresent: Boolean,
        decoratedRulesVersion: Int,
        currentRulesVersion: Int,
        decoratedManualVersion: Int,
        currentManualVersion: Int
    ): Boolean = templateActive
            && preparedIndex == chapterIndex
            && backendPresent
            && decoratedRulesVersion == currentRulesVersion
            && decoratedManualVersion == currentManualVersion
}
