package io.legado.app.ui.book.read.textweb

/**
 * 文本富渲染滚动判据（纯函数 ⇒ JVM 可测）。
 *
 * 滚动面（WebView）没有"页"的原生概念，宿主需要知道**是否已到章首/章末**以决定
 * 「翻页 vs 切章」：到章末再按下一页 ⇒ 切下一章；否则 ⇒ 章内滚动一屏。
 *
 * 用**容差**而非严格相等：`scrollY` 与 `contentHeight - viewportHeight` 常因
 * 亚像素/取整差 1~2px，严格相等会导致"明明到底了却切不了章"。
 */
object TextRichScrollPolicy {

    /** 默认容差（px）：吸收取整与亚像素误差。 */
    const val DefaultTolerancePx = 24

    data class Edge(val atTop: Boolean, val atBottom: Boolean)

    /**
     * 边界判定。
     *
     * @param scrollY 当前滚动偏移（正数向下）。
     * @param contentHeight 内容总高（px）。
     * @param viewportHeight 视口高（px）。
     */
    fun edge(
        scrollY: Int,
        contentHeight: Int,
        viewportHeight: Int,
        tolerancePx: Int = DefaultTolerancePx
    ): Edge {
        // 视口或内容无效（尚未测量完成）⇒ 视为"两头都到"，不主动切章，交由宿主兜底
        if (viewportHeight <= 0 || contentHeight <= 0) return Edge(atTop = true, atBottom = true)
        if (contentHeight <= viewportHeight) return Edge(atTop = true, atBottom = true)
        val maxScroll = contentHeight - viewportHeight
        val position = scrollY.coerceIn(0, maxScroll)
        return Edge(
            atTop = position <= tolerancePx,
            atBottom = position >= maxScroll - tolerancePx
        )
    }
}