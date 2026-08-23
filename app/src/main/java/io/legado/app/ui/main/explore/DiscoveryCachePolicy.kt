package io.legado.app.ui.main.explore

import io.legado.app.data.entities.SearchBook
import io.legado.app.utils.GSON
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Keeps discovery JSON rows comfortably below Android CursorWindow's per-row limit.
 * Discovery data is only a reload optimization, so an oversized snapshot should be
 * discarded instead of risking a process crash while Room materializes the row.
 */
internal object DiscoveryCachePolicy {

    const val MAX_SQLITE_VALUE_BYTES: Long = 512L * 1024L
    private const val MAX_NAME_LENGTH = 512
    private const val MAX_AUTHOR_LENGTH = 512
    private const val MAX_ORIGIN_NAME_LENGTH = 512
    private const val MAX_KIND_LENGTH = 2_048
    private const val MAX_INTRO_LENGTH = 4_096
    private const val MAX_CHAPTER_TITLE_LENGTH = 1_024
    private const val MAX_COUNT_TEXT_LENGTH = 256
    private const val MAX_REQUIRED_FIELD_BYTES = 16L * 1024L
    private const val MAX_OPTIONAL_URL_BYTES = 32L * 1024L
    private const val MAX_VARIABLE_BYTES = 16L * 1024L

    fun canRead(storedByteCount: Long?): Boolean {
        return storedByteCount != null && storedByteCount in 0..MAX_SQLITE_VALUE_BYTES
    }

    fun canStore(value: String): Boolean {
        return utf8ByteCount(value, MAX_SQLITE_VALUE_BYTES) <= MAX_SQLITE_VALUE_BYTES
    }

    /** Serializes without ever retaining more than the SQLite cache budget. */
    fun toBoundedJson(value: Any): String? {
        val output = BoundedByteArrayOutputStream(MAX_SQLITE_VALUE_BYTES.toInt())
        return try {
            OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
                GSON.toJson(value, writer)
            }
            output.toString(StandardCharsets.UTF_8.name())
        } catch (_: CacheSizeLimitExceededException) {
            null
        }
    }

    fun compact(book: SearchBook): SearchBook? {
        if (!book.bookUrl.fits(MAX_REQUIRED_FIELD_BYTES) ||
            !book.origin.fits(MAX_REQUIRED_FIELD_BYTES) ||
            !book.tocUrl.fits(MAX_OPTIONAL_URL_BYTES) ||
            !book.variable.fits(MAX_VARIABLE_BYTES)
        ) {
            return null
        }
        return book.copy(
            originName = book.originName.limitLength(MAX_ORIGIN_NAME_LENGTH),
            name = book.name.limitLength(MAX_NAME_LENGTH),
            author = book.author.limitLength(MAX_AUTHOR_LENGTH),
            kind = book.kind?.limitLength(MAX_KIND_LENGTH),
            coverUrl = book.coverUrl?.takeIf { it.fits(MAX_OPTIONAL_URL_BYTES) },
            intro = book.intro?.limitLength(MAX_INTRO_LENGTH),
            wordCount = book.wordCount?.limitLength(MAX_COUNT_TEXT_LENGTH),
            latestChapterTitle = book.latestChapterTitle?.limitLength(MAX_CHAPTER_TITLE_LENGTH),
            tocUrl = book.tocUrl,
            chapterWordCountText = book.chapterWordCountText?.limitLength(MAX_COUNT_TEXT_LENGTH)
        )
    }

    internal fun utf8ByteCount(value: String, stopAfter: Long = Long.MAX_VALUE): Long {
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

    private fun String.limitLength(maxLength: Int): String {
        return if (length <= maxLength) this else take(maxLength)
    }

    private fun String?.fits(maxBytes: Long): Boolean {
        return this == null || utf8ByteCount(this, maxBytes) <= maxBytes
    }

    private class BoundedByteArrayOutputStream(
        private val maxBytes: Int
    ) : ByteArrayOutputStream(minOf(maxBytes, 8 * 1024)) {

        override fun write(value: Int) {
            ensureCapacityFor(1)
            super.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            ensureCapacityFor(length)
            super.write(bytes, offset, length)
        }

        private fun ensureCapacityFor(additionalBytes: Int) {
            if (additionalBytes < 0 || count > maxBytes - additionalBytes) {
                throw CacheSizeLimitExceededException()
            }
        }
    }

    private class CacheSizeLimitExceededException : RuntimeException()
}
