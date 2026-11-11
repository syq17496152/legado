package io.legado.app.ui.video

import io.legado.app.help.video.PlaybackMode

/**
 * 竖滑 ViewPager 是否响应用户滑动（design §1.13 / tasks §12.6）。
 *
 * - SERIES：只允许切本剧集数，**禁翻页**（否则会切到文章列表里的下一部，语义错位）；
 * - FEED：允许翻页换作品/文章；
 * - SINGLE：末集降级后的单片语义——仍有列表上下文时允许继续上滑下一部（§12.9）；
 * - 书源/单 URL 单页模式恒禁用。
 */
internal fun pagerUserInputEnabled(
    mode: PlaybackMode,
    isSinglePage: Boolean,
    listSize: Int,
): Boolean {
    if (isSinglePage) return false
    return when (mode) {
        PlaybackMode.SERIES -> false
        PlaybackMode.FEED -> listSize > 1
        PlaybackMode.SINGLE -> listSize > 1
    }
}

/**
 * 垂直手势是否由播放器手势层接管（而非 ViewPager2 翻页）。
 *
 * ViewPager2 一旦禁用输入就**不会**再消费垂直滑动，若手势层也不接，SERIES 场景会
 * 表现为「上滑完全无反应」（2026-11-07 真机：收藏直入 SERIES 后上滑零日志）。
 * 故两者严格互补：ViewPager 不翻页 ⇒ 手势层分派 `dispatchVerticalSwitch`。
 */
internal fun gestureHandlesVerticalFling(
    mode: PlaybackMode,
    isSinglePage: Boolean,
    listSize: Int,
): Boolean = !pagerUserInputEnabled(mode, isSinglePage, listSize)
