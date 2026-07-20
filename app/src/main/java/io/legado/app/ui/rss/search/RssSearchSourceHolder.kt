package io.legado.app.ui.rss.search

import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.SearchRssArticle

/**
 * 订阅源搜索多源文章共享 Holder（rss-unified-search 新增）
 *
 * 用于在 [RssSearchAdapter] 点击进入详情页时，将多源文章映射传递给详情页（RssArticleInfoActivity /
 * ReadRssActivity / VideoPlayerActivity），详情页通过"换源"菜单弹出 [ChangeRssArticleSourceDialog]
 * 时读取此 Holder 中的数据。
 *
 * 设计依据：rss-unified-search design.md §5
 *
 * 遗漏点 37 修复：使用 @Volatile 保证跨线程可见性
 * - 写入：RssSearchAdapter 主线程调用 showArticleInfo 时写入
 * - 读取：ChangeRssArticleSourceDialog 内 IO 线程查询订阅源信息时读取
 *
 * 生命周期：
 * - 写入时机：RssSearchAdapter.showArticleInfo 调用 startActivity 跳转详情页之前
 * - 清理时机：RssArticleInfoActivity.onDestroy / ReadRssActivity.onDestroy / VideoPlayerActivity.onDestroy
 */
object RssSearchSourceHolder {

    /**
     * 当前正在查看的文章的多源映射（用于换源）
     *
     * key: 订阅源 sourceUrl（即 RssArticle.origin）
     * value: 该源对应的 RssArticle 实例
     *
     * 为 null 表示当前文章非搜索结果入口进入，不支持换源
     */
    @Volatile
    var articles: HashMap<String, RssArticle>? = null

    /**
     * 当前正在查看的搜索结果文章（rss-unified-search 阶段10 新增）
     *
     * 用于 [RssArticleInfoActivity] 详情页读取文章标题、简介、发布时间等信息。
     * SearchRssArticle 不是 Parcelable，无法通过 Intent 传递，故通过 Holder 共享。
     *
     * 写入时机：RssSearchActivity.showArticleInfo 跳转详情页之前
     * 清理时机：RssArticleInfoActivity.onDestroy
     */
    @Volatile
    var searchArticle: SearchRssArticle? = null

    /**
     * 当前搜索结果列表转 RssArticle 列表（rss-unified-search 阶段10 新增）
     *
     * 用于 [RssArticleInfoActivity] 点击"阅读"按钮跳转播放页时传入 [ReadRss.readRss] 的 rssArticles 参数，
     * 使 [io.legado.app.ui.video.VideoPlayerActivity] 可基于搜索结果列表上/下一个切换文章。
     *
     * 由 [RssSearchActivity.showArticleInfo] 在跳转详情页前从搜索结果 List<SearchRssArticle>
     * 转换（取每个 SearchRssArticle.getDefaultArticle()）。
     *
     * 写入时机：RssSearchActivity.showArticleInfo 跳转详情页之前
     * 清理时机：RssArticleInfoActivity.onDestroy
     */
    @Volatile
    var rssArticles: List<RssArticle>? = null

    /**
     * 清理Holder数据
     *
     * 应在 RssArticleInfoActivity.onDestroy / ReadRssActivity.onDestroy / VideoPlayerActivity.onDestroy 中调用
     */
    fun clear() {
        articles = null
        searchArticle = null
        rssArticles = null
    }

}
