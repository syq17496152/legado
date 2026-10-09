package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.model.localBook.epubcore.EpubRegex
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

internal object EpubDirectPublisherCss {

    private const val MAX_IMPORT_DEPTH = 6
    private const val MAX_STYLESHEET_BYTES = 2L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 8L * 1024L * 1024L

    fun collectForClassification(
        sourceHtml: String,
        chapterHref: String,
        resourceHost: String,
        load: (path: String, maxBytes: Long) -> ByteArray?
    ): String {
        if (sourceHtml.isBlank() || (
                !sourceHtml.contains("stylesheet", ignoreCase = true) &&
                    !sourceHtml.contains("@import", ignoreCase = true)
                )
        ) {
            return ""
        }
        val document = runCatching { Jsoup.parse(sourceHtml, "", Parser.xmlParser()) }
            .getOrNull() ?: return ""
        return collectForClassification(document, chapterHref, resourceHost, load)
    }

    internal fun collectForClassification(
        document: Document,
        chapterHref: String,
        resourceHost: String,
        load: (path: String, maxBytes: Long) -> ByteArray?
    ): String {
        val state = LoadState(load, resourceHost)
        return buildString {
            document.select("link[href]").forEach { link ->
                if (!link.isStylesheet()) return@forEach
                if (!EpubDirectCssMediaPolicy.mayApplyToScreen(link.attr("media"))) return@forEach
                val path = localPath(elementBase(document, link, chapterHref), link.attr("href"))
                    ?: return@forEach
                val css = state.loadExpanded(path, 0) ?: return@forEach
                appendWithMedia(css, link.attr("media"))
            }
            document.select("style").forEach { style ->
                if (style.hasAttr("disabled") ||
                    !EpubDirectCssMediaPolicy.mayApplyToScreen(style.attr("media"))
                ) return@forEach
                val source = style.data().ifBlank { style.html() }
                if (!source.contains("@import", ignoreCase = true)) return@forEach
                val expanded = state.expandInline(
                    source = source,
                    baseHref = elementBase(document, style, chapterHref)
                )
                if (expanded != source) append(expanded).append('\n')
            }
        }
    }

    private fun StringBuilder.appendWithMedia(css: String, media: String) {
        val normalizedMedia = media.trim()
        if (normalizedMedia.isBlank() || normalizedMedia.equals("all", true)) {
            append(css).append('\n')
        } else {
            append("@media ").append(normalizedMedia).append(" {\n")
                .append(css).append("\n}\n")
        }
    }

    fun inline(
        sourceHtml: String,
        chapterHref: String,
        resourceHost: String,
        load: (path: String, maxBytes: Long) -> ByteArray?
    ): String {
        if (sourceHtml.isBlank() || (
                !sourceHtml.contains("stylesheet", ignoreCase = true) &&
                    !sourceHtml.contains("@import", ignoreCase = true)
                )
        ) {
            return sourceHtml
        }
        val document = runCatching { Jsoup.parse(sourceHtml, "", Parser.xmlParser()) }
            .getOrElse { return sourceHtml }
        val state = LoadState(load, resourceHost)
        var changed = false
        document.select("link[href]").toList().forEach { link ->
            if (!link.isStylesheet()) return@forEach
            val path = localPath(elementBase(document, link, chapterHref), link.attr("href"))
                ?: return@forEach
            val css = state.loadExpanded(path, 0) ?: return@forEach
            val style = Element("style")
                .attr("data-epub-publisher-css", path)
                .appendText(css)
            link.attr("media").takeIf { it.isNotBlank() }?.let { style.attr("media", it) }
            link.before(style)
            link.remove()
            changed = true
        }
        document.select("style").toList().forEach { style ->
            if (style.hasAttr("data-epub-publisher-css")) return@forEach
            val source = style.data().ifBlank { style.html() }
            val expanded = state.expandInline(
                source = source,
                baseHref = elementBase(document, style, chapterHref)
            )
            if (expanded != source) {
                style.text(expanded)
                changed = true
            }
        }
        return if (changed) document.outerHtml() else sourceHtml
    }

    fun normalize(css: String): String {
        return DUOKAN_BLEED.replace(
            DUOKAN_TEXT_INDENT.replace(css) { match ->
                match.groupValues[1] + match.groupValues[2] + "text-indent:"
            }
        ) { match ->
            match.groupValues[1]
        }
    }

    private class LoadState(
        private val load: (path: String, maxBytes: Long) -> ByteArray?,
        private val resourceHost: String
    ) {
        private val active = HashSet<String>()
        private val cache = HashMap<String, String>()
        private val failed = HashSet<String>()
        private var totalBytes = 0L

        fun loadExpanded(path: String, depth: Int): String? {
            cache[path]?.let { return it }
            if (path in failed || depth > MAX_IMPORT_DEPTH || !active.add(path)) return null
            return try {
                val bytes = runCatching { load(path, MAX_STYLESHEET_BYTES) }.getOrNull() ?: run {
                    failed += path
                    return null
                }
                if (bytes.size > MAX_STYLESHEET_BYTES || totalBytes + bytes.size > MAX_TOTAL_BYTES) {
                    failed += path
                    return null
                }
                totalBytes += bytes.size
                val source = decodeStylesheet(bytes)
                val expanded = expandImports(source, path, depth)
                val normalized = normalize(expanded)
                val rewritten = EpubDirectDocumentBuilder.rewriteCssUrls(
                    baseHref = path,
                    css = normalized,
                    resourceHost = resourceHost
                )
                cache[path] = rewritten
                rewritten
            } finally {
                active.remove(path)
            }
        }

        fun expandInline(source: String, baseHref: String): String {
            return normalize(expandImports(source, baseHref, 0))
        }

        private fun expandImports(source: String, baseHref: String, depth: Int): String {
            return CSS_IMPORT.replace(source) { match ->
                val href = match.groupValues.drop(1).take(5).firstOrNull { it.isNotBlank() }
                    ?: return@replace match.value
                val importedPath = localPath(baseHref, href) ?: return@replace match.value
                val imported = loadExpanded(importedPath, depth + 1) ?: return@replace match.value
                val media = match.groupValues[6].trim()
                if (media.isBlank() || media.equals("all", true)) {
                    imported
                } else {
                    "@media $media {\n$imported\n}"
                }
            }
        }
    }

    private fun Element.isStylesheet(): Boolean {
        val relations = attr("rel").split(WHITESPACE)
        return relations.any { it.equals("stylesheet", true) } &&
            relations.none { it.equals("alternate", true) } &&
            !hasAttr("disabled")
    }

    private fun elementBase(document: Document, element: Element, chapterHref: String): String {
        val continuation = element.parents()
            .firstOrNull { it.hasAttr("data-epub-continuation-href") }
        val scopeHref = continuation
            ?.attr("data-epub-continuation-href")
            ?.takeIf { it.isNotBlank() }
            ?: chapterHref
        val declaredBase = if (continuation != null) {
            continuation.selectFirst("base[href]")?.attr("href")
        } else {
            document.selectFirst("head base[href]")?.attr("href")
        }?.takeIf { it.isNotBlank() }
        var baseHref = EpubDirectDocumentBuilder.resolveBaseHref(scopeHref, declaredBase)
        val chain = ArrayList<Element>()
        var current: Element? = element
        while (current != null) {
            chain += current
            if (current === continuation) break
            current = current.parent()
        }
        chain.asReversed().forEach { node ->
            node.attr("xml:base").takeIf { it.isNotBlank() }?.let { xmlBase ->
                baseHref = EpubDirectDocumentBuilder.resolveBaseHref(baseHref, xmlBase)
            }
        }
        return baseHref
    }

    private fun localPath(baseHref: String, href: String): String? {
        val value = href.trim()
        if (value.isBlank() || value.startsWith('#') || value.startsWith("//") || SCHEME.containsMatchIn(value)) {
            return null
        }
        return EpubPath.stripFragment(EpubPath.resolve(baseHref, value)).takeIf { it.isNotBlank() }
    }

    private fun decodeStylesheet(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val bom = stylesheetBom(bytes)
        val charset = bom?.first ?: declaredStylesheetCharset(bytes) ?: StandardCharsets.UTF_8
        val offset = bom?.second ?: 0
        val decoded = String(bytes, offset, bytes.size - offset, charset).removePrefix("\uFEFF")
        return CSS_CHARSET.replaceFirst(decoded, "")
    }

    private fun stylesheetBom(bytes: ByteArray): Pair<Charset, Int>? {
        if (bytes.startsWith(0x00, 0x00, 0xfe, 0xff)) {
            return runCatching { Charset.forName("UTF-32BE") }.getOrNull()?.let { it to 4 }
        }
        if (bytes.startsWith(0xff, 0xfe, 0x00, 0x00)) {
            return runCatching { Charset.forName("UTF-32LE") }.getOrNull()?.let { it to 4 }
        }
        if (bytes.startsWith(0xef, 0xbb, 0xbf)) return StandardCharsets.UTF_8 to 3
        if (bytes.startsWith(0xfe, 0xff)) return StandardCharsets.UTF_16BE to 2
        if (bytes.startsWith(0xff, 0xfe)) return StandardCharsets.UTF_16LE to 2
        return null
    }

    private fun declaredStylesheetCharset(bytes: ByteArray): Charset? {
        val prefix = buildString(minOf(bytes.size, MAX_CHARSET_PREFIX_BYTES)) {
            repeat(minOf(bytes.size, MAX_CHARSET_PREFIX_BYTES)) { index ->
                append((bytes[index].toInt() and 0xff).toChar())
            }
        }
        val name = CSS_CHARSET.find(prefix)?.groupValues?.getOrNull(1) ?: return null
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    private fun ByteArray.startsWith(vararg expected: Int): Boolean {
        if (size < expected.size) return false
        return expected.indices.all { index -> (this[index].toInt() and 0xff) == expected[index] }
    }

    private val WHITESPACE = EpubRegex.compile("\\s+")
    private val SCHEME = EpubRegex.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val CSS_IMPORT = EpubRegex.compile(
        """@import\s+(?:url\(\s*(?:"([^"]+)"|'([^']+)'|([^\s)]+))\s*\)|"([^"]+)"|'([^']+)')\s*([^;]*);""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val DUOKAN_TEXT_INDENT = EpubRegex.compile(
        """(^|[;{])(\s*)duokan-text-indent\s*:""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )
    private val DUOKAN_BLEED = EpubRegex.compile(
        """(^|[;{])\s*duokan-bleed\s*:[^;{}]*(?:;|(?=\s*(?:}|$)))""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )
    private val CSS_CHARSET = EpubRegex.compile(
        """^@charset\s+["']([^"']+)["']\s*;""",
        RegexOption.IGNORE_CASE
    )
    private const val MAX_CHARSET_PREFIX_BYTES = 256
}
