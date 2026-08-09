package io.legado.app.ui.book.read.config

import io.legado.app.help.HighlightStyle
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

/**
 * F-P0-2 备份选择器（借鉴蛋蛋Max）
 * 高亮规则数据类，使用 SharedPreferences 存储而非 Room 实体
 * 简化说明：跳过蛋蛋Max 的 TextLine 背景图迁移逻辑 | 已知上限：不支持背景图迁移 | 升级路径：后续移植 TextLine 扩展
 *
 * F-P1-2 高亮规则系统：新增 isRegex/styleJson/timeoutMillisecond 字段 + toHighlightStyle() 映射（方案 A+）
 * 旧字段保留不变，styleJson 优先，无 styleJson 时从旧字段降级映射
 */
data class HighlightRule(
    var id: String = System.currentTimeMillis().toString(),
    var name: String = "",
    var pattern: String = "",
    var sampleText: String = "",
    var group: String = HighlightRuleGroupStore.DEFAULT_GROUP,
    var targetScope: Int = TARGET_ALL,
    var enabled: Boolean = true,
    var textColor: Int? = null,
    var underlineMode: Int = 0,
    var underlineColor: Int? = null,
    var underlineWidth: Float = 1f,
    var underlineOffset: Float = 2f,
    var underlineSvgPath: String? = null,
    var bgImage: String? = null,
    var bgImageFit: Int = 0,
    var bgImageScale: Float = 1f,
    // F-P1-2 新增字段
    var isRegex: Boolean = false,
    var styleJson: String? = null,
    var timeoutMillisecond: Long = 3000L,
    // B15 新增：捕获组样式模板（如 <b><font color="red">$1</font></b>）与点号匹配换行
    var replacement: String = "",
    var isDotAll: Boolean = false,
) {

    fun styleSummary(): String {
        val parts = ArrayList<String>(4)
        parts.add(targetScopeLabel())
        textColor?.let {
            parts.add("字色 ${it.toHexColor()}")
        }
        if (underlineMode != 0) {
            parts.add(
                when (underlineMode) {
                    1 -> "实线下划线"
                    2 -> "虚线下划线"
                    3 -> "波浪下划线"
                    4 -> "双下划线"
                    5 -> "自定义SVG"
                    else -> "下划线"
                } + underlineColor?.let { " ${it.toHexColor()}" }.orEmpty()
            )
        }
        if (!bgImage.isNullOrBlank()) {
            parts.add(
                when (bgImageFit) {
                    1 -> "背景图(拉伸)"
                    2 -> "背景图(裁剪)"
                    else -> "背景图(平铺)"
                }
            )
        }
        if (parts.isEmpty()) {
            parts.add("无样式")
        }
        return parts.joinToString(" / ")
    }

    fun targetScopeLabel(): String {
        return when (targetScope) {
            TARGET_TITLE -> "作用于标题"
            TARGET_BODY -> "作用于正文"
            else -> "作用于全部"
        }
    }

    fun displayPattern(): String {
        return pattern.ifBlank { ".*" }
    }

    fun normalizedSampleText(): String {
        return sampleText.ifBlank {
            "她轻声说：\"今晚就出发。\"\n最近在重读《百年孤独》（纪念版），节奏依然很稳。"
        }
    }

    /** F-P1-2: 规则显示名(name 为空时回退到 pattern) */
    fun getDisplayName(): String = name.ifBlank { pattern }

    fun copyWithNewId(): HighlightRule {
        return copy(id = "${System.currentTimeMillis()}_${name.hashCode()}")
    }

    /**
     * F-P1-2 方案 A+ 映射: 将 HighlightRule 转为 HighlightStyle
     * 优先读 styleJson(新通道), 没有则从旧字段降级映射
     */
    fun toHighlightStyle(): HighlightStyle {
        // 优先: styleJson 完整样式
        if (!styleJson.isNullOrBlank()) {
            GSON.fromJsonObject<HighlightStyle>(styleJson).getOrNull()?.let { return it }
        }
        // 降级: 从旧字段映射
        val underline = underlineModeToKind()?.let { kind ->
            HighlightStyle.Underline(kind = kind, color = underlineColor ?: 0)
        }
        return HighlightStyle(
            fill = 0,
            textColor = textColor ?: 0,
            underline = underline
        )
    }

    /** 旧 underlineMode(1-5) 映射到 HighlightStyle.Kind */
    private fun underlineModeToKind(): HighlightStyle.Kind? {
        return when (underlineMode) {
            1 -> HighlightStyle.Kind.SOLID
            2 -> HighlightStyle.Kind.DASHED
            3 -> HighlightStyle.Kind.WAVY
            4 -> HighlightStyle.Kind.DOUBLE
            5 -> HighlightStyle.Kind.DOTTED // 旧 SVG 自定义映射为点线
            else -> null
        }
    }

    companion object {
        const val TARGET_ALL = 0
        const val TARGET_TITLE = 1
        const val TARGET_BODY = 2

        fun Int.toHexColor(): String = String.format("#%08X", this)
    }
}
