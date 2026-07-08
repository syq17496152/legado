package io.legado.app.help

/**
 * F-P1-2 高亮规则系统（借鉴阅读T）
 * 高亮默认调色板, 作为取色器(ColorPickerDialog)的预设种子。
 * 背景色含 ~0x80 alpha(半透明铺底); 字体色为不透明常用色。
 * 用户可经取色器自定义任意颜色。
 */
object HighlightColors {

    /** 背景填充预设(ARGB, 含 alpha) */
    val bg = intArrayOf(
        0x80FFF176.toInt(), // 黄
        0x80AED581.toInt(), // 绿
        0x804FC3F7.toInt(), // 蓝
        0x80F06292.toInt(), // 粉
        0x80FFB74D.toInt()  // 橙
    )

    /** 字体色预设(ARGB, 不透明) */
    val text = intArrayOf(
        0xFFD32F2F.toInt(), // 红
        0xFF1976D2.toInt(), // 蓝
        0xFF388E3C.toInt(), // 绿
        0xFFF57C00.toInt(), // 橙
        0xFF7B1FA2.toInt()  // 紫
    )
}
