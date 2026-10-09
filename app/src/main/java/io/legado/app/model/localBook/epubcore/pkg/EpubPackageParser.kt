package io.legado.app.model.localBook.epubcore.pkg

import io.legado.app.constant.AppLog
import io.legado.app.model.localBook.epubcore.EpubRegex
import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import org.w3c.dom.Document

class EpubPackageParser {

    fun parse(archive: EpubArchive): EpubPackage {
        val opfPath = findOpfPath(archive)
        val opf = XmlTools.parse(archive.readBytes(opfPath))
        val metadataElement = opf.elements("metadata").firstOrNull()
        val packageRendition = parsePackageRendition(metadataElement)

        val manifest = LinkedHashMap<String, EpubManifestItem>()
        opf.elements("manifest").firstOrNull()
            ?.children("item")
            ?.forEach { item ->
                val id = item.attr("id") ?: return@forEach
                val href = item.attr("href") ?: return@forEach
                if (manifest.containsKey(id)) {
                    AppLog.putDebug("EPUB manifest duplicate id ignored: id=$id href=$href opf=$opfPath")
                    return@forEach
                }
                val properties = propertyTokens(item.attr("properties"))
                manifest[id] = EpubManifestItem(
                    id = id,
                    href = EpubPath.resolve(opfPath, href),
                    mediaType = item.attr("media-type").orEmpty(),
                    properties = properties
                )
            }

        val spineElement = opf.elements("spine").firstOrNull()
        val spine = spineElement
            ?.children("itemref")
            ?.mapIndexedNotNull { index, itemref ->
                val idRef = itemref.attr("idref") ?: return@mapIndexedNotNull null
                val item = manifest[idRef] ?: return@mapIndexedNotNull null
                val properties = propertyTokens(itemref.attr("properties"))
                EpubSpineItem(
                    index = index,
                    idRef = idRef,
                    href = item.href,
                    linear = !itemref.attr("linear").equals("no", true),
                    properties = properties,
                    rendition = mergeRendition(packageRendition, properties)
                )
            }
            .orEmpty()

        return EpubPackage(
            opfPath = opfPath,
            metadata = EpubMetadata(
                title = metadataElement?.firstText("title"),
                creator = metadataElement?.firstText("creator"),
                language = metadataElement?.firstText("language"),
                identifier = metadataElement?.firstText("identifier")
            ),
            manifest = manifest,
            spine = spine,
            navHref = manifest.values.firstOrNull { "nav" in it.properties }?.href,
            ncxHref = spineElement?.attr("toc")?.let { manifest[it]?.href },
            coverHref = findCoverHref(opf, manifest),
            rendition = packageRendition
        )
    }

    /** 解析 metadata 中的全书 rendition 声明（EPUB3 `rendition:*` + EPUB2 `original-resolution`）。 */
    private fun parsePackageRendition(metadata: org.w3c.dom.Element?): EpubRendition {
        val meta = metadata?.elements("meta").orEmpty()
        fun property(name: String): String? = meta
            .firstOrNull { it.attr("property").equals(name, true) && it.attr("refines").isNullOrBlank() }
            ?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        val viewport = property("rendition:viewport")?.let(::parseViewport)
            ?: meta.firstOrNull { it.attr("name").equals("original-resolution", true) }
                ?.attr("content")?.let(::parseViewport)
        return EpubRendition(
            layout = property("rendition:layout")?.lowercase()?.takeIf { it in LAYOUT_VALUES },
            viewportWidth = viewport?.first,
            viewportHeight = viewport?.second
        )
    }

    /** 用 itemref `properties` 中的 `rendition:layout-*` 覆盖全书声明。 */
    private fun mergeRendition(packageRendition: EpubRendition, properties: Set<String>): EpubRendition {
        val layout = properties.firstNotNullOfOrNull { property ->
            property.removePrefix("rendition:layout-").takeIf { property.startsWith("rendition:layout-") }
        }?.takeIf { it in LAYOUT_VALUES }
        return packageRendition.copy(layout = layout ?: packageRendition.layout)
    }

    private fun propertyTokens(value: String?): Set<String> {
        return value.orEmpty()
            .split(WHITESPACE)
            .mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }
            .toSet()
    }

    private fun parseViewport(value: String): Pair<Float, Float>? {
        val width = VIEWPORT_WIDTH.find(value)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        val height = VIEWPORT_HEIGHT.find(value)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        if (width != null && height != null && width > 0f && height > 0f) return width to height
        val dimensions = VIEWPORT_DIMENSIONS.find(value) ?: return null
        val dimensionWidth = dimensions.groupValues[1].toFloatOrNull()
        val dimensionHeight = dimensions.groupValues[2].toFloatOrNull()
        return if (dimensionWidth != null && dimensionHeight != null && dimensionWidth > 0f && dimensionHeight > 0f) {
            dimensionWidth to dimensionHeight
        } else {
            null
        }
    }

    private fun findOpfPath(archive: EpubArchive): String {
        if (archive.exists("META-INF/container.xml")) {
            val doc = XmlTools.parse(archive.readBytes("META-INF/container.xml"))
            val fullPath = doc.elements("rootfile").firstOrNull()?.attr("full-path")
            if (!fullPath.isNullOrBlank() && archive.exists(fullPath)) {
                return EpubPath.normalize(fullPath)
            }
        }
        return archive.list().firstOrNull { it.endsWith(".opf", ignoreCase = true) }
            ?: error("EPUB package document not found")
    }

    private fun findCoverHref(
        opf: Document,
        manifest: Map<String, EpubManifestItem>
    ): String? {
        manifest.values.firstOrNull { "cover-image" in it.properties }?.let { return it.href }
        val coverId = opf.elements("meta")
            .firstOrNull { it.attr("name") == "cover" }
            ?.attr("content")
        return coverId?.let { manifest[it]?.href }
    }

    private companion object {
        private val WHITESPACE = EpubRegex.compile("\\s+")
        private val LAYOUT_VALUES = setOf("reflowable", "pre-paginated")
        private val VIEWPORT_WIDTH = EpubRegex.compile(
            "(?:^|[,;\\s])width\\s*=\\s*([0-9.]+)",
            RegexOption.IGNORE_CASE
        )
        private val VIEWPORT_HEIGHT = EpubRegex.compile(
            "(?:^|[,;\\s])height\\s*=\\s*([0-9.]+)",
            RegexOption.IGNORE_CASE
        )
        private val VIEWPORT_DIMENSIONS = EpubRegex.compile("([0-9.]+)\\s*[xX]\\s*([0-9.]+)")
    }
}
