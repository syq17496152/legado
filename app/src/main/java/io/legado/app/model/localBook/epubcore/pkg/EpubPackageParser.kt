package io.legado.app.model.localBook.epubcore.pkg

import io.legado.app.constant.AppLog
import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import org.w3c.dom.Document

class EpubPackageParser {

    fun parse(archive: EpubArchive): EpubPackage {
        val opfPath = findOpfPath(archive)
        val opf = XmlTools.parse(archive.readBytes(opfPath))
        val metadataElement = opf.elements("metadata").firstOrNull()

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
                val properties = item.attr("properties").orEmpty()
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                    .toSet()
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
                EpubSpineItem(
                    index = index,
                    idRef = idRef,
                    href = item.href,
                    linear = itemref.attr("linear") != "no"
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
            coverHref = findCoverHref(opf, manifest)
        )
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
}
