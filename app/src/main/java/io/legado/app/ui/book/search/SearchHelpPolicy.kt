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
