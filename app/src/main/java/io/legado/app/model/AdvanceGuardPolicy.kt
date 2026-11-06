package io.legado.app.model

/**
 * 续播守卫（design §3.1 / §7.1，纯函数可 JVM 单测）
 *
 * 判定顺序即优先级：切换中 > 布局切换 > 播放器无效 > 上次播放失败 > 倒计时等待中。
 * 返回 `null` 表示放行。
 *
 * 说明：守卫一旦命中只记日志、不推进；倒计时等待中判为重复，避免 3 秒窗口内
 * 第二个入口（去重窗口只有 1.2s）再次排一次倒计时。
 */
internal fun advanceBlockReason(
    switching: Boolean,
    layoutSwitching: Boolean,
    playerValid: Boolean,
    playError: Boolean,
    countdownPending: Boolean = false
): String? = when {
    switching -> "switching"
    layoutSwitching -> "layout_switching"
    !playerValid -> "player_invalid"
    playError -> "play_error"
    countdownPending -> "countdown_pending"
    else -> null
}
