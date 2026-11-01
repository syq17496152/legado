package io.legado.app.service

/**
 * 朗读起播前置判定（纯函数，IF-14-M 真机铁证 2026-11-01）。
 *
 * 背景：长时朗读在章末续播时可能「静默不动」——`play()` 的多个前置分支都是无声 `return`，
 * 现场无法区分是「引擎未初始化 / 音频焦点被拒 / 朗读列表为空」。
 * 本函数只做**归因**，调用方仍按原顺序执行（init → focus → 空列表），行为不变。
 */
internal object ReadAloudStartGuard {

    const val REASON_OK = ""
    const val REASON_NOT_INIT = "not_init"
    const val REASON_NO_FOCUS = "no_focus"
    const val REASON_EMPTY_LIST = "empty_list"

    /**
     * @param initFinished TTS 是否初始化完成（`ttsInitFinish`）
     * @param focusGranted 音频焦点是否申请成功（仅在初始化完成后才申请）
     * @param contentEmpty 朗读列表是否为空
     * @return 阻断原因；空串表示可起播
     */
    fun blockReason(initFinished: Boolean, focusGranted: Boolean, contentEmpty: Boolean): String =
        when {
            !initFinished -> REASON_NOT_INIT
            !focusGranted -> REASON_NO_FOCUS
            contentEmpty -> REASON_EMPTY_LIST
            else -> REASON_OK
        }
}
