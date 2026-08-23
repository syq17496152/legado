package io.legado.app.help.book.library

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.lib.cloud.S3Container
import io.legado.app.utils.MD5Utils
import java.text.Normalizer
import java.util.Locale

data class LibraryContainerConfig(
    val container: S3Container = S3Container(prefix = LibraryCloudPaths.ROOT_DIR),
    val password: String? = null,
    val sourceUrls: Set<String> = emptySet(),
    val minUploadChars: Int = 1500,
    val dailyUploadLimit: Int = 0,
    val lockedImported: Boolean = false
) {
    val id: String get() = container.id

    fun normalized(): LibraryContainerConfig {
        return copy(
            container = container.normalized().copy(
                prefix = container.prefix.trim().trim('/').ifBlank { LibraryCloudPaths.ROOT_DIR }
            ),
            password = password?.takeIf { it.isNotBlank() },
            sourceUrls = sourceUrls.filter { it.isNotBlank() }.toSet(),
            minUploadChars = minUploadChars.coerceAtLeast(0),
            dailyUploadLimit = dailyUploadLimit.coerceAtLeast(0),
            lockedImported = lockedImported
        )
    }

    fun matchesSource(sourceUrl: String?): Boolean {
        return !sourceUrl.isNullOrBlank() && sourceUrls.contains(sourceUrl)
    }
}

data class LibraryChapterPayloadV2(
    val version: Int = 2,
    val bookKey: String = "",
    val chapterKey: String = "",
    val titleKey: String = "",
    val relaxedTitleKey: String = "",
    val sourceKey: String = "",
    val name: String = "",
    val author: String = "",
    val normalizedName: String = "",
    val normalizedAuthor: String = "",
    val title: String = "",
    val normalizedTitle: String = "",
    val relaxedTitle: String = "",
    val ordinalTitle: String = "",
    val chapterIndex: Int = -1,
    val sourceUrl: String = "",
    val sourceName: String = "",
    val sourceBookUrl: String = "",
    val sourceChapterIdentity: String = "",
    val contentHash: String = "",
    val content: String = "",
    val updatedAt: Long = 0L
)

data class LibraryChapterManifestV3(
    val version: Int = 3,
    val bookKey: String = "",
    val name: String = "",
    val author: String = "",
    val normalizedName: String = "",
    val normalizedAuthor: String = "",
    val chapterKey: String = "",
    val title: String = "",
    val normalizedTitle: String = "",
    val relaxedTitle: String = "",
    val ordinalTitle: String = "",
    val variants: List<LibraryChapterVariantV3> = emptyList(),
    val updatedAt: Long = 0L
)

data class LibraryChapterVariantV3(
    val sourceKey: String = "",
    val sourceUrl: String = "",
    val sourceName: String = "",
    val sourceBookUrl: String = "",
    val sourceChapterIdentity: String = "",
    val chapterIndex: Int = -1,
    val title: String = "",
    val normalizedTitle: String = "",
    val relaxedTitle: String = "",
    val ordinalTitle: String = "",
    val contentHash: String = "",
    val payloadPath: String = "",
    val updatedAt: Long = 0L
)

data class LibraryChapterPayloadV3(
    val version: Int = 3,
    val bookKey: String = "",
    val chapterKey: String = "",
    val sourceKey: String = "",
    val name: String = "",
    val author: String = "",
    val normalizedName: String = "",
    val normalizedAuthor: String = "",
    val title: String = "",
    val normalizedTitle: String = "",
    val relaxedTitle: String = "",
    val ordinalTitle: String = "",
    val chapterIndex: Int = -1,
    val sourceUrl: String = "",
    val sourceName: String = "",
    val sourceBookUrl: String = "",
    val sourceChapterIdentity: String = "",
    val contentHash: String = "",
    val content: String = "",
    val updatedAt: Long = 0L
)

data class LibraryCloudMatchKey(
    val kind: String,
    val key: String
)

data class LibraryCloudChapterVersion(
    val path: String,
    val payload: LibraryChapterPayloadV2,
    val matchKind: String,
    val schemaVersion: Int = 2
)

object LibraryCloudPaths {
    const val ROOT_DIR = "Library"
    const val V2_DIR = "v2"
    const val V3_DIR = "v3"

    fun currentChapterPath(bookKey: String, matchKey: LibraryCloudMatchKey): String {
        return "$V2_DIR/books/$bookKey/chapters/${matchKey.kind}/${matchKey.key}/current.json.gz"
    }

    fun variantChapterPath(
        bookKey: String,
        matchKey: LibraryCloudMatchKey,
        sourceKey: String
    ): String {
        return "$V2_DIR/books/$bookKey/chapters/${matchKey.kind}/${matchKey.key}/variants/$sourceKey/current.json.gz"
    }

    fun variantsPrefix(bookKey: String, matchKey: LibraryCloudMatchKey): String {
        return "$V2_DIR/books/$bookKey/chapters/${matchKey.kind}/${matchKey.key}/variants/"
    }

    fun v3ManifestPath(bookKey: String, chapterKey: String): String {
        return "$V3_DIR/books/$bookKey/chapters/$chapterKey/manifest.json.gz"
    }

    fun v3CurrentPath(bookKey: String, chapterKey: String): String {
        return "$V3_DIR/books/$bookKey/chapters/$chapterKey/current.json.gz"
    }

    fun v3PayloadPath(bookKey: String, chapterKey: String, sourceKey: String): String {
        return "$V3_DIR/books/$bookKey/chapters/$chapterKey/sources/$sourceKey.json.gz"
    }
}

object LibraryCloudKeys {
    fun normalize(value: String?): String {
        return canonicalTextForKey(value)
    }

    fun canonicalTextForKey(value: String?): String {
        val normalized = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC)
        val builder = StringBuilder(normalized.length)
        var index = 0
        while (index < normalized.length) {
            val codePoint = normalized.codePointAt(index)
            index += Character.charCount(codePoint)
            if (shouldDropFromKey(codePoint)) continue
            builder.appendCodePoint(Character.toLowerCase(codePoint))
        }
        return builder.toString()
    }

    fun bookKey(book: Book): String {
        return bookKey(book.name, book.getRealAuthor())
    }

    fun bookKey(name: String, author: String): String {
        return MD5Utils.md5Encode("${normalize(name)}\u001F${normalize(author)}")
    }

    fun sharedBookKey(book: Book): String {
        return sharedBookKey(book.name)
    }

    fun sharedBookKey(name: String): String {
        return MD5Utils.md5Encode("name\u001F${normalizeBookName(name)}")
    }

    fun bookKeys(book: Book): List<String> {
        return listOf(bookKey(book), sharedBookKey(book)).distinct()
    }

    fun chapterKey(chapter: BookChapter): String {
        return MD5Utils.md5Encode(
            listOf(
                chapter.index.toString(),
                normalize(chapter.title),
                chapter.contentCacheIdentity()
            ).joinToString("\u001F")
        )
    }

    fun titleKey(chapter: BookChapter): String {
        return MD5Utils.md5Encode(normalize(chapter.title))
    }

    fun relaxedTitleKey(chapter: BookChapter): String {
        return MD5Utils.md5Encode(relaxedTitle(chapter.title))
    }

    fun ordinalTitleKey(chapter: BookChapter): String? {
        return chapterOrdinal(chapter.title)?.let { MD5Utils.md5Encode(it) }
    }

    fun libraryChapterKey(chapter: BookChapter): String {
        val relaxed = relaxedTitle(chapter.title)
        val ordinal = chapterOrdinal(chapter.title).orEmpty()
        val seed = listOf(relaxed, ordinal)
            .filter { it.isNotBlank() }
            .joinToString("\u001F")
            .ifBlank { normalize(chapter.title).ifBlank { chapter.index.toString() } }
        return MD5Utils.md5Encode(seed)
    }

    fun sourceKey(sourceUrl: String?): String {
        return MD5Utils.md5Encode(normalize(sourceUrl))
    }

    fun matchKeys(chapter: BookChapter): List<LibraryCloudMatchKey> {
        val relaxed = LibraryCloudMatchKey("relaxed", relaxedTitleKey(chapter))
        val strict = LibraryCloudMatchKey("title", titleKey(chapter))
        val ordinal = ordinalTitleKey(chapter)?.let { LibraryCloudMatchKey("ordinal", it) }
        return listOfNotNull(relaxed, strict, ordinal)
            .distinctBy { "${it.kind}\u001F${it.key}" }
    }

    fun variantMatchKeys(chapter: BookChapter): List<LibraryCloudMatchKey> {
        val relaxed = LibraryCloudMatchKey("relaxed", relaxedTitleKey(chapter))
        val ordinal = ordinalTitleKey(chapter)?.let { LibraryCloudMatchKey("ordinal", it) }
        return listOfNotNull(relaxed, ordinal)
            .distinctBy { "${it.kind}\u001F${it.key}" }
    }

    fun sourceChapterKey(sourceUrl: String, chapterKey: String): String {
        return MD5Utils.md5Encode("${normalize(sourceUrl)}\u001F$chapterKey")
    }

    fun urlHash(chapter: BookChapter): String {
        return MD5Utils.md5Encode(runCatching { chapter.getAbsoluteURL() }.getOrElse { chapter.url })
    }

    fun identityHash(chapter: BookChapter): String {
        return MD5Utils.md5Encode(chapter.contentCacheIdentity())
    }

    fun contentHash(content: String): String {
        return MD5Utils.md5Encode(content)
    }

    fun relaxedTitle(value: String?): String {
        val normalized = normalize(value)
        val converted = chineseNumberRegex.replace(normalized) {
            chineseNumberToLong(it.value)?.toString() ?: it.value
        }
        return removePunctuation(converted
            .replace(Regex("(chapter|chap|volume|vol|正文|章节|第|章|回|节|卷|集|部|篇)"), "")
        )
    }

    fun chapterOrdinal(value: String?): String? {
        val normalized = normalize(value)
        if (normalized.isBlank()) return null
        val converted = chineseNumberRegex.replace(normalized) {
            chineseNumberToLong(it.value)?.toString() ?: it.value
        }
        val ordinal = ordinalRegexes.firstNotNullOfOrNull { regex ->
            regex.find(converted)?.groupValues?.getOrNull(1)
        } ?: return null
        return ordinal.trimStart('0').ifBlank { "0" }
    }

    fun normalizeBookName(value: String?): String {
        return removePunctuation(normalize(value))
    }

    fun payloadMatches(book: Book, chapter: BookChapter, payload: LibraryChapterPayloadV2): Boolean {
        if (payload.content.isBlank()) return false
        return bookMatches(book, payload.name, payload.author, payload.normalizedAuthor) &&
            titleMatches(
                chapter = chapter,
                title = payload.title,
                normalizedTitle = payload.normalizedTitle,
                payloadRelaxedTitle = payload.relaxedTitle,
                ordinalTitle = payload.ordinalTitle
            )
    }

    fun payloadMatches(book: Book, chapter: BookChapter, payload: LibraryChapterPayloadV3): Boolean {
        if (payload.content.isBlank()) return false
        return bookMatches(book, payload.name, payload.author, payload.normalizedAuthor) &&
            titleMatches(
                chapter = chapter,
                title = payload.title,
                normalizedTitle = payload.normalizedTitle,
                payloadRelaxedTitle = payload.relaxedTitle,
                ordinalTitle = payload.ordinalTitle
            )
    }

    fun manifestMatches(book: Book, chapter: BookChapter, manifest: LibraryChapterManifestV3): Boolean {
        return bookMatches(book, manifest.name, manifest.author, manifest.normalizedAuthor) &&
            titleMatches(
                chapter = chapter,
                title = manifest.title,
                normalizedTitle = manifest.normalizedTitle,
                payloadRelaxedTitle = manifest.relaxedTitle,
                ordinalTitle = manifest.ordinalTitle
            )
    }

    fun variantMatches(chapter: BookChapter, variant: LibraryChapterVariantV3): Boolean {
        return titleMatches(
            chapter = chapter,
            title = variant.title,
            normalizedTitle = variant.normalizedTitle,
            payloadRelaxedTitle = variant.relaxedTitle,
            ordinalTitle = variant.ordinalTitle
        )
    }

    fun debugCodePoints(value: String?): String {
        val text = value.orEmpty()
        if (text.isBlank()) return ""
        val parts = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            parts += "U+${codePoint.toString(16).uppercase(Locale.ROOT).padStart(4, '0')}"
        }
        return parts.joinToString(" ")
    }

    private fun removePunctuation(value: String): String {
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            index += Character.charCount(codePoint)
            if (isPunctuation(codePoint)) continue
            builder.appendCodePoint(codePoint)
        }
        return builder.toString()
    }

    private fun shouldDropFromKey(codePoint: Int): Boolean {
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) return true
        if (Character.isISOControl(codePoint)) return true
        return when (Character.getType(codePoint)) {
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.PRIVATE_USE.toInt(),
            Character.SURROGATE.toInt(),
            Character.UNASSIGNED.toInt() -> true
            else -> isDecorativeSymbolBlock(codePoint)
        }
    }

    private fun isDecorativeSymbolBlock(codePoint: Int): Boolean {
        return when (Character.UnicodeBlock.of(codePoint)?.toString()) {
            "VARIATION_SELECTORS",
            "VARIATION_SELECTORS_SUPPLEMENT",
            "EMOTICONS",
            "MISCELLANEOUS_SYMBOLS",
            "MISCELLANEOUS_SYMBOLS_AND_PICTOGRAPHS",
            "TRANSPORT_AND_MAP_SYMBOLS",
            "SUPPLEMENTAL_SYMBOLS_AND_PICTOGRAPHS",
            "SYMBOLS_AND_PICTOGRAPHS_EXTENDED_A",
            "DINGBATS" -> true
            else -> false
        }
    }

    private fun isPunctuation(codePoint: Int): Boolean {
        return when (Character.getType(codePoint)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    private fun bookMatches(
        book: Book,
        name: String,
        author: String,
        normalizedAuthor: String
    ): Boolean {
        if (name.isNotBlank() && normalizeBookName(name) != normalizeBookName(book.name)) {
            return false
        }
        val bookAuthor = normalize(book.getRealAuthor())
        val payloadAuthor = normalizedAuthor.ifBlank { normalize(author) }
        return bookAuthor.isBlank() || payloadAuthor.isBlank() || bookAuthor == payloadAuthor
    }

    private fun titleMatches(
        chapter: BookChapter,
        title: String,
        normalizedTitle: String,
        payloadRelaxedTitle: String,
        ordinalTitle: String
    ): Boolean {
        val strict = normalize(chapter.title)
        val relaxed = relaxedTitle(chapter.title)
        val ordinal = chapterOrdinal(chapter.title).orEmpty()
        val payloadStrict = normalizedTitle.ifBlank { normalize(title) }
        val payloadRelaxed = payloadRelaxedTitle.ifBlank { relaxedTitle(title) }
        val payloadOrdinal = ordinalTitle.ifBlank { chapterOrdinal(title).orEmpty() }
        return (strict.isNotBlank() && strict == payloadStrict) ||
            (relaxed.isNotBlank() && relaxed == payloadRelaxed) ||
            (ordinal.isNotBlank() && ordinal == payloadOrdinal)
    }

    private fun chineseNumberToLong(value: String): Long? {
        if (value.isBlank()) return null
        var result = 0L
        var section = 0L
        var number = 0L
        value.forEach { char ->
            when (char) {
                '零', '〇' -> number = 0
                '一' -> number = 1
                '二', '两' -> number = 2
                '三' -> number = 3
                '四' -> number = 4
                '五' -> number = 5
                '六' -> number = 6
                '七' -> number = 7
                '八' -> number = 8
                '九' -> number = 9
                '十' -> {
                    section += (number.takeIf { it > 0 } ?: 1) * 10
                    number = 0
                }
                '百' -> {
                    section += (number.takeIf { it > 0 } ?: 1) * 100
                    number = 0
                }
                '千' -> {
                    section += (number.takeIf { it > 0 } ?: 1) * 1000
                    number = 0
                }
                '万' -> {
                    result += (section + number).coerceAtLeast(1) * 10000
                    section = 0
                    number = 0
                }
                else -> return null
            }
        }
        return result + section + number
    }

    private val chineseNumberRegex = Regex("[零〇一二两三四五六七八九十百千万]+")
    private val ordinalRegexes = listOf(
        Regex("(?:第)?([0-9]{1,7})(?:章|回|节|卷|集|部|篇|话)"),
        Regex("(?:chapter|chap|volume|vol)([0-9]{1,7})"),
        Regex("^\\D{0,8}?([0-9]{1,7})")
    )
}
