package io.legado.app.help

import io.legado.app.help.HighlightStyle.Deco
import io.legado.app.help.HighlightStyle.Kind
import io.legado.app.help.HighlightStyle.Underline

/**
 * F-P1-2 高亮规则系统（借鉴阅读T）
 * 内置高亮样式预设(面板一键套用)
 */
object HighlightStyles {

    /**
     * 内置高亮样式预设（面板一键套用）。
     *
     * R12.4：色值由 [HighlightPalette] 按**当前阅读器态**（白天/夜间/墨水屏）提供，
     * 不再各自硬编码，保证与取色器预设、内置规则默认色同源。
     */
    val presets: List<HighlightStyle>
        get() {
            val tone = HighlightPalette.currentTone()
            val fills = HighlightPalette.bgPresets(tone)
            val texts = HighlightPalette.textPresets(tone)
            // 墨水屏无半透明填充概念 → 该态改用下划线/删除线等线型表达
            val yellow = fills.getOrNull(0) ?: 0
            val blue = fills.getOrNull(2) ?: 0
            val red = texts.getOrNull(0)
            val blueLine = texts.getOrNull(1)
            val gray = texts.getOrNull(4)
            return if (tone == HighlightPalette.Tone.EINK) {
                listOf(
                    HighlightStyle(underline = Underline(Kind.SOLID, 0), bold = true),
                    HighlightStyle(underline = Underline(Kind.WAVY, 0)),
                    HighlightStyle(strike = Deco(0)),
                    HighlightStyle(emphasis = Deco(0)),
                    HighlightStyle(box = Deco(0)),
                    HighlightStyle(underline = Underline(Kind.DOUBLE, 0))
                )
            } else {
                listOf(
                    HighlightStyle(fill = yellow),
                    HighlightStyle(fill = blue),
                    HighlightStyle(underline = Underline(Kind.WAVY, red ?: 0)),
                    HighlightStyle(underline = Underline(Kind.SOLID, blueLine ?: 0), bold = true),
                    HighlightStyle(strike = Deco(gray ?: 0)),
                    HighlightStyle(emphasis = Deco(red ?: 0))
                )
            }
        }
}
