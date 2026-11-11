package io.legado.app.help

import java.util.concurrent.atomic.AtomicInteger

/**
 * 进程前后台状态（review P0-1 修复）。
 *
 * 背景：`Exo2MediaPlayer.updatePrefetchSnapshot()` 曾把 `foreground` 写死为 true，
 * 导致切后台/锁屏后仍继续预取（耗电、耗流量），`SegmentPrefetchPolicy` 的 `background`
 * 闸门形同虚设。
 *
 * 实现：由 `App` 注册 `ActivityLifecycleCallbacks` 维护计数；仅在「无 Activity 处于 started」
 * 时判定为后台。锁屏通常伴随 Activity stopped ⇒ 同样判后台。
 */
object ForegroundState {

    private val startedCount = AtomicInteger(0)

    @Volatile
    private var foreground: Boolean = true

    fun onActivityStarted() {
        startedCount.incrementAndGet()
        foreground = true
    }

    fun onActivityStopped() {
        val remaining = startedCount.updateAndGet { current ->
            (current - 1).coerceAtLeast(0)
        }
        if (remaining == 0) foreground = false
    }

    fun isForeground(): Boolean = foreground

    /** 测试用：重置 */
    internal fun resetForTest(value: Boolean = true) {
        startedCount.set(if (value) 1 else 0)
        foreground = value
    }
}
