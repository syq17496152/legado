package io.legado.app.help

import io.legado.app.help.HighlightStyle.Deco
import io.legado.app.help.HighlightStyle.Kind
import io.legado.app.help.HighlightStyle.Underline

/**
 * F-P1-2 高亮规则系统（借鉴阅读T）
 * 内置高亮样式预设(面板一键套用)
 */
object HighlightStyles {
    val presets: List<HighlightStyle> = listOf(
        HighlightStyle(fill = 0x80FFF176.toInt()),                                  // 黄底
        HighlightStyle(fill = 0x804FC3F7.toInt()),                                  // 蓝底
        HighlightStyle(underline = Underline(Kind.WAVY, 0xFFE53935.toInt())),       // 红波浪
        HighlightStyle(underline = Underline(Kind.SOLID, 0xFF1E88E5.toInt()), bold = true), // 蓝下划线+加粗
        HighlightStyle(strike = Deco(0xFF9E9E9E.toInt())),                          // 删除线
        HighlightStyle(emphasis = Deco(0xFFE53935.toInt()))                         // 着重号
    )
}
