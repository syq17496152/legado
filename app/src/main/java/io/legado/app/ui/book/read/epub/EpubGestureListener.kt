package io.legado.app.ui.book.read.epub

/**
 * EPUB 阅读面板手势回调契约（自 `EpubGestureController.Listener` 平移，批 8，2026-10-17）。
 *
 * 背景：`EpubGestureController`（触摸解析器本体）**全仓零实例化** —— EPUB 触摸解析早已由
 * `EpubReadView.onTouchEvent` 自身承担 ⇒ 该控制器整类属「被取代」死件，已随本批退役；
 * 仅其回调契约仍在服役：由 `EpubReadView.Listener` 继承、由 `ReadBookActivity` 覆写。
 * 故契约以本顶层接口保留，成员语义与原嵌套接口逐字一致。
 */
interface EpubGestureListener {

    fun onCenterTap(x: Float, y: Float) = Unit

    fun onPreviousPage() = Unit

    fun onNextPage() = Unit

    fun onPageClick(x: Float, y: Float) = Unit

    /**
     * 九宫格动作入口：`-1` 忽略、`0` 中央、`1` 下一页、`2` 上一页、其余回落中央。
     * （`EpubReadView.Listener` 覆写本方法为忽略，实际动作由其宿主 `ReadBookActivity` 提供。）
     */
    fun onTapAction(action: Int, x: Float, y: Float) {
        when (action) {
            -1 -> Unit
            0 -> onCenterTap(x, y)
            1 -> onNextPage()
            2 -> onPreviousPage()
            else -> onCenterTap(x, y)
        }
    }
}
