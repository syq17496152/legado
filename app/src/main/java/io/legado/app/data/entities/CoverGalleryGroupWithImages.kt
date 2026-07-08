package io.legado.app.data.entities

import androidx.room.Embedded
import androidx.room.Relation

/**
 * F-P0-2 备份选择器（借鉴蛋蛋Max）
 * 封面图集分组与图片关系实体
 */
data class CoverGalleryGroupWithImages(
    @Embedded
    val group: CoverGalleryGroup,
    @Relation(
        parentColumn = "id",
        entityColumn = "groupId"
    )
    val images: List<CoverGalleryImage>
)
