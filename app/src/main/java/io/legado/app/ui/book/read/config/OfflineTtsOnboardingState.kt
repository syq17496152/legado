package io.legado.app.ui.book.read.config

/**
 * 首次配置引导的判定内核（纯函数，JVM 可单测）。
 *
 * 设计约束（spec AD-04）：
 * - **不阻塞**：系统语音始终可听，引导只解决"要不要现在配离线"；
 * - **老用户零打扰**：已就绪的用户即使没写过 `onboarded` 也不弹；
 * - **跳过有限次**：累计跳过 3 次后不再自动弹出，但仍可从「更多设置 → 首次配置向导」手动进入。
 */
object OfflineTtsOnboardingState {

    /** 自动弹出的最大跳过次数 */
    const val MAX_SKIP = 3

    enum class Decision {
        /** 直接进「听书设置」首屏 */
        ShowHome,

        /** 自动进入引导 */
        EnterOnboarding,

        /** 已跳过足够次数：静默，不自动弹 */
        Skipped
    }

    data class SkipResult(val newCount: Int, val silentFromNow: Boolean)

    /** 完成引导后的落盘值（调用方按此写偏好） */
    data class CompletedMark(val onboarded: Boolean = true, val skipCount: Int = 0)

    /**
     * @param onboarded 曾经完成过引导
     * @param engineReady 当前离线档位已就绪（模型 READY 且有可用音色）
     * @param skipCount 累计跳过次数
     */
    fun decide(onboarded: Boolean, engineReady: Boolean, skipCount: Int): Decision = when {
        onboarded -> Decision.ShowHome
        // 老用户：模型已就绪说明早就配置过，不再弹
        engineReady -> Decision.ShowHome
        skipCount >= MAX_SKIP -> Decision.Skipped
        else -> Decision.EnterOnboarding
    }

    /**
     * 能否进入第 2 步（选音色）。
     * 半就绪（`.importing` 残标 / 缺文件）不能放行，否则用户选完音色却合不出声。
     */
    fun canEnterStep2(modelReady: Boolean, voiceCount: Int): Boolean =
        modelReady && voiceCount > 0

    /** 跳过：次数递增，达到上限后从此静默 */
    fun skipResult(skipCount: Int): SkipResult {
        val next = skipCount.coerceAtLeast(0) + 1
        return SkipResult(newCount = next, silentFromNow = next >= MAX_SKIP)
    }

    /** 完成引导：写入 onboarded 并把跳过计数清零 */
    fun onCompleted(): CompletedMark = CompletedMark()
}
