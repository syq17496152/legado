package io.legado.app.utils

import io.legado.app.data.entities.SearchBook

object SearchBookMergeUtils {

    fun appendReplacing(
        current: Collection<SearchBook>,
        incoming: Collection<SearchBook>
    ): List<SearchBook> {
        if (current.isEmpty()) return incoming.distinctReplacing()
        if (incoming.isEmpty()) return current.toList()
        val result = linkedMapOf<String, SearchBook>()
        current.forEach { book ->
            result[book.stableSearchBookKey()] = book
        }
        incoming.forEach { book ->
            val key = book.stableSearchBookKey()
            result[key] = result[key]?.let { mergeSearchBook(it, book) } ?: book
        }
        return result.values.toList()
    }

    fun prependReplacing(
        current: Collection<SearchBook>,
        incoming: Collection<SearchBook>
    ): List<SearchBook> {
        if (incoming.isEmpty()) return current.toList()
        if (current.isEmpty()) return incoming.distinctReplacing()
        val result = linkedMapOf<String, SearchBook>()
        incoming.forEach { book ->
            result[book.stableSearchBookKey()] = book
        }
        current.forEach { book ->
            val key = book.stableSearchBookKey()
            result[key] = result[key]?.let { incomingBook ->
                mergeSearchBook(book, incomingBook)
            } ?: book
        }
        return result.values.toList()
    }

    private fun Collection<SearchBook>.distinctReplacing(): List<SearchBook> {
        val result = linkedMapOf<String, SearchBook>()
        forEach { book ->
            val key = book.stableSearchBookKey()
            result[key] = result[key]?.let { mergeSearchBook(it, book) } ?: book
        }
        return result.values.toList()
    }

    private fun mergeSearchBook(old: SearchBook, new: SearchBook): SearchBook {
        val merged = new.copy(
            origin = new.origin.ifBlank { old.origin },
            originName = new.originName.ifBlank { old.originName },
            name = new.name.ifBlank { old.name },
            author = new.author.ifBlank { old.author },
            kind = new.kind.ifMeaningful() ?: old.kind,
            coverUrl = new.coverUrl.ifMeaningful() ?: old.coverUrl,
            intro = new.intro.ifMeaningful() ?: old.intro,
            wordCount = new.wordCount.ifMeaningful() ?: old.wordCount,
            latestChapterTitle = new.latestChapterTitle.ifMeaningful() ?: old.latestChapterTitle,
            tocUrl = new.tocUrl.ifBlank { old.tocUrl },
            variable = new.variable.ifMeaningful() ?: old.variable,
            chapterWordCountText = new.chapterWordCountText.ifMeaningful() ?: old.chapterWordCountText,
            chapterWordCount = new.chapterWordCount.takeIf { it >= 0 } ?: old.chapterWordCount,
            respondTime = new.respondTime.takeIf { it >= 0 } ?: old.respondTime
        )
        val origins = linkedSetOf<String>()
        origins.addAll(old.origins)
        origins.addAll(new.origins)
        origins.add(old.origin)
        origins.add(new.origin)
        origins.filter { it.isNotBlank() }.forEach(merged::addOrigin)
        return merged
    }

    private fun String?.ifMeaningful(): String? {
        return this?.takeIf { it.isNotBlank() }
    }
}

fun SearchBook.stableSearchBookKey(): String {
    val sourceKey = origin.ifBlank { originName }
    return when {
        bookUrl.isNotBlank() -> "$sourceKey|$bookUrl"
        name.isNotBlank() || author.isNotBlank() -> "$sourceKey|$name|$author"
        coverUrl?.isNotBlank() == true -> "$sourceKey|${coverUrl.orEmpty()}"
        else -> "$sourceKey|$time"
    }
}
