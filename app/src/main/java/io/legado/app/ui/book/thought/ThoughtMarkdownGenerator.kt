package io.legado.app.ui.book.thought

import io.legado.app.data.entities.BookHighlight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * B16 批注导出：将书籍划线/批注（BookHighlight）生成为 Obsidian 友好的 Markdown
 * 字段映射：bookText≈selectedText / note≈thought / time≈createTime
 */
object ThoughtMarkdownGenerator {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun generate(
        bookName: String,
        bookAuthor: String,
        bookCover: String?,
        bookIntro: String?,
        highlights: List<BookHighlight>
    ): String {
        val sb = StringBuilder()

        sb.appendLine("<font size=4>《$bookName》</font>")
        sb.appendLine()
        sb.appendLine("作者：$bookAuthor")
        sb.appendLine()
        if (!bookCover.isNullOrBlank()) {
            sb.appendLine("<img src=\"$bookCover\" width=\"150\">")
            sb.appendLine()
        }
        sb.appendLine("---")
        sb.appendLine()

        if (!bookIntro.isNullOrBlank()) {
            sb.appendLine("### 书籍简介")
            sb.appendLine()
            sb.appendLine(bookIntro.trim())
            sb.appendLine()
            sb.appendLine("---")
            sb.appendLine()
        }

        val grouped = highlights.groupBy { it.chapterIndex to it.chapterName }
            .toSortedMap(compareBy({ it.first }, { it.second }))

        grouped.forEach { (chapterInfo, chapterHighlights) ->
            val (_, chapterName) = chapterInfo
            sb.appendLine("### $chapterName")
            sb.appendLine()

            chapterHighlights.forEachIndexed { index, highlight ->
                if (highlight.bookText.isNotBlank()) {
                    sb.appendLine(highlight.bookText.trim())
                    sb.appendLine()
                }

                if (highlight.note.isNotBlank()) {
                    sb.appendLine("> ${highlight.note.trim()}")
                    sb.appendLine()
                }

                val timeStr = dateFormat.format(Date(highlight.time))
                sb.appendLine("<font>$timeStr</font>")
                sb.appendLine()

                if (index < chapterHighlights.size - 1) {
                    sb.appendLine("---")
                    sb.appendLine()
                }
            }

            sb.appendLine("---")
            sb.appendLine()
        }

        return sb.toString().trimEnd() + "\n"
    }
}
