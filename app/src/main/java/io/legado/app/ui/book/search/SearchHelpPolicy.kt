package io.legado.app.ui.book.search

/**
 * 搜索页「输入帮助层（书架命中 + 搜索历史）」是否应让位给结果列表。
 *
 * 真机取证（2026-11-08 00:34）：外部 `--es key` 直达搜索后在线结果已返回 10 条，
 * 但首次聚焦回调把帮助层重新盖到结果上 ⇒ 用户看到「搜索没结果」。
 *
 * @param hasFocus 搜索框是否获得焦点
 * @param progressLoading 是否正在搜索
 * @param hasResult 是否已有结果条目
 * @param intentWindowActive 是否处于「intent 直达搜索」的抑制时间窗内
 */
internal fun shouldHideInputHelp(
    hasFocus: Boolean,
    progressLoading: Boolean,
    hasResult: Boolean,
    intentWindowActive: Boolean,
): Boolean {
    if (progressLoading) return true
    if (hasFocus && intentWindowActive) return true
    return !hasFocus && hasResult
}

/**
 * 结果到达后是否应立即收起帮助层（`LaunchedEffect` 兜底）。
 *
 * 与 [shouldHideInputHelp] 的分工：后者在焦点变化时判定，本函数在「结果数/关键词变化」时判定，
 * 覆盖「输入框已聚焦、之后才有结果」这条不触发焦点回调的时序（真机 2026-11-08 01:02）。
 */
internal fun shouldCollapseHelpOnResults(resultCount: Int, query: String): Boolean =
    resultCount > 0 && query.isNotBlank()
