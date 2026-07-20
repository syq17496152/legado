package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import kotlinx.parcelize.Parcelize


/**
 * 搜索关键词实体
 *
 * 复合主键 (word, type) 设计：隔离书源搜索历史与订阅源搜索历史
 * - type = 0：书源搜索历史（兼容旧数据，Migration 98→99 将旧数据 type 设为 0）
 * - type = 1：订阅源搜索历史（rss-unified-search 新增）
 *
 * 注意：原设计为单字段主键 word + unique index，因无法隔离 type 改为复合主键重建表。
 * 参见 DatabaseMigrations.migration_98_99
 */
@Parcelize
@Entity(tableName = "search_keywords", primaryKeys = ["word", "type"])
data class SearchKeyword(
    /** 搜索关键词 */
    var word: String = "",
    /** 使用次数 */
    var usage: Int = 1,
    /** 最后一次使用时间 */
    var lastUseTime: Long = System.currentTimeMillis(),
    /** 搜索类型：0=书源（兼容旧数据），1=订阅源 */
    @ColumnInfo(defaultValue = "0")
    var type: Int = 0
) : Parcelable
