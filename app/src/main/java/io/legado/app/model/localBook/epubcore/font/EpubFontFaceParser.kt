package io.legado.app.model.localBook.epubcore.font

import io.legado.app.model.localBook.epubcore.archive.EpubPath
import io.legado.app.model.localBook.epubcore.style.EpubCss
import java.util.Locale

object EpubFontFaceParser {

    fun parse(cssHref: String, css: String): List<EpubFontFace> {
        if (css.isBlank()) return emptyList()
        val cleanCss = css.replace(cssCommentRegex, "")
        val faces = arrayListOf<EpubFontFace>()
        var index = 0
        while (index < cleanCss.length) {
            val at = cleanCss.indexOf("@font-face", index, ignoreCase = true)
            if (at < 0) break
            val blockStart = cleanCss.indexOf('{', at + "@font-face".length)
            if (blockStart < 0) break
            val blockEnd = cleanCss.findMatchingCssBrace(blockStart)
            if (blockEnd < 0) break
            parseBlock(cssHref, cleanCss.substring(blockStart + 1, blockEnd))?.let(faces::add)
            index = blockEnd + 1
        }
        return faces
    }

    fun parseImports(cssHref: String, css: String): List<String> {
        if (css.isBlank()) return emptyList()
        val cleanCss = css.replace(cssCommentRegex, "")
        val imports = arrayListOf<String>()
        var index = 0
        while (index < cleanCss.length) {
            val at = cleanCss.indexOf("@import", index, ignoreCase = true)
            if (at < 0) break
            val statementStart = at + "@import".length
            val statementEnd = cleanCss.findCssStatementEnd(statementStart)
            cleanCss.substring(statementStart, statementEnd)
                .extractImportUrl()
                ?.takeIf { href ->
                    !href.startsWith("data:", ignoreCase = true) &&
                        !href.startsWith("http://", ignoreCase = true) &&
                        !href.startsWith("https://", ignoreCase = true)
                }
                ?.let { href -> imports += EpubPath.resolve(cssHref, href) }
            index = if (statementEnd < cleanCss.length) statementEnd + 1 else cleanCss.length
        }
        return imports
    }

    private fun parseBlock(cssHref: String, block: String): EpubFontFace? {
        val declarations = EpubCss.parseDeclarations(block).associateBy { it.name.lowercase(Locale.ROOT) }
        val family = declarations["font-family"]?.value?.cleanFontFamily() ?: return null
        val sources = declarations["src"]?.value?.parseSources(cssHref).orEmpty()
        if (sources.isEmpty()) return null
        return EpubFontFace(
            family = family,
            sources = sources,
            weight = declarations["font-weight"]?.value.toFontWeightRange(),
            style = declarations["font-style"]?.value.toFontStyle()
        )
    }

    private fun String.parseSources(cssHref: String): List<EpubFontSource> {
        return splitCommaList()
            .mapNotNull { source ->
                val href = source.extractUrl() ?: return@mapNotNull null
                if (href.startsWith("data:", ignoreCase = true)) return@mapNotNull null
                if (href.startsWith("http://", ignoreCase = true) || href.startsWith("https://", ignoreCase = true)) {
                    return@mapNotNull null
                }
                EpubFontSource(
                    href = EpubPath.stripFragment(EpubPath.resolve(cssHref, href)),
                    format = source.extractFormat()
                )
            }
    }

    private fun String.extractUrl(): String? {
        val start = indexOf("url(", ignoreCase = true)
        if (start < 0) return null
        var quote: Char? = null
        for (index in start + 4 until length) {
            val char = this[index]
            if (quote != null) {
                if (char == quote && getOrNull(index - 1) != '\\') quote = null
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                ')' -> return substring(start + 4, index).trim().trim('\'', '"')
                    .takeIf { it.isNotBlank() && !it.equals("none", true) }
            }
        }
        return null
    }

    private fun String.extractFormat(): String? {
        val start = indexOf("format(", ignoreCase = true)
        if (start < 0) return null
        val end = indexOf(')', start + 7)
        if (end < 0) return null
        return substring(start + 7, end)
            .trim()
            .trim('\'', '"')
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotBlank() }
    }

    private fun String.extractImportUrl(): String? {
        extractUrl()?.let { return it }
        val clean = trimStart()
        val quote = clean.firstOrNull()?.takeIf { it == '\'' || it == '"' } ?: return null
        for (index in 1 until clean.length) {
            if (clean[index] == quote && clean.getOrNull(index - 1) != '\\') {
                return clean.substring(1, index).trim()
                    .takeIf { it.isNotBlank() && !it.equals("none", true) }
            }
        }
        return null
    }

    private fun String?.toFontWeightRange(): IntRange {
        val clean = this?.trim()?.lowercase(Locale.ROOT) ?: return 400..400
        if (clean == "normal") return 400..400
        if (clean == "bold") return 700..700
        val parts = EpubCss.splitValueList(clean)
            .mapNotNull { it.toIntOrNull()?.coerceIn(1, 1000) }
        return when (parts.size) {
            0 -> 400..400
            1 -> parts[0]..parts[0]
            else -> parts.minOrNull()!!..parts.maxOrNull()!!
        }
    }

    private fun String?.toFontStyle(): EpubFontStyle {
        val clean = this?.trim()?.lowercase(Locale.ROOT) ?: return EpubFontStyle.Normal
        return when {
            clean.startsWith("italic") -> EpubFontStyle.Italic
            clean.startsWith("oblique") -> EpubFontStyle.Oblique
            else -> EpubFontStyle.Normal
        }
    }

    private fun String.cleanFontFamily(): String? {
        return splitCommaList()
            .firstOrNull()
            ?.trim()
            ?.trim('\'', '"')
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.findMatchingCssBrace(start: Int): Int {
        var depth = 0
        var quote: Char? = null
        for (index in start until length) {
            val char = this[index]
            if (quote != null) {
                if (char == quote && getOrNull(index - 1) != '\\') quote = null
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun String.findCssStatementEnd(start: Int): Int {
        var quote: Char? = null
        var parenDepth = 0
        for (index in start until length) {
            val char = this[index]
            if (quote != null) {
                if (char == quote && getOrNull(index - 1) != '\\') quote = null
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                ';' -> if (parenDepth == 0) return index
            }
        }
        return length
    }

    private fun String.splitCommaList(): List<String> {
        val result = arrayListOf<String>()
        var quote: Char? = null
        var parenDepth = 0
        var start = 0
        for (index in indices) {
            val char = this[index]
            if (quote != null) {
                if (char == quote && getOrNull(index - 1) != '\\') quote = null
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                ',' -> if (parenDepth == 0) {
                    substring(start, index).trim().takeIf { it.isNotBlank() }?.let(result::add)
                    start = index + 1
                }
            }
        }
        substring(start).trim().takeIf { it.isNotBlank() }?.let(result::add)
        return result
    }

    private val cssCommentRegex = Regex("/\\*[\\s\\S]*?\\*/")
}
