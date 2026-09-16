package io.legado.app.help

/**
 * F-P1-2 高亮规则系统（借鉴阅读T）
 * 高亮默认调色板, 作为取色器(ColorPickerDialog)的预设种子。
 * 背景色含 ~0x80 alpha(半透明铺底); 字体色为不透明常用色。
 * 用户可经取色器自定义任意颜色。
 */
object HighlightColors {

    /** 背景填充预设(ARGB, 含 alpha)；R12.4 起由 [HighlightPalette] 按阅读器态提供（唯一真源） */
    val bg: IntArray
        get() = HighlightPalette.bgPresets(HighlightPalette.currentTone())

    /** 字体色预设(ARGB, 不透明)；R12.4 起由 [HighlightPalette] 按阅读器态提供（唯一真源） */
    val text: IntArray
        get() = HighlightPalette.textPresets(HighlightPalette.currentTone())
}
