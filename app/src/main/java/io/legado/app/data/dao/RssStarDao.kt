package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.RssStar
import kotlinx.coroutines.flow.Flow

@Dao
interface RssStarDao {

    @get:Query("select * from rssStars order by starTime desc")
    val all: List<RssStar>

    @Query("select `group` from rssStars group by `group` order by `group`")
    fun flowGroups(): Flow<List<String>>

    // ui-theme-gap-audit R1：收藏列表主查询不再 select 大字段（image/content/description/variable），
    // 封面图改为列表项按需单行 getImage 查询（与订阅文章列表保持一致），
    // 防止大图 base64（实测单行最大 ~395KB）多行 select 挤满 CursorWindow 2MB 窗口导致读取失败。
    // 详情页仍走 get() select * 单行查询，image 完整保留不丢失。
    @Query(
        """select origin, sort, title, starTime, link, pubDate, `group`, type, durPos
        from rssStars where `group` = :group order by starTime desc"""
    )
    fun flowByGroup(group: String): Flow<List<RssStar>>

    // 收藏封面图按需单行加载（单行远小于 CursorWindow 2MB，安全，图完整显示）
    @Query("select image from rssStars where origin = :origin and link = :link limit 1")
    suspend fun getImage(origin: String, link: String): String?

    @Query("select * from rssStars where origin = :origin and link = :link")
    fun get(origin: String, link: String): RssStar?

    @Query("select * from rssStars order by starTime desc")
    fun liveAll(): Flow<List<RssStar>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg rssStar: RssStar)

    @Update
    fun update(vararg rssStar: RssStar)

    @Query("update rssStars set origin = :origin where origin = :oldOrigin")
    fun updateOrigin(origin: String, oldOrigin: String)

    @Query("delete from rssStars where origin = :origin")
    fun delete(origin: String)

    @Query("delete from rssStars where origin = :origin and link = :link")
    fun delete(origin: String, link: String)

    @Query("delete from rssStars where `group` = :group")
    fun deleteByGroup(group: String)

    @Query("delete from rssStars")
    fun deleteAll()
}