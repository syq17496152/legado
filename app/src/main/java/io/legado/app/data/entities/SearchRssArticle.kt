package io.legado.app.data.entities

/**
 * 订阅源统一搜索结果内存包装类（不持久化到 Room）
 *
 * 参考 [SearchBook] 的多源聚合设计：
 * - [origins] 记录所有返回该文章的订阅源 sourceUrl 集合
 * - [originArticles] 记录每个源对应的 [RssArticle] 实例，用于换源时取用
 *
 * 与 [SearchKeyword.type] 的区别：
 * - SearchRssArticle.type：文章类型（0=网页, 1=图片, 2=视频），对应 [RssArticle.type]
 * - SearchKeyword.type：搜索历史类型（0=书源, 1=订阅源）
 *
 * 设计依据：rss-unified-search spec.md FR-02 / design.md §2
 */
data class SearchRssArticle(
    var title: String = "",
    var pubDate: String? = null,
    var description: String? = null,
    var image: String? = null,
    /**文章类型 0=网页, 1=图片, 2=视频（对应 RssArticle.type，非 SearchKeyword.type）**/
    var type: Int = 0,
    /**已读状态（阻塞点 15 修复：通过 RssSearchModel.mergeItems 批量查询 rssArticles 表判断）**/
    var isRead: Boolean = false,
    /**所有来源的 sourceUrl 集合（参考 SearchBook.origins）**/
    val origins: LinkedHashSet<String> = linkedSetOf(),
    /**每个源对应的 RssArticle 实例（用于换源时取用）**/
    val originArticles: HashMap<String, RssArticle> = hashMapOf()
) {

    /**
     * 添加来源
     *
     * @param origin 订阅源 sourceUrl
     * @param article 该源对应的 RssArticle 实例
     */
    fun addOrigin(origin: String, article: RssArticle) {
        origins.add(origin)
        originArticles[origin] = article
    }

    /**
     * 去重 key：title + pubDate（参考书源 name + author 策略）
     *
     * 注意：pubDate 为 null 时 key 形如 "标题|"，相同标题+null pubDate 的文章会聚合
     */
    fun deduplicationKey(): String = "$title|${pubDate ?: ""}"

    /**
     * 获取默认源（第一个）的 RssArticle，用于进入详情页
     */
    fun getDefaultArticle(): RssArticle? = origins.firstOrNull()?.let { originArticles[it] }

}
