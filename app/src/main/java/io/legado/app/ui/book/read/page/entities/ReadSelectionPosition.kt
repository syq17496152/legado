package io.legado.app.ui.book.read.page.entities

/**
 * Immutable read-aloud target captured before the text selection UI is cleared.
 */
data class ReadSelectionPosition(
    val bookUrl: String,
    val chapterIndex: Int,
    val chapterUrl: String,
    val chapterPosition: Int
)
