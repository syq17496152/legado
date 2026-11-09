package io.legado.app.help.readaloud

import io.legado.app.constant.AppLog

/**
 * 听书路径埋点（TtsUx）：只回答"用户实际走了哪条路"。
 *
 * 为什么单独开 tag：入口收敛这类改动无法靠单测验收，必须有真机可回放的路径证据。
 * 与 `TtsTrace`（合成链路技术日志）区分：本 tag 只记用户动作与跳转，不记音频数据。
 *
 * 级别用 INFO：`AppLog.putDebugWithTag` 在 recordLog 关闭时仍会把 INFO 打到 logcat，
 * 保证测试包/正式包都能采集；内容不含音量、文本等业务数据。
 */
object TtsUxTrace {

    const val TAG = "TtsUx"

    const val EVENT_ENTER_SETTINGS = "enter_settings"
    const val EVENT_ONBOARDING = "onboarding"
    const val EVENT_ACTION = "action"

    /** 引导判定结果：enter / home / skipped */
    fun decide(result: String, engineReady: Boolean, skipCount: Int) {
        put(EVENT_ONBOARDING, "decide=$result engineReady=$engineReady skip=$skipCount")
    }

    /** 引导单步结果（step: 1..3；result: enter/ok/skip/fail） */
    fun onboardingStep(step: Int, result: String, profile: String = "") {
        put(EVENT_ONBOARDING, "step=$step result=$result profile=$profile")
    }

    /** 进入听书设置：from = read_dialog / notification / guide / other */
    fun enterSettings(from: String) {
        put(EVENT_ENTER_SETTINGS, "from=$from")
    }

    /** 入口跳转：name = 用户点的名字，to = 目标页标识 */
    fun action(name: String, to: String) {
        put(EVENT_ACTION, "name=$name to=$to")
    }

    private fun put(event: String, detail: String) {
        AppLog.putDebugWithTag(TAG, "$event $detail", level = AppLog.Level.INFO)
    }
}
