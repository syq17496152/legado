package io.legado.app.model.localBook.epubcore.font

import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import io.legado.app.model.localBook.epubcore.pkg.EpubManifestItem
import io.legado.app.model.localBook.epubcore.pkg.EpubPackage
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Locale

object EpubFontCatalog {

    fun fromPackage(archive: EpubArchive, pkg: EpubPackage): List<EpubFontFace> {
        val visitedCss = linkedSetOf<String>()
        val faces = arrayListOf<EpubFontFace>()
        pkg.manifest.values
            .filter { item -> item.isCssItem() }
            .forEach { item -> faces += collectLinkedCss(archive, item.href, visitedCss) }
        pkg.spine.forEach { spineItem ->
            faces += collectChapterFaces(archive, spineItem.href, visitedCss)
        }
        return faces.distinct()
    }

    private fun collectChapterFaces(
        archive: EpubArchive,
        chapterHref: String,
        visitedCss: MutableSet<String>
    ): List<EpubFontFace> {
        val href = chapterHref.toArchivePath()
        if (href.isBlank()) return emptyList()
        val html = runCatching { archive.readText(href) }.getOrDefault("")
        if (html.isBlank()) return emptyList()
        val document = runCatching { Jsoup.parse(html) }.getOrNull() ?: return emptyList()
        val faces = arrayListOf<EpubFontFace>()
        document.select("link[href]").forEach { link ->
            if (!link.isStylesheetLink()) return@forEach
            val cssHref = link.attr("href").trim().takeIf { it.isNotBlank() } ?: return@forEach
            faces += collectLinkedCss(archive, EpubPath.resolve(href, cssHref), visitedCss)
        }
        document.select("style").forEach { style ->
            val css = style.data().ifBlank { style.html() }
            faces += collectCss(archive, href, css, visitedCss)
        }
        return faces
    }

    private fun collectLinkedCss(
        archive: EpubArchive,
        cssHref: String,
        visitedCss: MutableSet<String>
    ): List<EpubFontFace> {
        val href = cssHref.toArchivePath()
        if (href.isBlank() || !visitedCss.add(href)) return emptyList()
        val css = runCatching { archive.readText(href) }.getOrDefault("")
        return collectCss(archive, href, css, visitedCss)
    }

    private fun collectCss(
        archive: EpubArchive,
        cssHref: String,
        css: String,
        visitedCss: MutableSet<String>
    ): List<EpubFontFace> {
        if (css.isBlank()) return emptyList()
        val href = cssHref.toArchivePath()
        val faces = arrayListOf<EpubFontFace>()
        faces += EpubFontFaceParser.parse(href, css)
        EpubFontFaceParser.parseImports(href, css).forEach { importHref ->
            faces += collectLinkedCss(archive, importHref, visitedCss)
        }
        return faces
    }

    private fun EpubManifestItem.isCssItem(): Boolean {
        return mediaType.equals("text/css", ignoreCase = true) ||
            href.toArchivePath().endsWith(".css", ignoreCase = true)
    }

    private fun Element.isStylesheetLink(): Boolean {
        return attr("rel")
            .trim()
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .any { it == "stylesheet" }
    }

    private fun String.toArchivePath(): String {
        val end = indexOfAny(charArrayOf('?', '#')).takeIf { it >= 0 } ?: length
        return EpubPath.normalize(substring(0, end))
    }
}
