package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * F-P0-2 备份选择器（借鉴蛋蛋Max）
 * 封面图集图片实体
 */
@Entity(
    tableName = "cover_gallery_images",
    foreignKeys = [
        ForeignKey(
            entity = CoverGalleryGroup::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("groupId")]
)
data class CoverGalleryImage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val groupId: Long = 0,
    val path: String = "",
    val order: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
