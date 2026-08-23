package io.legado.app.model.localBook.epubcore.facade

import io.legado.app.data.entities.BookChapter
import io.legado.app.model.localBook.epubcore.pkg.EpubMetadata

data class EpubCoreBook(
    val bookUrl: String,
    val metadata: EpubMetadata,
    val chapters: List<BookChapter>,
    val coverHref: String?
)
