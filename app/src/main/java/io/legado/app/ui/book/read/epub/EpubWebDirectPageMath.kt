package io.legado.app.ui.book.read.epub

import io.legado.app.model.reader.render.ReadingPosition
import kotlin.math.ceil
import kotlin.math.max

/**
 * 可见 WebView 后端的**分页与位置数学**（阶段 2.3 配套；纯函数 ⇒ JVM 可测）。
 *
 * 为什么独立成对象：可见 WebView 后端本身持有 [android.webkit.WebView]，无法在 JVM 单测中构造；
 * 但其「内容高 → 页数」「滚动偏移 ↔ 页索引」的换算才是出错高发区（固定版式内容常高于视口）。
 * 把这些判据收进纯函数，既满足契约测试收窄口径（AD-12），也让真机 L2 只需验证 WebView 接线本身。
 *
 * 语义（与统一坐标 AD-14 对齐）：**固定/媒体/交互态只产出页·区域锚点**，不做字符偏移换算。
 */
internal object EpubWebDirectPageMath {

    /** 内容高 → 页数（向上取整；内容不足一屏也算 1 页）。 */
    fun pageCount(contentHeightPx: Int, viewportHeightPx: Int): Int {
        if (viewportHeightPx <= 0) return 1
        if (contentHeightPx <= 0) return 1
        return max(1, ceil(contentHeightPx.toDouble() / viewportHeightPx).toInt())
    }

    /** 滚动偏移 → 页索引（夹取到合法区间）。 */
    fun pageIndexForScroll(scrollY: Int, viewportHeightPx: Int, pageCount: Int): Int {
        if (viewportHeightPx <= 0 || pageCount <= 0) return 0
        return (scrollY / viewportHeightPx).coerceIn(0, pageCount - 1)
    }

    /** 页索引 → 滚动偏移（夹取到内容范围）。 */
    fun scrollForPage(pageIndex: Int, viewportHeightPx: Int, pageCount: Int): Int {
        if (viewportHeightPx <= 0 || pageCount <= 0) return 0
        return (pageIndex.coerceIn(0, pageCount - 1)) * viewportHeightPx
    }

    /** 页内相对比例（0..1），供 [ReadingPosition.Page] 报位。 */
    fun inPageRatio(scrollY: Int, viewportHeightPx: Int): Float {
        if (viewportHeightPx <= 0) return 0f
        val offsetInPage = scrollY % viewportHeightPx
        return (offsetInPage.toFloat() / viewportHeightPx).coerceIn(0f, 1f)
    }

    /** 由滚动状态构造页锚点位置（固定版式唯一合法的位置表达）。 */
    fun positionFor(
        chapterIndex: Int,
        scrollY: Int,
        viewportHeightPx: Int,
        pageCount: Int
    ): ReadingPosition.Page {
        return ReadingPosition.Page(
            chapterIndex = chapterIndex,
            pageIndex = pageIndexForScroll(scrollY, viewportHeightPx, pageCount),
            inPageRatio = inPageRatio(scrollY, viewportHeightPx)
        )
    }

    /** 由页锚点位置还原滚动偏移（导入位置用）。 */
    fun scrollForPosition(
        position: ReadingPosition.Page,
        viewportHeightPx: Int,
        pageCount: Int
    ): Int {
        return scrollForPage(position.pageIndex, viewportHeightPx, pageCount) +
            (position.inPageRatio * viewportHeightPx).toInt()
    }
}