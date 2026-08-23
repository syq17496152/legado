package io.legado.app.help.book

import io.legado.app.data.entities.ReplaceRule

data class BookContent(
    val sameTitleRemoved: Boolean,
    val textList: List<String>,
    //起效的替换规则
    val effectiveReplaceRules: List<ReplaceRule>?,
    // archive 段落规则引擎依赖：段落->原文下标映射（默认按序）
    val sourceIndexes: List<Int> = textList.indices.toList()
) {

    override fun toString(): String {
        return textList.joinToString("\n")
    }

}
