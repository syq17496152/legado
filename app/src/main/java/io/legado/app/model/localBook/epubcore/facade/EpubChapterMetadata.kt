package io.legado.app.model.localBook.epubcore.facade

import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.GSON

/** Metadata kept on a physical EPUB spine page without changing its XHTML. */
internal object EpubChapterMetadata {

    const val TocHiddenKey = "epubTocHidden"
    const val LogicalOwnerUrlKey = "epubLogicalOwnerUrl"

    fun isHiddenFromToc(chapter: BookChapter): Boolean {
        return chapter.variableMap[TocHiddenKey].equals("true", ignoreCase = true)
    }

    fun markPhysicalContinuation(
        chapter: BookChapter,
        ownerUrl: String?,
        ownerTitle: String?
    ): BookChapter {
        if (ownerUrl.isNullOrBlank() || ownerTitle.isNullOrBlank()) return chapter
        val variables = chapter.variableMap.toMutableMap().apply {
            put(TocHiddenKey, "true")
            put(LogicalOwnerUrlKey, ownerUrl)
        }
        return chapter.copy(
            title = ownerTitle,
            variable = GSON.toJson(variables)
        )
    }
}
