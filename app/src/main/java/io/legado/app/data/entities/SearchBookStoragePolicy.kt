package io.legado.app.data.entities

/**
 * Bounds source-controlled search result fields before Room stores them.
 *
 * Search results are a rebuildable cache. Keeping every stored row well below
 * CursorWindow's per-row limit is safer than persisting an unusably large row
 * that can crash later when a query materializes it.
 */
internal object SearchBookStoragePolicy {

    const val MAX_STORED_ROW_BYTES = 512 * 1024
    const val MAX_BOOK_URL_BYTES = 32 * 1024
    const val MAX_ORIGIN_BYTES = 32 * 1024
    const val MAX_NAME_BYTES = 16 * 1024
    const val MAX_AUTHOR_BYTES = 16 * 1024
    const val MAX_ORIGIN_NAME_BYTES = 8 * 1024
    const val MAX_KIND_BYTES = 32 * 1024
    const val MAX_COVER_URL_BYTES = 32 * 1024
    const val MAX_INTRO_BYTES = 128 * 1024
    const val MAX_WORD_COUNT_BYTES = 1024
    const val MAX_CHAPTER_TITLE_BYTES = 16 * 1024
    const val MAX_TOC_URL_BYTES = 32 * 1024
    const val MAX_VARIABLE_BYTES = 64 * 1024
    const val MAX_CHAPTER_WORD_COUNT_TEXT_BYTES = 1024
    const val MAX_CONFIGURED_TEXT_BYTES =
        MAX_BOOK_URL_BYTES + MAX_ORIGIN_BYTES + MAX_NAME_BYTES + MAX_AUTHOR_BYTES +
            MAX_ORIGIN_NAME_BYTES + MAX_KIND_BYTES + MAX_COVER_URL_BYTES + MAX_INTRO_BYTES +
            MAX_WORD_COUNT_BYTES + MAX_CHAPTER_TITLE_BYTES + MAX_TOC_URL_BYTES +
            MAX_VARIABLE_BYTES + MAX_CHAPTER_WORD_COUNT_TEXT_BYTES

    init {
        check(MAX_CONFIGURED_TEXT_BYTES <= MAX_STORED_ROW_BYTES) {
            "SearchBook text budgets exceed the safe SQLite row budget"
        }
    }

    /**
     * Returns a storage-only copy. Runtime HTML and the caller's live object are
     * deliberately left untouched.
     *
     * Fields that are identifiers or executable state must not be truncated.
     * If one of those is unreasonably large, the result remains usable in the
     * current screen but is not cached in SQLite.
     */
    fun sanitize(book: SearchBook): SearchBook? {
        if (!book.bookUrl.fitsUtf8(MAX_BOOK_URL_BYTES) ||
            !book.origin.fitsUtf8(MAX_ORIGIN_BYTES) ||
            !book.name.fitsUtf8(MAX_NAME_BYTES) ||
            !book.author.fitsUtf8(MAX_AUTHOR_BYTES) ||
            !book.tocUrl.fitsUtf8(MAX_TOC_URL_BYTES) ||
            !book.variable.fitsUtf8(MAX_VARIABLE_BYTES)
        ) {
            return null
        }

        val sanitized = book.copy(
            originName = book.originName.takeUtf8Bytes(MAX_ORIGIN_NAME_BYTES),
            kind = book.kind?.takeUtf8Bytes(MAX_KIND_BYTES),
            coverUrl = book.coverUrl?.takeIf { it.fitsUtf8(MAX_COVER_URL_BYTES) },
            intro = book.intro?.limitTaggedText(MAX_INTRO_BYTES),
            wordCount = book.wordCount?.takeUtf8Bytes(MAX_WORD_COUNT_BYTES),
            latestChapterTitle = book.latestChapterTitle?.takeUtf8Bytes(MAX_CHAPTER_TITLE_BYTES),
            chapterWordCountText = book.chapterWordCountText
                ?.takeUtf8Bytes(MAX_CHAPTER_WORD_COUNT_TEXT_BYTES)
        )
        return sanitized
    }

    /**
     * Returns a key that is safe to bind back into SQLite for targeted cleanup.
     * Oversized primary keys are handled by the predicate-only cleanup query so
     * a rejected value is never copied into another large SQLite bind buffer.
     * Empty legacy keys remain bindable so delete keeps its previous behavior.
     */
    fun cleanupBookUrl(bookUrl: String): String? {
        return bookUrl.takeIf { it.fitsUtf8(MAX_BOOK_URL_BYTES) }
    }

    internal fun storedUtf8ByteCount(book: SearchBook): Long {
        return sequenceOf(
            book.bookUrl,
            book.origin,
            book.originName,
            book.name,
            book.author,
            book.kind,
            book.coverUrl,
            book.intro,
            book.wordCount,
            book.latestChapterTitle,
            book.tocUrl,
            book.variable,
            book.chapterWordCountText
        ).filterNotNull().sumOf { utf8ByteCount(it) }
    }

    internal fun utf8ByteCount(value: String, stopAfter: Int = Int.MAX_VALUE): Long {
        var byteCount = 0L
        var index = 0
        while (index < value.length) {
            val char = value[index]
            byteCount += when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                char.isHighSurrogate() &&
                    index + 1 < value.length &&
                    value[index + 1].isLowSurrogate() -> {
                    index++
                    4
                }

                else -> 3
            }
            if (byteCount > stopAfter) return byteCount
            index++
        }
        return byteCount
    }

    private fun String?.fitsUtf8(maxBytes: Int): Boolean {
        return this == null || utf8ByteCount(this, maxBytes) <= maxBytes
    }

    private fun String.limitTaggedText(maxBytes: Int): String {
        if (fitsUtf8(maxBytes)) return this
        val tag = taggedTextMarkers.firstOrNull { startsWith(it.first, ignoreCase = true) }
            ?: return takeUtf8Bytes(maxBytes)
        val (startTag, endTag) = tag
        val contentEnd = lastIndexOf(endTag, ignoreCase = true)
            .takeIf { it >= startTag.length }
            ?: length
        val maxContentBytes = (maxBytes - startTag.length - endTag.length).coerceAtLeast(0)
        val content = takeUtf8Bytes(
            maxBytes = maxContentBytes,
            startIndex = startTag.length,
            endIndex = contentEnd
        )
        return startTag + content + endTag
    }

    private fun String.takeUtf8Bytes(
        maxBytes: Int,
        startIndex: Int = 0,
        endIndex: Int = length
    ): String {
        var byteCount = 0
        var safeEndIndex = startIndex
        while (safeEndIndex < endIndex) {
            val char = this[safeEndIndex]
            val charCount: Int
            val charBytes: Int
            if (char.isHighSurrogate() &&
                safeEndIndex + 1 < endIndex &&
                this[safeEndIndex + 1].isLowSurrogate()
            ) {
                charCount = 2
                charBytes = 4
            } else {
                charCount = 1
                charBytes = when {
                    char.code < 0x80 -> 1
                    char.code < 0x800 -> 2
                    else -> 3
                }
            }
            if (byteCount > maxBytes - charBytes) break
            byteCount += charBytes
            safeEndIndex += charCount
        }
        return if (startIndex == 0 && safeEndIndex == length) {
            this
        } else {
            substring(startIndex, safeEndIndex)
        }
    }

    private val taggedTextMarkers = arrayOf(
        "<useweb>" to "</useweb>",
        "<usehtml>" to "</usehtml>",
        "<md>" to "</md>"
    )
}

internal fun SearchBook.sanitizedForStorage(): SearchBook? {
    return SearchBookStoragePolicy.sanitize(this)
}
