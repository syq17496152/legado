package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

// precise-manage: 网址记录表（借鉴 Legado_Max UrlRecord）
@Parcelize
@Entity(
    tableName = "url_records",
    indices = [(Index(value = ["timestamp"])), (Index(value = ["domain"]))]
)
data class UrlRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val domain: String,
    val method: String,
    val sourceName: String? = null,
    val sourceUrl: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val responseCode: Int = 0,
    val duration: Long = 0,
    val requestBody: String? = null,
    val errorMsg: String? = null
) : Parcelable