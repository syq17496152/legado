package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * AD-04: 播放历史 Room 实体（跨会话进度恢复）
 *
 * 复合主键：articleUrl + videoUrl（同一文章的同一视频仅保留一条记录）
 */
@Entity(tableName = "playHistories", primaryKeys = ["articleUrl", "videoUrl"])
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
