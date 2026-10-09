package io.legado.app.model.localBook.epubcore.facade

import io.legado.app.data.entities.BookChapter

/** One definition of a chapter that may be opened by sequential EPUB navigation. */
internal object EpubReadableChapterPolicy {

    fun isReadable(chapter: BookChapter?): Boolean {
        return chapter != null && !chapter.url.startsWith("skip:")
    }

    fun isLogical(chapter: BookChapter?): Boolean {
        return isReadable(chapter) && !EpubChapterMetadata.isHiddenFromToc(chapter!!)
    }

    fun adjacent(
        chapters: List<BookChapter>,
        currentIndex: Int,
        direction: Int
    ): Int? {
        val readable = chapters.asSequence().filter(::isReadable)
        return if (direction < 0) {
            readable.filter { it.index < currentIndex }.maxOfOrNull { it.index }
        } else {
            readable.filter { it.index > currentIndex }.minOfOrNull { it.index }
        }
    }

    fun adjacentLogical(
        chapters: List<BookChapter>,
        currentIndex: Int,
        direction: Int
    ): Int? {
        val logical = chapters.asSequence().filter(::isLogical)
        return if (direction < 0) {
            logical.filter { it.index < currentIndex }.maxOfOrNull { it.index }
        } else {
            logical.filter { it.index > currentIndex }.minOfOrNull { it.index }
        }
    }

    fun resolve(
        chapters: List<BookChapter>,
        requestedIndex: Int,
        preferredDirection: Int = 1
    ): Int? {
        if (isReadable(chapters.firstOrNull { it.index == requestedIndex })) return requestedIndex
        return adjacent(chapters, requestedIndex, preferredDirection)
            ?: adjacent(chapters, requestedIndex, -preferredDirection)
    }

}
