package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.PlayHistory

/**
 * AD-04: 播放历史 DAO
 */
@Dao
interface PlayHistoryDao {

    @Query("select * from playHistories where articleUrl = :articleUrl and videoUrl = :videoUrl")
    fun get(articleUrl: String, videoUrl: String): PlayHistory?

    /**
     * F183（2026-09-21，video/video-player 选集三态）：某文章下**已有播放记录**的集原始 URL 集合。
     *
     * 键与保存侧一致——`VideoPlay.historyKeyUrl`（嗅探前原始 URL；多线路多集模式下由
     * `playRssEpisode` 覆写为 `RssEpisode.url`，故可直接与选集列表的 `episode.url` 比对）。
     * 纯查询新增，**不涉及 schema 变更** ⇒ 不需要数据库迁移。
     */
    @Query("select videoUrl from playHistories where articleUrl = :articleUrl")
    fun getWatchedVideoUrls(articleUrl: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg history: PlayHistory)

    @Query("delete from playHistories where articleUrl = :articleUrl and videoUrl = :videoUrl")
    fun delete(articleUrl: String, videoUrl: String)

    @Query("delete from playHistories")
    fun clearAll()
}
