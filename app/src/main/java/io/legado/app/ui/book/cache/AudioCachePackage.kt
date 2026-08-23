package io.legado.app.ui.book.cache

import io.legado.app.data.entities.BookChapter
import java.io.File
import java.security.MessageDigest

internal const val AUDIO_CACHE_MANIFEST_VERSION = 2
private const val AUDIO_CACHE_MAX_CHAPTERS = 50_000
private const val AUDIO_CACHE_MAX_CHAPTER_INDEX = 1_000_000
private const val AUDIO_CACHE_MAX_FILE_COUNT = 1_000_000
private const val AUDIO_CACHE_MAX_SHORT_TEXT = 16 * 1024
private const val AUDIO_CACHE_MAX_URL_TEXT = 64 * 1024
private const val AUDIO_CACHE_MAX_VARIABLE_TEXT = 256 * 1024

internal data class AudioCacheManifest(
    val version: Int = 0,
    val catalogComplete: Boolean = false,
    val bookName: String? = "",
    val author: String? = "",
    val bookUrl: String? = "",
    val chapters: List<Chapter>? = emptyList()
) {
    val schemaVersion: Int
        get() = version.takeIf { it > 0 } ?: 1

    val hasCompleteCatalog: Boolean
        get() = schemaVersion >= AUDIO_CACHE_MANIFEST_VERSION && catalogComplete

    fun chapterList(): List<Chapter> = chapters.orEmpty()

    data class Chapter(
        val index: Int = 0,
        val title: String? = "",
        val isVolume: Boolean = false,
        val url: String? = "",
        val baseUrl: String? = "",
        val isVip: Boolean = false,
        val isPay: Boolean = false,
        val resourceUrl: String? = null,
        val tag: String? = null,
        val wordCount: String? = null,
        val start: Long? = null,
        val end: Long? = null,
        val startFragmentId: String? = null,
        val endFragmentId: String? = null,
        val variable: String? = null,
        val imgUrl: String? = null,
        val cacheDir: String? = null,
        val fileCount: Int = 0
    ) {
        fun toBookChapter(bookUrl: String): BookChapter {
            return BookChapter(
                url = url.orEmpty(),
                title = title.orEmpty(),
                isVolume = isVolume,
                baseUrl = baseUrl.orEmpty(),
                bookUrl = bookUrl,
                index = index,
                isVip = isVip,
                isPay = isPay,
                resourceUrl = resourceUrl,
                tag = tag,
                wordCount = wordCount,
                start = start,
                end = end,
                startFragmentId = startFragmentId,
                endFragmentId = endFragmentId,
                variable = variable,
                imgUrl = imgUrl
            )
        }

        fun resolvedCacheDir(): String {
            val value = cacheDir?.takeIf { it.isNotBlank() } ?: index.toString()
            require(!File(value).isAbsolute &&
                value != "." &&
                value != ".." &&
                File(value).name == value &&
                !value.contains('/') &&
                !value.contains('\\')
            ) { "invalid audio cache directory" }
            return value
        }

        companion object {
            fun from(chapter: BookChapter, fileCount: Int, cacheDir: String? = null): Chapter {
                return Chapter(
                    index = chapter.index,
                    title = chapter.title,
                    isVolume = chapter.isVolume,
                    url = chapter.url,
                    baseUrl = chapter.baseUrl,
                    isVip = chapter.isVip,
                    isPay = chapter.isPay,
                    resourceUrl = chapter.resourceUrl,
                    tag = chapter.tag,
                    wordCount = chapter.wordCount,
                    start = chapter.start,
                    end = chapter.end,
                    startFragmentId = chapter.startFragmentId,
                    endFragmentId = chapter.endFragmentId,
                    variable = chapter.variable,
                    imgUrl = chapter.imgUrl,
                    cacheDir = cacheDir,
                    fileCount = fileCount.coerceAtLeast(0)
                )
            }
        }
    }
}

internal fun AudioCacheManifest.validateForRestore(expectedBookUrl: String) {
    require(bookUrl.isNullOrBlank() || bookUrl == expectedBookUrl) { "audio cache book mismatch" }
    require(bookName.hasAtMost(AUDIO_CACHE_MAX_SHORT_TEXT)) { "audio cache book name is too long" }
    require(author.hasAtMost(AUDIO_CACHE_MAX_SHORT_TEXT)) { "audio cache author is too long" }
    require(bookUrl.hasAtMost(AUDIO_CACHE_MAX_URL_TEXT)) { "audio cache book URL is too long" }
    require(chapterList().size <= AUDIO_CACHE_MAX_CHAPTERS) { "audio cache contains too many chapters" }
    val cacheDirectories = hashSetOf<String>()
    val chapterIndexes = hashSetOf<Int>()
    val chapterUrls = hashSetOf<String>()
    chapterList().forEach { chapter ->
        require(chapter.index in 0..AUDIO_CACHE_MAX_CHAPTER_INDEX) { "invalid audio chapter index" }
        require(chapter.fileCount in 0..AUDIO_CACHE_MAX_FILE_COUNT) { "invalid audio file count" }
        require(chapter.title.hasAtMost(AUDIO_CACHE_MAX_SHORT_TEXT)) { "audio chapter title is too long" }
        require(chapter.url.hasAtMost(AUDIO_CACHE_MAX_URL_TEXT)) { "audio chapter URL is too long" }
        require(chapter.baseUrl.hasAtMost(AUDIO_CACHE_MAX_URL_TEXT)) { "audio chapter base URL is too long" }
        require(chapter.resourceUrl.hasAtMost(AUDIO_CACHE_MAX_URL_TEXT)) { "audio resource URL is too long" }
        require(chapter.imgUrl.hasAtMost(AUDIO_CACHE_MAX_URL_TEXT)) { "audio chapter image URL is too long" }
        require(chapter.tag.hasAtMost(AUDIO_CACHE_MAX_SHORT_TEXT)) { "audio chapter tag is too long" }
        require(chapter.wordCount.hasAtMost(AUDIO_CACHE_MAX_SHORT_TEXT)) { "audio chapter word count is too long" }
        require(chapter.startFragmentId.hasAtMost(AUDIO_CACHE_MAX_SHORT_TEXT)) { "audio chapter fragment is too long" }
        require(chapter.endFragmentId.hasAtMost(AUDIO_CACHE_MAX_SHORT_TEXT)) { "audio chapter fragment is too long" }
        require(chapter.variable.hasAtMost(AUDIO_CACHE_MAX_VARIABLE_TEXT)) { "audio chapter variables are too long" }
        require(chapter.cacheDir.hasAtMost(255)) { "audio cache directory is too long" }
        require(chapterIndexes.add(chapter.index)) { "duplicate audio chapter index" }
        chapter.url.orEmpty().takeIf { it.isNotBlank() }?.let { url ->
            require(chapterUrls.add(url)) { "duplicate audio chapter url" }
        }
        if (!chapter.isVolume) {
            require(cacheDirectories.add(chapter.resolvedCacheDir())) {
                "duplicate audio cache directory"
            }
        }
    }
}

private fun String?.hasAtMost(maxLength: Int): Boolean = this == null || length <= maxLength

internal fun mergeRestoredAudioCatalog(
    existing: List<BookChapter>,
    incoming: List<BookChapter>,
    replaceCatalog: Boolean,
    preferIncomingResourceUrl: (BookChapter) -> Boolean = { false }
): List<BookChapter> {
    if (incoming.isEmpty()) return existing
    if (existing.isEmpty() || replaceCatalog) return incoming.sortedBy { it.index }

    val merged = existing.map { it.copy() }.toMutableList()
    incoming.forEach { candidate ->
        val matchIndex = merged.indexOfFirst { current ->
            current.matchesAudioChapter(candidate)
        }
        if (matchIndex >= 0) {
            val current = merged[matchIndex]
            if (!candidate.resourceUrl.isNullOrBlank() &&
                (preferIncomingResourceUrl(candidate) || current.resourceUrl.isNullOrBlank())
            ) {
                merged[matchIndex] = current.copy(resourceUrl = candidate.resourceUrl)
            }
            return@forEach
        }

        val conflicts = merged.any { current ->
            current.index == candidate.index ||
                (current.url.isNotBlank() && current.url == candidate.url)
        }
        if (!conflicts) {
            merged.add(candidate.copy())
        }
    }
    return merged.sortedBy { it.index }
}

internal data class AudioPackageChapterMerge(
    val chapter: AudioCacheManifest.Chapter,
    val localSource: BookChapter? = null,
    val replacePackagedFiles: Boolean = false
)

internal fun mergeAudioPackageChapters(
    remote: List<AudioCacheManifest.Chapter>,
    local: List<BookChapter>,
    isLocallyCached: (BookChapter) -> Boolean
): List<AudioPackageChapterMerge> {
    val merged = remote.map { AudioPackageChapterMerge(it) }.toMutableList()
    local.forEach { localChapter ->
        val remoteIndex = merged.indexOfFirst { item -> item.chapter.matches(localChapter) }
        val hasLocalCache = !localChapter.isVolume && isLocallyCached(localChapter)
        if (remoteIndex >= 0) {
            val remoteChapter = merged[remoteIndex].chapter
            val selectedResourceUrl = if (hasLocalCache) {
                localChapter.resourceUrl
            } else {
                remoteChapter.resourceUrl ?: localChapter.resourceUrl
            }
            val updatedLocal = localChapter.copy(resourceUrl = selectedResourceUrl)
            merged[remoteIndex] = AudioPackageChapterMerge(
                chapter = AudioCacheManifest.Chapter.from(
                    chapter = updatedLocal,
                    fileCount = 0,
                    cacheDir = remoteChapter.resolvedCacheDir()
                ),
                localSource = localChapter.takeIf { hasLocalCache },
                replacePackagedFiles = hasLocalCache
            )
        } else {
            merged += AudioPackageChapterMerge(
                chapter = AudioCacheManifest.Chapter.from(
                    chapter = localChapter,
                    fileCount = 0,
                    cacheDir = newAudioCacheDirectoryName(localChapter)
                ),
                localSource = localChapter.takeIf { hasLocalCache },
                replacePackagedFiles = hasLocalCache
            )
        }
    }
    return merged.sortedBy { it.chapter.index }
}

private fun AudioCacheManifest.Chapter.matches(chapter: BookChapter): Boolean {
    val chapterUrl = url.orEmpty()
    if (chapterUrl.isNotBlank() && chapter.url.isNotBlank() && chapterUrl == chapter.url) return true
    if (!resourceUrl.isNullOrBlank() &&
        !chapter.resourceUrl.isNullOrBlank() &&
        resourceUrl == chapter.resourceUrl
    ) {
        return true
    }
    return index == chapter.index && title.orEmpty().trim() == chapter.title.trim()
}

private fun newAudioCacheDirectoryName(chapter: BookChapter): String {
    val identity = when {
        chapter.url.isNotBlank() -> "url|${chapter.url}"
        !chapter.resourceUrl.isNullOrBlank() -> "resource|${chapter.resourceUrl}"
        else -> "index|${chapter.index}|${chapter.title.trim()}"
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
    val suffix = digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "c_${chapter.index}_$suffix"
}

private fun BookChapter.matchesAudioChapter(other: BookChapter): Boolean {
    if (url.isNotBlank() && other.url.isNotBlank() && url == other.url) return true
    if (!resourceUrl.isNullOrBlank() &&
        !other.resourceUrl.isNullOrBlank() &&
        resourceUrl == other.resourceUrl
    ) {
        return true
    }
    return index == other.index && title.trim() == other.title.trim()
}

internal fun countImportableAudioFiles(directory: File): Int {
    return directory.listFiles()
        ?.count { file -> file.isFile && file.length() > 0L && file.hasAudioCacheFileName() }
        ?: 0
}

private fun File.hasAudioCacheFileName(): Boolean {
    val urlIndex = name.substringBefore('_', "").toIntOrNull() ?: return false
    val remainder = name.substringAfter('_', "")
    val position = remainder.substringBefore('_', "").toLongOrNull() ?: return false
    val declaredLength = remainder.substringAfter("${position}_", "")
        .substringBefore('_', "")
        .toLongOrNull()
        ?: return false
    return urlIndex >= 0 &&
        position >= 0L &&
        declaredLength > 0L &&
        declaredLength == length()
}
