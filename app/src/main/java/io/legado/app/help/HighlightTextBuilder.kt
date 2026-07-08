package io.legado.app.help

/**
 * F-P1-2 高亮规则系统（借鉴阅读T）
 * 把已排版章节重建为"字符串偏移 == 章内 pos"的文本(纯函数, JVM 可测)。
 * 口径与 TextPage.getPosByLineColumn 一致: 行内累加列 charData 长度、补到 charSize、段末 +1(以 '\n' 表示)。
 * 于是匹配偏移可直接当 HighlightMatcher.Range 用, 无需偏移映射表。
 */
object HighlightTextBuilder {

    /**
     * @param columnTexts 本行各列文字(非文字列传 ""), 按列序
     * @param charSize    本行字符数(= TextLine.charSize), 用于跨行推进
     * @param isParagraphEnd 段末(额外占 1 个字符位)
     */
    data class LineInput(val columnTexts: List<String>, val charSize: Int, val isParagraphEnd: Boolean)

    fun build(lines: List<LineInput>): String {
        val sb = StringBuilder()
        for (line in lines) {
            var n = 0
            for (t in line.columnTexts) {
                sb.append(t)
                n += t.length
            }
            // 补到 charSize, 使跨行偏移与按 charSize 的推进对齐
            // 前置: sum(columnTexts.length) <= charSize(列文字是行 text 的子串)。
            // 若超出, 此处不截断 → 本行长于 charSize → 后续行偏移漂移; 调用方须把非文字列映射为 ""。
            while (n < line.charSize) {
                sb.append(' ')
                n++
            }
            if (line.isParagraphEnd) sb.append('\n')
        }
        return sb.toString()
    }
}
