package io.legado.app.model.localBook.epubcore.pkg

data class EpubPackage(
    val opfPath: String,
    val metadata: EpubMetadata,
    val manifest: Map<String, EpubManifestItem>,
    val spine: List<EpubSpineItem>,
    val navHref: String?,
    val ncxHref: String?,
    val coverHref: String?,
    /** 出版方 rendition 声明（布局/视口）；缺省未声明（A5）。 */
    val rendition: EpubRendition = EpubRendition()
) {
    /** 全书默认布局（`reflowable`/`pre-paginated`）；缺省 null 表示未声明。 */
    val renditionLayout: String?
        get() = rendition.layout
}

/**
 * EPUB3 rendition 声明（`pkg/EpubPackage*`，迁移自 archive v15）。
 *
 * `layout` 缺省 null 表示出版方未声明（分类时按内容结构推断）；
 * 视口用于固定版式/图库的等比缩放（EPUB2 由 `original-resolution` 提供）。
 */
data class EpubRendition(
    val layout: String? = null,
    val viewportWidth: Float? = null,
    val viewportHeight: Float? = null
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
    val linear: Boolean,
    /** itemref `properties`（小写；含 `rendition:layout-*` 等覆盖声明）。 */
    val properties: Set<String> = emptySet(),
    /** 合并 itemref 覆盖后的章节级 rendition；缺省继承全书声明。 */
    val rendition: EpubRendition = EpubRendition()
)
