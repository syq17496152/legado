package io.legado.app.help

/**
 * F-P1-2 高亮规则系统（借鉴阅读T）
 * 高亮位置匹配(纯函数, 无 Android 依赖, 可 JVM 单测)。
 * 位置口径与 createBookmark 一致: 行内按 charData 长度累加, 跨行按 charSize 推进, 段末 +1。
 */
object HighlightMatcher {

    /** 一条高亮的章内半开区间 [start, end) 及其样式 */
    data class Range(val start: Int, val end: Int, val style: HighlightStyle)

    /**
     * 一行的最小规格:
     * @param charSize           本行字符数(= TextLine.charSize), 用于跨行推进
     * @param columnCharLengths  每列的 charData 长度(非文字列填 0)
     * @param isParagraphEnd     是否段落结尾(段末额外占 1 个字符位)
     */
    data class LineSpec(val charSize: Int, val columnCharLengths: List<Int>, val isParagraphEnd: Boolean)

    /**
     * @param pageBase 本页起始的章内偏移(= chapter.getReadLength(page.index))
     * @return 每行每列的合并样式;null = 该列无高亮
     */
    fun resolve(
        pageBase: Int,
        lines: List<LineSpec>,
        ranges: List<Range>
    ): List<List<HighlightStyle?>> {
        val result = ArrayList<List<HighlightStyle?>>(lines.size)
        var lineBase = pageBase
        for (line in lines) {
            var colPos = lineBase
            val lineColors = ArrayList<HighlightStyle?>(line.columnCharLengths.size)
            for (len in line.columnCharLengths) {
                val colStart = colPos
                val colEnd = colPos + len
                var acc: HighlightStyle? = null
                for (r in ranges) {
                    // 半开区间相交: [colStart,colEnd) ∩ [r.start,r.end)
                    if (colStart < r.end && colEnd > r.start) {
                        acc = HighlightStyle.merge(acc, r.style)
                    }
                }
                lineColors.add(acc)
                colPos += len
            }
            result.add(lineColors)
            lineBase += line.charSize + if (line.isParagraphEnd) 1 else 0
        }
        return result
    }
}
