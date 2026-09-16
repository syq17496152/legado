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
        val parts = ArrayList<String>(6)
        parts.add(targetScopeLabel())
        // R12.2：摘要与阅读页渲染同源——统一由 toHighlightStyle() 派生（styleJson 优先，legacy 仅作降级输入），
        // 修复「改过样式后列表仍显示旧色」的不一致
        val style = toHighlightStyle()
        if (style.fill != 0) {
            parts.add("背景 ${style.fill.toHexColor()}")
        }
        if (style.textColor != 0) {
            parts.add("字色 ${style.textColor.toHexColor()}")
        }
        style.underline?.let { u ->
            val kindLabel = when (u.kind) {
                HighlightStyle.Kind.SOLID -> "实线下划线"
                HighlightStyle.Kind.DASHED -> "虚线下划线"
                HighlightStyle.Kind.WAVY -> "波浪下划线"
                HighlightStyle.Kind.DOUBLE -> "双下划线"
                HighlightStyle.Kind.DOTTED -> "点线下划线"
            }
            parts.add(kindLabel + if (u.color != 0) " ${u.color.toHexColor()}" else "")
            // 仅展示显式设置过的线宽/线距（未设置为 null）
            u.width?.let { parts.add("线宽 $it") }
            u.distance?.let { parts.add("线距 $it") }
        }
        style.strike?.let { parts.add("删除线" + if (it.color != 0) " ${it.color.toHexColor()}" else "") }
        if (style.box != null) parts.add("方框")
        if (style.emphasis != null) parts.add("着重号")
        if (style.bold) parts.add("加粗")
        if (style.italic) parts.add("斜体")
        if (style.fontPath.isNotEmpty()) parts.add("自定义字体")
        if (!bgImage.isNullOrBlank()) {
            parts.add(
                when (bgImageFit) {
                    1 -> "背景图(拉伸)"
                    2 -> "背景图(裁剪)"
                    else -> "背景图(平铺)"
                }
            )
        }
        if (parts.size <= 1) {
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
            // 健壮性：GSON 反序列化缺失字段会写入 null（如 fontPath），必须兜底后再返回
            GSON.fromJsonObject<HighlightStyle>(styleJson).getOrNull()?.let { return it.sanitized() }
        }
        // 降级: 从旧字段映射
        val underline = underlineModeToKind()?.let { kind ->
            HighlightStyle.Underline(
                kind = kind,
                color = underlineColor ?: 0,
                // 约定「默认值 = 未设置」（与 HighlightRuleStore.healBuiltin 同口径）：
                // 等于默认值即视为未设置（null），否则会把默认值写死成"用户显式设置"
                width = underlineWidth.takeIf { it != HighlightStyle.Underline.DEFAULT_WIDTH },
                distance = underlineOffset.takeIf { it != HighlightStyle.Underline.DEFAULT_DISTANCE }
            )
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
