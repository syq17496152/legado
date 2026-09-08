package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 多角色选角模板（AD-09 范式模板配置层）
 * 内置模板（builtin=true）由 assets/defaultData/tts/castingTemplates.json 经 DefaultData 链导入，
 * 幂等键=templateId；用户模板经模板管理页 CRUD / JSON 导入导出。
 */
@Entity(tableName = "ttsCastingTemplates")
data class TtsCastingTemplate(
    @PrimaryKey
    val id: String,
    @ColumnInfo(defaultValue = "")
    var name: String = "",
    @ColumnInfo(defaultValue = "0")
    var builtin: Boolean = false,
    @ColumnInfo(defaultValue = "1")
    var enabled: Boolean = true,
    @ColumnInfo(name = "sortOrder", defaultValue = "0")
    var sortOrder: Int = 0,
    // 规则数组 JSON：[{tag, match:{type: builtin_quote|regex|keyword, pattern}, sourceJson, prosody:{rate,pitch,volume}}]
    @ColumnInfo(defaultValue = "")
    var rulesJson: String = "",
    // 兜底声源（SpeechRoute JSON）：声源失败段级回退目标
    @ColumnInfo(defaultValue = "")
    var fallbackSourceJson: String = "",
    @ColumnInfo(defaultValue = "0")
    var lastUpdateTime: Long = 0
)
