package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.RssReadRecord

@Dao
interface RssReadRecordDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertRecord(vararg rssReadRecord: RssReadRecord)

    @Query("select * from rssReadRecords order by readTime desc")
    fun getRecords(): List<RssReadRecord>

    @Query("select * from rssReadRecords where origin = :origin order by readTime desc")
    fun getRecordsByOrigin(origin: String): List<RssReadRecord>

    @Query("select * from rssReadRecords where record = :record and origin = :origin")
    fun getRecord( record: String, origin: String): RssReadRecord?

    @Update
    fun update(vararg rssRecord: RssReadRecord)

    @get:Query("select count(1) from rssReadRecords")
    val countRecords: Int

    @Query("select count(1) from rssReadRecords where origin = :origin")
    fun countRecordsByOrigin(origin: String): Int

    @Query("delete from rssReadRecords")
    fun deleteAllRecord()

    @Query("delete from rssReadRecords where origin = :origin")
    fun deleteRecordsByOrigin(origin: String)

    // ==================== W4 / REQ-18：一键全标已读 ====================

    /**
     * 把已有记录但 `read = 0` 的行置为已读（限定 origin 集合；「全部」= 传全部源的 origin）。
     *
     * @return 受影响行数（回执用）
     */
    @Query("update rssReadRecords set read = 1 where read = 0 and origin in (:origins)")
    fun markAllReadByOrigins(origins: List<String>): Int

    /**
     * 为**尚无阅读记录**的文章补一条「已读」记录（限定 origin 集合）。
     *
     * 为什么必须补记录：文章列表的未读判定是 `left join rssReadRecords` + `ifNull(read, 0)`
     * ⇒ **没有记录的文章**永远命中不了 `update`（只 @Update 个已存在行会留一堆「未读」）。
     * 说明：`readTime` 记 `now`（本次标记时刻）、`durPos` 记 0（不臆造阅读进度）。
     *
     * 返回 void：Room 限制 INSERT 查询函数只能返回 void/long，而 INSERT…SELECT 的 rowid 无意义
     * ⇒ 新增行数由调用方以 `countRecords` 前后差值统计（见 `RssReadRecordMarker.markRead`）。
     */
    @Query(
        "insert into rssReadRecords " +
            "(record, title, readTime, read, origin, sort, image, type, durPos, pubDate) " +
            "select link, title, :now, 1, origin, sort, image, type, 0, pubDate from rssArticles " +
            "where origin in (:origins) and not exists " +
            "(select 1 from rssReadRecords r where r.record = rssArticles.link)"
    )
    fun insertMissingAsReadByOrigins(origins: List<String>, now: Long)

}