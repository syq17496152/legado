package io.legado.app.help.video

/**
 * 结束令牌去重（design §1.5 / AD-01）
 *
 * 同一次播放结束可能经多个入口到达（播放器自身 / Activity / 悬浮窗 Service / 沉浸式 Fragment），
 * 用「会话键 + 集索引」组合令牌 + 时间窗，确保同一次结束只放行一次续播。
 */
object EndedTokenDeduper {

    /** 去重窗口：同令牌在该窗口内只放行一次 */
    const val WINDOW_MS = 1200L

    private var lastToken: String? = null
    private var lastAt: Long = 0L

    /**
     * 结束令牌（真机回归后修正）
     *
     * **不得**把「当前集索引 / 切换世代」编进令牌：同一次播完的多个入口回调是**串行**到达的，
     * 第一个入口执行推进后立刻改写索引与切换世代，第二个入口再取到的是新值 ⇒ 令牌不同 ⇒ 去重失效 ⇒ 跳集
     * （2026-11-07 真机铁证：fragment 推进 3→4 后 player 入口又推进 4→5）。
     *
     * 播放器实例在一次播完事件内是稳定标识（各入口拿到同一实例），且 1.2s 窗口内不可能正常播完两集。
     */
    fun playerToken(playerId: Int): String = "player:$playerId"

    /**
     * 是否放行本次推进
     *
     * @param token 令牌，请用 [playerToken]（同一次结束的各入口必须取到同一令牌）
     * @param now 当前时间（毫秒，允许测试注入）
     */
    @Synchronized
    fun allow(token: String, now: Long = android.os.SystemClock.uptimeMillis()): Boolean {
        if (token == lastToken && now - lastAt < WINDOW_MS) {
            return false
        }
        lastToken = token
        lastAt = now
        return true
    }

    /** 新会话/换源时复位 */
    @Synchronized
    fun reset() {
        lastToken = null
        lastAt = 0L
    }
}
