package io.legado.app.ui.video

import io.legado.app.help.video.PlaybackMode

/**
 * 解析入口注入的播放模式提示（design §12.5 / AD-10）。
 *
 * Intent extra 用 [PlaybackMode] 名称字符串；非法值/null 返回 null ⇒ 调用方回落到
 * 「按列表上下文推导」（见 `VideoPlay.resolveInitialMode`），避免外部调用方传脏值导致崩溃。
 */
internal fun parsePlaybackModeHint(raw: String?): PlaybackMode? =
    raw?.let { kotlin.runCatching { PlaybackMode.valueOf(it) }.getOrNull() }
