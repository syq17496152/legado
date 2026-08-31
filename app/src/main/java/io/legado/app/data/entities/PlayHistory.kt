package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * AD-04: 播放历史 Room 实体（跨会话进度恢复）
 *
 * 复合主键：articleUrl + videoUrl + rssSourceId
 * （video-sniff-403-and-rss-classic-fix 4.8e/方案A：主键纳入 rssSourceId，
 * 同一文章同视频按订阅源隔离进度——同一 m3u8 被多线路/多源复用时不再互相覆盖。
 * 对应迁移：DatabaseMigrations.migration_108_109）
 */
@Entity(tableName = "playHistories", primaryKeys = ["articleUrl", "videoUrl", "rssSourceId"])
data class PlayHistory(
    var articleUrl: String = "",
    var videoUrl: String = "",
    @ColumnInfo(defaultValue = "0")
    var position: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    var duration: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    var lastPlayTime: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "")
    var rssSourceId: String = ""
)
