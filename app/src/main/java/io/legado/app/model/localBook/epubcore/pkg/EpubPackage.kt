package io.legado.app.model.localBook.epubcore.pkg

data class EpubPackage(
    val opfPath: String,
    val metadata: EpubMetadata,
    val manifest: Map<String, EpubManifestItem>,
    val spine: List<EpubSpineItem>,
    val navHref: String?,
    val ncxHref: String?,
    val coverHref: String?
)

data class EpubMetadata(
    val title: String?,
    val creator: String?,
    val language: String?,
    val identifier: String?
)

data class EpubManifestItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val properties: Set<String>
)

data class EpubSpineItem(
    val index: Int,
    val idRef: String,
    val href: String,
    val linear: Boolean
)
