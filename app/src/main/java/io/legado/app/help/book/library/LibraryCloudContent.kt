package io.legado.app.help.book.library

import org.jsoup.parser.Parser

object LibraryCloudContent {

    fun toUploadText(content: String): String {
        if (content.isBlank()) return ""
        var text = content.replace("\r\n", "\n").replace('\r', '\n')
        text = removeWholeTagRegex.replace(text, "\n")
        text = imageTagRegex.replace(text, "")
        text = brRegex.replace(text, "\n")
        text = blockCloseRegex.replace(text, "\n")
        text = blockOpenRegex.replace(text, "\n")
        text = tagRegex.replace(text, "")
        text = Parser.unescapeEntities(text, false)
        return text
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    fun meaningfulCharCount(content: String): Int {
        return content.count { !it.isWhitespace() }
    }

    private val removeWholeTagRegex = Regex(
        pattern = "<\\s*(script|style|svg|audio|video|canvas|iframe)\\b[\\s\\S]*?<\\s*/\\s*\\1\\s*>",
        options = setOf(RegexOption.IGNORE_CASE)
    )
    private val imageTagRegex = Regex("<\\s*img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val brRegex = Regex("<\\s*br\\s*/?\\s*>", RegexOption.IGNORE_CASE)
    private val blockCloseRegex = Regex(
        "<\\s*/\\s*(p|div|section|article|li|h[1-6]|tr|blockquote)\\s*>",
        RegexOption.IGNORE_CASE
    )
    private val blockOpenRegex = Regex(
        "<\\s*(p|div|section|article|li|h[1-6]|tr|blockquote)\\b[^>]*>",
        RegexOption.IGNORE_CASE
    )
    private val tagRegex = Regex("<[^>]+>")
}
