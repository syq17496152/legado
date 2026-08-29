package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.RssArticle
import kotlinx.coroutines.flow.Flow

@Dao
interface RssArticleDao {

    @Query("select * from rssArticles where origin = :origin and link = :link and sort = :sort")
    fun get(origin: String, link: String, sort: String): RssArticle?

    @Query("select * from rssArticles where origin = :origin and link = :link")
    fun getByLink(origin: String, link: String): RssArticle?

    /**
     * 批量查询指定 origin 下已读的 link 列表（rss-unified-search 新增）
     *
     * 用于订阅源统一搜索结果已读状态判断：
     * - 搜索结果来自网络返回，read 字段默认 false
     * - 通过此方法查询 rssArticles 表中已读的文章 link，批量判断已读状态
     *
     * @param origin 订阅源 sourceUrl
     * @param links 搜索结果中的文章 link 列表
     * @return 已读的 link 列表（子集）
     */
    @Query("select link from rssArticles where origin = :origin and link in (:links) and read = 1")
    fun getReadLinks(origin: String, links: List<String>): List<String>

    // ui-theme-gap-audit R1：列表封面图按需单行加载（主查询不含 image，见 flowByOriginSort）
    // 单行 image 最大 ~395KB 远小于 CursorWindow 2MB，安全；详情/封面图完整保留不裁剪
    @Query("select image from rssArticles where origin = :origin and link = :link limit 1")
    suspend fun getImage(origin: String, link: String): String?

    // R4.3 修复：去掉 t1.content 字段（大文章 content 超 CursorWindow 2MB 限制）
    // app-stability-round2 P1-1 修复：再去掉 t1.description 字段（部分源 description 也可能超大触发 SQLiteBlobTooBigException）
    // rss-parse-optimization P0 修复：再去掉 t1.variable 字段（部分源 variable JSON 超大导致 CursorWindow 溢出 30+次/会话）
    // ui-theme-gap-audit R1 修复：image 字段部分源存 base64 数据图（实测 max 395KB/36 行 >100KB），
    // 多行大图一次性 select 挤满 CursorWindow 2MB 窗口 → 单行分配失败 → 订阅文章界面获取数据失败。
    // 列表主查询不再 select image（大图改为列表项按需单行 getImage 查询，单行远小于窗口，安全且图完整显示）
    // 列表界面 content/description/variable 均未使用（RssArticlesAdapter 仅用 title/pubDate/image/read/origin），详情页通过 get() 查询完整字段
    @Query(
        """select t1.link, t1.sort, t1.origin, t1.`order`, t1.title,
            t1.`group`, t1.pubDate, t1.type, t1.durPos, ifNull(t2.read, 0) as read
        from rssArticles as t1 left join rssReadRecords as t2
        on t1.link = t2.record  where t1.origin = :origin and t1.sort = :sort
        order by `order` desc"""
    )
    fun flowByOriginSort(origin: String, sort: String): Flow<List<RssArticle>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg rssArticle: RssArticle)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun append(vararg rssArticle: RssArticle)

    @Query("delete from rssArticles where origin = :origin and sort = :sort and `order` < :order")
    fun clearOld(origin: String, sort: String, order: Long)

    @Update
    fun update(vararg rssArticle: RssArticle)

    @Query("update rssArticles set origin = :origin where origin = :oldOrigin")
    fun updateOrigin(origin: String, oldOrigin: String)

    @Query("delete from rssArticles where origin = :origin")
    fun delete(origin: String)

}