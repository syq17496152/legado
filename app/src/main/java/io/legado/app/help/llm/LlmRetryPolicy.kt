package io.legado.app.help.llm

/**
 * 2.5 连接退避策略（design AD-11）。
 *
 * `:llm` 进程崩溃对主进程只表现为「连接断开」，上层必须：
 * ① 立刻回落规则结果（不阻塞朗读）；② 记录一次失败；③ 按指数退避再连，且**每会话最多 3 次**
 * （否则反复拉起 native 进程会把低端设备拖垮）。
 *
 * 纯逻辑、无 I/O，便于 JVM 单测锁定次数与退避梯度。
 */
object LlmRetryPolicy {

    const val MAX_ATTEMPTS = 3

    private val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L)

    data class Decision(
        /** 本次是第几次重试（1 起） */
        val attempt: Int,
        /** 重连前等待毫秒；额度用尽时为 0 */
        val delayMs: Long
    ) {

        val allowed: Boolean get() = attempt in 1..MAX_ATTEMPTS
    }

    /** @param used 本会话已用掉的重试次数（0 表示第一次失败） */
    fun next(used: Int): Decision {
        val attempt = used.coerceAtLeast(0) + 1
        if (attempt > MAX_ATTEMPTS) return Decision(attempt, 0L)
        return Decision(attempt, BACKOFF_MS[(attempt - 1).coerceAtMost(BACKOFF_MS.size - 1)])
    }

    fun exhausted(used: Int): Boolean = used >= MAX_ATTEMPTS
}
