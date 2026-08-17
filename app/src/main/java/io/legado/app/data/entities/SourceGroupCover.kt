package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * source-folder-cover: 发现/订阅源分组封面实体
 *
 * 书架分组（BookGroup.cover）可直接换封面；发现/订阅源分组是运行时 SQL 动态聚合，
 * 无独立实体，故新增本表按 (kind, groupName) 记录用户自定义封面。
 * kind 隔离命名空间（book/rss），groupName 可为真实分组名或特殊分组固定 key。
 *
 * 注意：复合主键 (kind, groupName) 已保证唯一并可服务按 kind 前缀查询，
 * 不另建索引（迁移 103→104 的建表 SQL 必须与本实体导出的 schema 一致）。
 */
@Entity(
    tableName = "source_group_covers",
    primaryKeys = ["kind", "groupName"]
)
data class SourceGroupCover(
    /** 命名空间：KIND_BOOK / KIND_RSS */
    @ColumnInfo(name = "kind") val kind: String,
    /** 真实分组名 或 特殊分组固定 key（all_groups/no_group/type_xxx） */
    @ColumnInfo(name = "groupName") val groupName: String,
    /** 自定义封面文件绝对路径（externalFiles/covers/ 下，与书架 GroupEditDialog 封面存储约定一致），为空表示默认渐变+首字 */
    @ColumnInfo(name = "cover") val cover: String? = null
) {

    companion object {
        const val KIND_BOOK = "book"
        const val KIND_RSS = "rss"

        /** 特殊分组固定 key（与本地化文本解耦，见 spec.md） */
        const val KEY_ALL_GROUPS = "all_groups"
        const val KEY_NO_GROUP = "no_group"
        const val KEY_TYPE_TEXT = "type_text"
        const val KEY_TYPE_AUDIO = "type_audio"
        const val KEY_TYPE_IMAGE = "type_image"
        const val KEY_TYPE_FILE = "type_file"
        const val KEY_TYPE_VIDEO = "type_video"
        const val KEY_TYPE_WEB = "type_web"
    }
}