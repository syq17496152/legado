package io.legado.app.ui.video

/**
 * 选集弹框选台后的补播判定（design §1.7「强化选集入口」）。
 *
 * 真机实证（2026-11-07，3.26.110710）：在选集弹框里切到第 13 集后，引擎已 READY、pos=0，
 * 但界面黑屏 + 播放按钮 —— 原因是弹框关闭瞬间窗口焦点丢失，GSY 会暂停刚起播的播放器，
 * 弹框消失后无人恢复。故切集后需在短窗口内重试，状态落到暂停即 `startAfterPrepared`（不重走 setUp）。
 */
internal fun needResumeAfterPickerSwitch(switched: Boolean, playerPlaying: Boolean): Boolean =
    switched && !playerPlaying
