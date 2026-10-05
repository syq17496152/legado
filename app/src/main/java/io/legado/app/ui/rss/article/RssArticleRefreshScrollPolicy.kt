package io.legado.app.ui.rss.article

/**
 * 订阅文章列表「刷新后回到顶部」判定的单源纯函数。
 *
 * 为什么抽成纯函数：宿主 `RssArticlesFragment` 的回顶时机与优先级（刷新意图 / 旋转恢复 / 空表）
 * 若内联在 Flow 回调里，会把「是否回顶」埋在 Android 生命周期与协程时序中，无法单测、无法钉死不变量。
 * 此处收敛为不依赖任何 Android API 的纯函数，可被 JVM 单测直接覆盖。
 *
 * ⚠️ 本文件**不得**引入 `android.*` 依赖（否则纯函数层不再可单测）。
 */
object RssArticleRefreshScrollPolicy {

    /**
     * 是否应当执行「刷新后回到顶部」。
     *
     * 四个条件必须**同时**成立（任一不成立即不回顶）：
     * - [enabled]：设置开关「刷新后回到顶部」已开启（老模式）；
     * - [refreshPending]：本次数据发射由一个刷新入口触发的加载产生（`loadArticles` 系列）；
     * - !restoringPosition：当前**没有**待恢复的旋转/进程重建位置 —— 位置恢复是更具体的用户意图，优先于回顶；
     * - [hasItems]：列表非空 —— 空表回顶无视觉意义，且会白跑一次挂起滚动。
     *
     * @param enabled           设置开关是否开启（`AppConfig.rssArticleRefreshToTop`）
     * @param refreshPending    本次发射是否携带「刷新入口」回顶意图（宿主 `scrollTopOnNextData`）
     * @param restoringPosition 是否存在待执行的旋转/进程重建位置恢复
     * @param hasItems          发射结果列表是否非空
     */
    fun shouldScrollToTop(
        enabled: Boolean,
        refreshPending: Boolean,
        restoringPosition: Boolean,
        hasItems: Boolean
    ): Boolean {
        if (!enabled) return false
        if (!refreshPending) return false
        if (restoringPosition) return false
        if (!hasItems) return false
        return true
    }
}
