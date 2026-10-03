package io.legado.app.web

import java.util.concurrent.ConcurrentHashMap

/**
 * Web 服务请求限流（web-mcp-productization 一期 · 1.3.3 / REQ-1-110）。
 *
 * **固定窗口计数**：同键在 [WINDOW_MS] 内超过 [LIMIT] 次即拒绝（返回 429）。
 * 只对**携带令牌**的请求计数（键 = 级别 + 令牌摘要）—— 理由见 tasks §1.3.3 实施修订：
 * 匿名请求若共用一个桶，会在同一 WiFi 多设备场景下互相误伤（且过渡期只读端点本就免令牌）。
 *
 * 简化说明: 固定窗口在窗口交界处可能瞬时放行至多 2×LIMIT 次 | 已知上限: 无令牌请求不限流 |
 * 升级路径: 需更平滑时改滑动窗口/令牌桶。
 *
 * **为什么不复用 `help/ConcurrentRateLimiter.kt`**（design 原计划）：该类按 `BaseSource.getKey()` 键控，
 * 且 `fetchStart()` 在 `source == null` 时直接返回 null（不生效）⇒ 无法用于"按令牌限流"。
 */
object WebRateLimiter {

    /** 窗口内最大请求数（`internal` 供单测引用，避免测试硬编码常量）。 */
    internal const val LIMIT = 60

    /** 窗口长度（毫秒）。 */
    internal const val WINDOW_MS = 10_000L

    private class Window(val startAt: Long, count: Int) {
        @Volatile
        var count: Int = count
    }

    private val windows = ConcurrentHashMap<String, Window>()

    /**
     * 记账一次请求。
     *
     * @return `true` = 放行；`false` = 超限（调用方应返回 429）。
     */
    fun tryAcquire(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val window = windows.compute(key) { _, old ->
            when {
                old == null -> Window(now, 1)
                now - old.startAt >= WINDOW_MS -> Window(now, 1)
                else -> old.also { it.count += 1 }
            }
        } ?: return true
        return window.count <= LIMIT
    }

    /** 限流键：级别 + 令牌明文摘要（不落盘、不外泄），保证不同令牌互不影响。 */
    fun keyOf(level: TokenManager.Level, token: String): String =
        "${level.name}:${TokenManager.sha256Hex(token)}"

    /** 清空计数（**仅供单测**）。 */
    internal fun reset() = windows.clear()
}
