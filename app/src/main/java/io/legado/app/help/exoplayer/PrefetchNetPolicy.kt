package io.legado.app.help.exoplayer

/**
 * 计量网络 × 用户开关 的预取准入判定（2026-11-13 用户裁定，AD-46）。
 *
 * 背景：移动网络下预取曾被默认关闭（`prefetchOnMobile=false`）且**没有前端开关**，
 * 真机 4G 播放时快照 `isMetered=true` ⇒ `gate reason=metered`，用户观感「完全不缓冲」。
 *
 * 现约定：默认允许移动网络预取；用户可在播放设置里显式关闭。
 * 抽成纯函数便于单测锁定，避免再次出现「策略默认值被悄悄改回省流量」。
 */
internal object PrefetchNetPolicy {

    /** 是否因计量网络**阻止**预取（仅当用户显式关闭移动网络预取时为 true） */
    fun blockedByMetered(meteredNow: Boolean, prefetchOnMobile: Boolean): Boolean =
        meteredNow && !prefetchOnMobile
}
