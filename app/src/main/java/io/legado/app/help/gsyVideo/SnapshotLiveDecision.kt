package io.legado.app.help.gsyVideo

import io.legado.app.help.exoplayer.LivePlaybackPolicy

/**
 * 播放器 → 预取快照的「是否直播」决策（AD-44，纯函数便于单测）。
 *
 * 背景：fMP4 / 多码率 VOD 在起播与变轨瞬间会被 Media3 标成 dynamic 窗口，
 * 旧判定直接 `dynamic → live` ⇒ 预取 `gate=live` 全程静默不启动。
 * 规则见 [LivePlaybackPolicy.resolveLive]：明确直播优先，其次已知时长按点播。
 */
internal object SnapshotLiveDecision {

    fun resolve(
        isCurrentMediaItemLive: Boolean,
        windowIsDynamic: Boolean,
        knownDurationMs: Long,
    ): Boolean = LivePlaybackPolicy.resolveLive(
        isCurrentMediaItemLive = isCurrentMediaItemLive,
        windowIsDynamic = windowIsDynamic,
        knownDurationMs = knownDurationMs,
    )
}
