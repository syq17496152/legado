package io.legado.app.utils

import org.jsoup.Jsoup

object BookIntroUtils {

    fun listIntro(raw: String?): String? {
        val intro = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val text = when {
            intro.startsWith("<useweb>", ignoreCase = true) -> {
                intro.unwrapTaggedIntro("useweb")?.htmlToPlainText()
            }
            intro.startsWith("<usehtml>", ignoreCase = true) -> {
                intro.unwrapTaggedIntro("usehtml")?.htmlToPlainText()
            }
            intro.startsWith("<md>", ignoreCase = true) -> {
                intro.unwrapTaggedIntro("md")?.markdownToPlainText()
            }
            else -> intro.htmlToPlainText()
        }
        return text
            ?.replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
            ?.replace(Regex("\\n{3,}"), "\n\n")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.unwrapTaggedIntro(tag: String): String? {
        val start = Regex("^\\s*<${Regex.escape(tag)}\\b[^>]*>", RegexOption.IGNORE_CASE)
            .find(this)
            ?: return null
        val end = Regex("</\\s*${Regex.escape(tag)}\\s*>", RegexOption.IGNORE_CASE)
            .findAll(this)
            .lastOrNull()
            ?.range
            ?.first
            ?: -1
        return substring(
            (start.range.last + 1).coerceAtMost(length),
            if (end > start.range.last) end else length
        )
    }

    private fun String.htmlToPlainText(): String {
        val doc = Jsoup.parse(this)
        doc.select("script,style,noscript,iframe,canvas,svg,video,audio").remove()
        return doc.text()
    }

    private fun String.markdownToPlainText(): String {
        return replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
            .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("[*_`>#~\\-]+"), " ")
            .htmlToPlainText()
    }
}