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
     * @param isTitle     本行是否为标题行(R12.1: 供规则作用域 targetScope 过滤)
     */
    data class LineInput(
        val columnTexts: List<String>,
        val charSize: Int,
        val isParagraphEnd: Boolean,
        val isTitle: Boolean = false
    )

    /**
     * 章节文本 + 逐字符的标题行标记(长度与 [text] 一致)。
     * R12.1: 规则命中后需按「该命中是否落在标题行」决定是否生效。
     */
    data class ChapterText(val text: String, val titleFlags: BooleanArray)

    fun build(lines: List<LineInput>): String = buildChapter(lines).text

    fun buildChapter(lines: List<LineInput>): ChapterText {
        val sb = StringBuilder()
        val flags = ArrayList<Boolean>()
        fun append(text: String, isTitle: Boolean) {
            sb.append(text)
            repeat(text.length) { flags.add(isTitle) }
        }
        for (line in lines) {
            var n = 0
            for (t in line.columnTexts) {
                append(t, line.isTitle)
                n += t.length
            }
            // 补到 charSize, 使跨行偏移与按 charSize 的推进对齐
            // 前置: sum(columnTexts.length) <= charSize(列文字是行 text 的子串)。
            // 若超出, 此处不截断 → 本行长于 charSize → 后续行偏移漂移; 调用方须把非文字列映射为 ""。
            while (n < line.charSize) {
                append(" ", line.isTitle)
                n++
            }
            if (line.isParagraphEnd) append("\n", line.isTitle)
        }
        return ChapterText(sb.toString(), BooleanArray(sb.length) { flags[it] })
    }
}
