package io.legado.app.help.exoplayer

/**
 * 预取限速（§13.7）：按目标速率计算「还需等待多久」，避免后台预取抢占当前播放带宽。
 *
 * 纯函数，便于 JVM 单测锁定节流曲线（不依赖 Android/网络）。
 */
internal object PrefillThrottle {

    /** 默认限速 1.5MB/s（约 12Mbps）：足够 30~60s 区间预取，又不压制在线播放供给 */
    const val DEFAULT_LIMIT_BYTES_PER_SEC: Long = 1_500_000L

    /** 单次等待上限：保证取消（会话结束）能及时响应，不会被长等待拖延 */
    private const val MAX_WAIT_MS: Long = 200L

    /**
     * 已读 [totalRead] 字节、已耗时 [elapsedMs] 时，为维持 [limitBytesPerSec] 还需等待的毫秒数。
     *
     * - 返回 0 表示尚未超前（无需等待）；
     * - 超前越多等待越久，但单次不超过 [MAX_WAIT_MS]；
     * - [limitBytesPerSec] ≤ 0 视为不限速。
     */
    fun waitMs(
        totalRead: Long,
        elapsedMs: Long,
        limitBytesPerSec: Long = DEFAULT_LIMIT_BYTES_PER_SEC,
    ): Long {
        if (limitBytesPerSec <= 0 || totalRead <= 0 || elapsedMs < 0) return 0
        val expectedMs = totalRead * 1000 / limitBytesPerSec
        val wait = expectedMs - elapsedMs
        return wait.coerceIn(0, MAX_WAIT_MS)
    }
}
