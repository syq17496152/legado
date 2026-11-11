package io.legado.app.ui.video

import io.legado.app.model.END_SNAP_MS

/**
 * 起播后是否需要「回卷到开头」（IF-05 兜底，design §12 续播链路）。
 *
 * 背景：订阅源续播走 `VideoPlay.startPlay` 的 `player.seekOnStart = durChapterPos`，
 * 该 seek 在 prepare **之后**才生效 ⇒ `onPrepared` 回调里 `currentPosition` 仍是 0，
 * 无法在此时判定。等一拍后再看：若起播位置已落在片尾 2s 内，说明这是「上次播完的位置」，
 * 直接重头播，否则会立刻 ENDED、停在黑屏且封面层吞掉上滑手势（2026-11-07 真机）。
 *
 * 短视频保护：[durationMs] 太短（≤2×阈值）时不判片尾，避免「刚起播就判成播完」无限回卷。
 */
internal fun shouldSnapToStart(positionMs: Long, durationMs: Long): Boolean =
    durationMs > END_SNAP_MS * 2 && positionMs >= durationMs - END_SNAP_MS
