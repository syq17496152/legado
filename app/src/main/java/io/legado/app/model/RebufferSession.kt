package io.legado.app.model

/**
 * 卡顿自愈会话状态（纯逻辑，可 JVM 单测）
 *
 * 把「累计 + 只提示一次 + 会话复位」从 VideoPlay 抽出来，避免关键语义只存在于全局单例里无法验证。
 */
class RebufferSession {

    var stats: RebufferStats = RebufferStats()
        private set

    /** 本轮会话是否已提示过切线路 */
    var alerted: Boolean = false
        private set

    /**
     * 记录一次稳态卡顿。
     *
     * @return 累计后的统计 + 本次是否应提示（同一会话最多 true 一次）
     */
    fun record(durationMs: Long, hasAlternativeRoute: Boolean): RebufferUpdate {
        val update = RebufferAlertPolicy.record(stats, durationMs, hasAlternativeRoute)
        stats = update.stats
        val notify = update.notifySwitchRoute && !alerted
        if (notify) alerted = true
        return RebufferUpdate(stats, notify)
    }

    fun reset() {
        stats = RebufferStats()
        alerted = false
    }
}
