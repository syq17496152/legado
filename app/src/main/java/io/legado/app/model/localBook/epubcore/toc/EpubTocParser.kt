package io.legado.app.model.localBook.epubcore.toc

import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import io.legado.app.model.localBook.epubcore.pkg.EpubPackage
import io.legado.app.model.localBook.epubcore.pkg.XmlTools
import io.legado.app.model.localBook.epubcore.pkg.attr
import io.legado.app.model.localBook.epubcore.pkg.children
import io.legado.app.model.localBook.epubcore.pkg.elements
import org.jsoup.Jsoup
import org.w3c.dom.Element

class EpubTocParser {

    fun parse(archive: EpubArchive, pkg: EpubPackage): List<TocItem> {
        pkg.navHref?.let { href ->
            parseNav(archive, href).takeIf { it.isNotEmpty() }?.let { return it }
        }
        pkg.ncxHref?.let { href ->
            parseNcx(archive, href).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return pkg.spine.map { TocItem("Chapter ${it.index + 1}", it.href) }
    }

    private fun parseNav(archive: EpubArchive, navHref: String): List<TocItem> {
        val doc = Jsoup.parse(archive.readText(navHref))
        val nav = doc.select("nav").firstOrNull {
            val type = it.attr("epub:type").ifBlank { it.attr("type") }
            type == "toc" || type.split(Regex("\\s+")).contains("toc")
        } ?: doc.selectFirst("nav")
        val rootOl = nav?.children()?.firstOrNull { it.normalName() == "ol" } ?: return emptyList()
        return parseHtmlNavList(navHref, rootOl)
    }

    private fun parseHtmlNavList(baseHref: String, ol: org.jsoup.nodes.Element): List<TocItem> {
        return ol.children().filter { it.normalName() == "li" }.mapNotNull { li ->
            val anchor = li.children().firstOrNull { it.normalName() == "a" || it.normalName() == "span" }
            val title = anchor?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val hrefRaw = anchor.attr("href")
            TocItem(
                title = title,
                href = if (hrefRaw.isBlank()) "" else EpubPath.resolve(baseHref, hrefRaw),
                fragment = EpubPath.fragment(hrefRaw),
                children = li.children().firstOrNull { it.normalName() == "ol" }
                    ?.let { parseHtmlNavList(baseHref, it) }
                    .orEmpty()
            )
        }
    }

    private fun parseNcx(archive: EpubArchive, ncxHref: String): List<TocItem> {
        val doc = XmlTools.parse(archive.readBytes(ncxHref))
        return doc.elements("navMap").firstOrNull()
            ?.children("navPoint")
            ?.mapNotNull { parseNavPoint(ncxHref, it) }
            .orEmpty()
    }

    private fun parseNavPoint(baseHref: String, navPoint: Element): TocItem? {
        val title = navPoint.elements("text").firstOrNull()
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val src = navPoint.elements("content").firstOrNull()?.attr("src").orEmpty()
        return TocItem(
            title = title,
            href = if (src.isBlank()) "" else EpubPath.resolve(baseHref, src),
            fragment = EpubPath.fragment(src),
            children = navPoint.children("navPoint").mapNotNull { parseNavPoint(baseHref, it) }
        )
    }
}
