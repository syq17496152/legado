package io.legado.app.ui.rss.article.compose

/**
 * D4 Rss 文章列表布局形态（design-b3-d4-flagship §2.1）。
 * 与源实体 articleStyle 字段 0~4 一一对应（映射见 [toRssArticleListStyle]）。
 */
enum class RssArticleListStyle {
    /** style 0/1：单列列表行 */
    LIST,

    /** style 2：两列网格 */
    GRID_2,

    /** style 3：瀑布流（StaggeredGridCells.Adaptive 自适应，等效原竖 2/横 3） */
    MASONRY,

    /** style 4：三列网格 */
    GRID_3,
}

fun Int.toRssArticleListStyle(): RssArticleListStyle = when (this) {
    2 -> RssArticleListStyle.GRID_2
    3 -> RssArticleListStyle.MASONRY
    4 -> RssArticleListStyle.GRID_3
    else -> RssArticleListStyle.LIST
}

/**
 * 底部安全区行为（design-b3-d4-flagship §2.1）：
 * modern 嵌入时消费主底部栏 padding，独立宿主消费导航栏。
 */
enum class ListBottomInset {
    MAIN_BOTTOM_BAR,
    NAVIGATION_BARS,
}
