package io.legado.app.ui.book.read.page.entities.column

/**
 * 文字基列
 */
interface TextBaseColumn : BaseColumn {
    override var start: Float
    override var end: Float
    val charData: String
    var selected: Boolean
    var isSearchResult: Boolean

    /**
     * R3：该列是否为段落首行缩进列。
     * 缩进列仍参与章内位置推进，但不承载**装饰类**高亮（方框/下划线/删除线/着重号），
     * 纯色矩形背景填充照常渲染（否则整段底色会在缩进处出现缺口）。
     */
    val isParagraphIndent: Boolean get() = false
}