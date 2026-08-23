package io.legado.app.model.localBook.epubcore.toc

data class TocItem(
    val title: String,
    val href: String,
    val fragment: String? = null,
    val children: List<TocItem> = emptyList()
)
