package io.legado.app.help.exoplayer

/**
 * 预取门禁日志上下文（AD-47）。
 *
 * 目的：只靠 `gate reason=xxx` 无法判断「为什么没预取」（是暂停？缓冲中？移动网络？开关关了？）。
 * 把关键快照字段拼成固定顺序的一行，便于真机日志聚合脚本直接解析。
 */
internal object PrefetchGateDiag {

    fun describe(snapshot: PrefetchSnapshot): String = buildString {
        append("pos=").append(snapshot.positionMs)
        append(" buf=").append(snapshot.bufferedPositionMs)
        append(" dur=").append(snapshot.durationMs)
        append(" paused=").append(snapshot.paused)
        append(" buffering=").append(snapshot.buffering)
        append(" metered=").append(snapshot.isMetered)
        append(" prefetch=").append(snapshot.prefetchEnabled)
        append(" cacheCfg=").append(snapshot.videoCacheEnabled)
        append(" live=").append(snapshot.isLive)
        append(" tier=").append(snapshot.previousTier?.name ?: "null")
    }
}
