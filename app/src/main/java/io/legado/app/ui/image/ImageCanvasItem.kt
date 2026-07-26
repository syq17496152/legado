package io.legado.app.ui.image

/**
 * 图片垂直画布数据结构（V2 B-3 修订：sealed class 替代 MutableList<String>）
 *
 * 用于 ImagePlay.allImageUrls 和 ImageCanvasAdapter，支持图片项 + 文章分隔符混合数据结构。
 *
 * - [ImageItem]：单张图片，含 URL + 所属文章索引 + 文章内图片索引
 * - [ArticleDivider]：文章分隔符，标记新文章开始（显示文章标题）
 */
sealed class ImageCanvasItem {

    /**
     * 图片项
     *
     * @param url 图片 URL
     * @param articleIndex 所属文章索引（对应 ImagePlay.rssArticles 的下标）
     * @param imageIndex 文章内图片索引（从 0 开始）
     */
    data class ImageItem(
        val url: String,
        val articleIndex: Int,
        val imageIndex: Int
    ) : ImageCanvasItem()

    /**
     * 文章分隔符
     *
     * @param articleIndex 文章索引
     * @param articleTitle 文章标题（从 rssArticles[articleIndex].title 获取，可为空）
     */
    data class ArticleDivider(
        val articleIndex: Int,
        val articleTitle: String?
    ) : ImageCanvasItem()
}
