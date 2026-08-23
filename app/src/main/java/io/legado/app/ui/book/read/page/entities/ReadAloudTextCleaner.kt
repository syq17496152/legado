package io.legado.app.ui.book.read.page.entities

import io.legado.app.model.localBook.EpubFile
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import org.jsoup.parser.Parser

object ReadAloudTextCleaner {

    fun cleanVisibleText(text: String, keepLineBreaks: Boolean = true): String {
        if (text.isBlank()) return ""
        var result = text
        if (result.trimStart().startsWith(EpubFile.NATIVE_CONTENT_FLAG, ignoreCase = true)) {
            return ""
        }
        result = useHtmlBlockRegex.replace(result) { match ->
            match.groupValues.getOrNull(1).orEmpty()
        }
        result = useHtmlTagRegex.replace(result, "")
        result = stripReadAloudMarkup(result)
        result = Parser.unescapeEntities(result, false)
        result = stripReadAloudMarkup(result)
        result = result
            .replace(ChapterProvider.srcReplaceChar.toString(), "")
            .replace(ChapterProvider.srcReplacementChar.toString(), "")
            .replace(ChapterProvider.reviewChar.toString(), "")
            .replace(internalPlaceholderRegex, "")
            .replace(buttonActionRegex, "")
        return if (keepLineBreaks) {
            result
                .replace(horizontalSpaceRegex, " ")
                .replace(lineBreakSpaceRegex, "\n")
                .replace(multiLineBreakRegex, "\n\n")
                .trim()
        } else {
            result
                .replace(allSpaceRegex, " ")
                .trim()
        }
    }

    fun cleanInlineText(text: String): String {
        return cleanVisibleText(text, keepLineBreaks = false)
    }

    private val useHtmlBlockRegex = Regex(
        "<usehtml[^>]*>([\\s\\S]*?)</usehtml>",
        RegexOption.IGNORE_CASE
    )
    private val useHtmlTagRegex = Regex(
        "</?usehtml[^>]*>",
        RegexOption.IGNORE_CASE
    )
    private val imageTagRegex = Regex(
        "<img\\b[^>\\r\\n]*(?:>|$)",
        RegexOption.IGNORE_CASE
    )
    private val breakTagRegex = Regex(
        "</?(?:p|div|br|hr|h[1-6]|li|tr|table|section|article|dd|dl)[^>]*>",
        RegexOption.IGNORE_CASE
    )
    private val htmlTagRegex = Regex(
        "</?[a-zA-Z][^>]*>",
        RegexOption.IGNORE_CASE
    )
    private val internalPlaceholderRegex = Regex("[\\u88AE\\u7962\\uA9C1\\uFFFC]")
    private val buttonActionRegex = Regex("@onclick:[^\\r\\n]*", RegexOption.IGNORE_CASE)
    private val horizontalSpaceRegex = Regex("[\\t\\x0B\\f\\r ]+")
    private val lineBreakSpaceRegex = Regex(" *\\n+ *")
    private val multiLineBreakRegex = Regex("\\n{3,}")
    private val allSpaceRegex = Regex("\\s+")

    private fun stripReadAloudMarkup(text: String): String {
        return text
            .let { imageTagRegex.replace(it, "") }
            .let { breakTagRegex.replace(it, "\n") }
            .let { htmlTagRegex.replace(it, "") }
    }
}
