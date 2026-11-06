package io.legado.app.help.video

/**
 * 播完续播倒计时（design §1.7 / tasks §7.1，纯策略可单测）
 *
 * 目标：播完不再「瞬间跳集」，先给 3 秒可取消提示，避免误连播。
 *
 * 关键约束：**只有存在可见 UI 承载倒计时时才延迟**（[plan]）。
 * 悬浮窗 / 后台 Service 播放没有提示载体，若同样延迟 3 秒，用户会看到「播完黑屏 3 秒」，
 * 属于体验倒退，因此这些入口保持原来的立即续播。
 */
object AdvanceCountdown {

    /** 默认倒计时秒数（design §1.7 明确 3 秒） */
    const val DEFAULT_SECONDS = 3

    /** 倒计时刷新间隔（毫秒）：200ms 足够让秒数文字及时变化，又不至于过度唤醒主线程 */
    const val TICK_MS = 200L

    sealed class Plan {
        data class Delay(val seconds: Int) : Plan()
        object Immediate : Plan()
    }

    /**
     * 是否延迟续播
     *
     * @param hasUiHost 当前是否有可见 UI 注册了倒计时监听
     * @param seconds 期望倒计时秒数（<=0 视为关闭）
     */
    fun plan(hasUiHost: Boolean, seconds: Int = DEFAULT_SECONDS): Plan =
        if (hasUiHost && seconds > 0) Plan.Delay(seconds) else Plan.Immediate

    /**
     * 剩余秒数（向上取整）：0ms→3，1ms→3，1000ms→2，2999ms→1，3000ms→0
     *
     * 向上取整保证「提示显示的秒数」与「实际等待」一致，不会出现显示 0 秒还停着的错觉。
     */
    fun secondsLeft(elapsedMs: Long, totalMs: Long): Int {
        if (totalMs <= 0) return 0
        val remain = (totalMs - elapsedMs).coerceAtLeast(0L)
        return ((remain + 999L) / 1000L).toInt()
    }
}
