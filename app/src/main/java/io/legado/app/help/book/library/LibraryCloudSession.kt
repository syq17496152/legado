package io.legado.app.help.book.library

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonObject

data class LibraryCloudSession(
    val config: LibraryContainerConfig?,
    val book: Book,
    val state: LibraryCloudState,
    val errorMessage: String? = null
) {

    val isEnabled: Boolean get() = config != null

    suspend fun downloadChapter(chapter: BookChapter): String? {
        return downloadCurrentChapter(chapter)
    }

    suspend fun downloadCurrentChapter(chapter: BookChapter): String? {
        val cfg = config ?: return null
        if (state != LibraryCloudState.READY) return null
        val backend = LibraryCloudBackend(cfg)
        downloadCurrentChapterCurrentV3(backend, chapter)?.let { return it }
        downloadCurrentChapterManifestV3(backend, chapter)?.let { return it }
        for (path in currentChapterPaths(chapter)) {
            val payload = readPayloadOrNull(backend, path) ?: continue
            if (LibraryCloudKeys.payloadMatches(book, chapter, payload)) {
                return payload.content.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private suspend fun downloadCurrentChapterCurrentV3(
        backend: LibraryCloudBackend,
        chapter: BookChapter
    ): String? {
        for (path in v3CurrentPaths(chapter)) {
            val payload = readPayloadV3OrNull(backend, path) ?: continue
            if (LibraryCloudKeys.payloadMatches(book, chapter, payload)) {
                return payload.content.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private suspend fun downloadCurrentChapterManifestV3(
        backend: LibraryCloudBackend,
        chapter: BookChapter
    ): String? {
        for (path in v3ManifestPaths(chapter)) {
            val manifest = readManifestOrNull(backend, path) ?: continue
            if (!LibraryCloudKeys.manifestMatches(book, chapter, manifest)) continue
            val variant = selectVariant(manifest, chapter) ?: continue
            val payload = readPayloadV3OrNull(backend, variant.payloadPath) ?: continue
            if (!LibraryCloudKeys.payloadMatches(book, chapter, payload)) continue
            return payload.content.takeIf { it.isNotBlank() }
        }
        return null
    }

    suspend fun listChapterVersions(chapter: BookChapter): List<LibraryCloudChapterVersion> {
        val cfg = config ?: return emptyList()
        if (state != LibraryCloudState.READY) return emptyList()
        val backend = LibraryCloudBackend(cfg)
        val v3Versions = listCurrentVersionsV3(backend, chapter) + listManifestVersionsV3(backend, chapter)
        if (v3Versions.isNotEmpty()) return sortVersions(v3Versions, chapter)
        val versions = currentChapterPaths(chapter).mapNotNull { path ->
            val payload = readPayloadOrNull(backend, path) ?: return@mapNotNull null
            if (LibraryCloudKeys.payloadMatches(book, chapter, payload)) {
                LibraryCloudChapterVersion(path, payload, "v2-current")
            } else {
                null
            }
        }
        return sortVersions(versions, chapter)
    }

    suspend fun downloadChapter(version: LibraryCloudChapterVersion): String? {
        val cfg = config ?: return null
        val backend = LibraryCloudBackend(cfg)
        if (version.schemaVersion >= 3) {
            val payload = readPayloadV3OrNull(backend, version.path) ?: return null
            if (version.payload.contentHash.isNotBlank() && payload.contentHash != version.payload.contentHash) {
                return null
            }
            return payload.content.takeIf { it.isNotBlank() }
        }
        val payload = readPayloadOrNull(backend, version.path) ?: return null
        return payload.content.takeIf { it.isNotBlank() }
    }

    suspend fun deleteChapter(version: LibraryCloudChapterVersion): Boolean {
        val cfg = config ?: return false
        if (version.path.isBlank()) return false
        val backend = LibraryCloudBackend(cfg)
        val deleted = backend.delete(version.path)
        if (deleted && version.schemaVersion >= 3 && version.matchKind == "v3-manifest") {
            removeManifestVersion(backend, version)
        }
        return deleted
    }

    private suspend fun readPayloadOrNull(
        backend: LibraryCloudBackend,
        path: String
    ): LibraryChapterPayloadV2? {
        val cfg = config ?: return null
        return runCatching {
            val bytes = backend.downloadOrNull(path) ?: return null
            val json = LibraryCloudCrypto.decodeString(bytes, cfg.password)
            GSON.fromJsonObject<LibraryChapterPayloadV2>(json).getOrThrow()
        }.onFailure {
            AppLog.put("读取书库章节失败 ${book.name} $path\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    private suspend fun listCurrentVersionsV3(
        backend: LibraryCloudBackend,
        chapter: BookChapter
    ): List<LibraryCloudChapterVersion> {
        return v3CurrentPaths(chapter).mapNotNull { path ->
            val payload = readPayloadV3OrNull(backend, path) ?: return@mapNotNull null
            if (LibraryCloudKeys.payloadMatches(book, chapter, payload)) {
                LibraryCloudChapterVersion(
                    path = path,
                    payload = payload.toDisplayPayload(),
                    matchKind = "v3-current",
                    schemaVersion = 3
                )
            } else {
                null
            }
        }
    }

    private suspend fun listManifestVersionsV3(
        backend: LibraryCloudBackend,
        chapter: BookChapter
    ): List<LibraryCloudChapterVersion> {
        return v3ManifestPaths(chapter).flatMap { path ->
            val manifest = readManifestOrNull(backend, path) ?: return@flatMap emptyList()
            if (!LibraryCloudKeys.manifestMatches(book, chapter, manifest)) return@flatMap emptyList()
            manifest.variants
                .filter { it.payloadPath.isNotBlank() && LibraryCloudKeys.variantMatches(chapter, it) }
                .map {
                    LibraryCloudChapterVersion(
                        path = it.payloadPath,
                        payload = it.toDisplayPayload(manifest),
                        matchKind = "v3-manifest",
                        schemaVersion = 3
                    )
                }
        }
    }

    private suspend fun readPayloadV3OrNull(
        backend: LibraryCloudBackend,
        path: String
    ): LibraryChapterPayloadV3? {
        val cfg = config ?: return null
        if (path.isBlank()) return null
        return runCatching {
            val bytes = backend.downloadOrNull(path) ?: return null
            val json = LibraryCloudCrypto.decodeString(bytes, cfg.password)
            GSON.fromJsonObject<LibraryChapterPayloadV3>(json).getOrThrow()
        }.onFailure {
            AppLog.put("读取书库v3章节失败 ${book.name} $path\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    private suspend fun readManifestOrNull(
        backend: LibraryCloudBackend,
        path: String
    ): LibraryChapterManifestV3? {
        val cfg = config ?: return null
        return runCatching {
            val bytes = backend.downloadOrNull(path) ?: return null
            val json = LibraryCloudCrypto.decodeString(bytes, cfg.password)
            GSON.fromJsonObject<LibraryChapterManifestV3>(json).getOrThrow()
        }.onFailure {
            AppLog.put("读取书库v3清单失败 ${book.name} $path\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    private suspend fun removeManifestVersion(
        backend: LibraryCloudBackend,
        version: LibraryCloudChapterVersion
    ) {
        val cfg = config ?: return
        val payload = version.payload
        if (payload.bookKey.isBlank() || payload.chapterKey.isBlank()) return
        val path = LibraryCloudPaths.v3ManifestPath(payload.bookKey, payload.chapterKey)
        val manifest = readManifestOrNull(backend, path) ?: return
        val variants = manifest.variants.filterNot { it.payloadPath == version.path }
        if (variants.size == manifest.variants.size) return
        if (variants.isEmpty()) {
            backend.delete(path)
        } else {
            backend.upload(
                path,
                LibraryCloudCrypto.encodeJson(manifest.copy(variants = variants), cfg.password, gzip = true),
                "application/json"
            )
        }
    }

    private fun selectVariant(
        manifest: LibraryChapterManifestV3,
        chapter: BookChapter
    ): LibraryChapterVariantV3? {
        return manifest.variants
            .filter { it.payloadPath.isNotBlank() && LibraryCloudKeys.variantMatches(chapter, it) }
            .sortedWith(
                compareBy<LibraryChapterVariantV3> {
                    if (it.sourceUrl == book.origin || it.sourceUrl.isBlank()) 0 else 1
                }.thenBy { kotlin.math.abs(it.chapterIndex - chapter.index) }
                    .thenByDescending { it.updatedAt }
            )
            .firstOrNull()
    }

    private fun sortVersions(
        versions: List<LibraryCloudChapterVersion>,
        chapter: BookChapter
    ): List<LibraryCloudChapterVersion> {
        return versions
            .distinctBy {
                listOf(
                    it.schemaVersion,
                    it.payload.sourceKey,
                    it.payload.sourceBookUrl,
                    it.payload.normalizedTitle,
                    it.payload.relaxedTitle,
                    it.payload.contentHash
                ).joinToString("\u001F")
            }
            .sortedWith(
                compareBy<LibraryCloudChapterVersion> {
                    if (it.payload.sourceUrl == book.origin || it.payload.sourceUrl.isBlank()) 0 else 1
                }.thenBy { kotlin.math.abs(it.payload.chapterIndex - chapter.index) }
                    .thenBy { it.payload.sourceName.ifBlank { it.payload.sourceUrl } }
                    .thenByDescending { it.payload.updatedAt }
            )
    }

    private fun LibraryChapterVariantV3.toDisplayPayload(
        manifest: LibraryChapterManifestV3
    ): LibraryChapterPayloadV2 {
        return LibraryChapterPayloadV2(
            version = 3,
            bookKey = manifest.bookKey,
            chapterKey = manifest.chapterKey,
            sourceKey = sourceKey,
            name = manifest.name,
            author = manifest.author,
            normalizedName = manifest.normalizedName,
            normalizedAuthor = manifest.normalizedAuthor,
            title = title.ifBlank { manifest.title },
            normalizedTitle = normalizedTitle.ifBlank { manifest.normalizedTitle },
            relaxedTitle = relaxedTitle.ifBlank { manifest.relaxedTitle },
            ordinalTitle = ordinalTitle.ifBlank { manifest.ordinalTitle },
            chapterIndex = chapterIndex,
            sourceUrl = sourceUrl,
            sourceName = sourceName,
            sourceBookUrl = sourceBookUrl,
            sourceChapterIdentity = sourceChapterIdentity,
            contentHash = contentHash,
            updatedAt = updatedAt
        )
    }

    private fun LibraryChapterPayloadV3.toDisplayPayload(): LibraryChapterPayloadV2 {
        return LibraryChapterPayloadV2(
            version = 3,
            bookKey = bookKey,
            chapterKey = chapterKey,
            sourceKey = sourceKey,
            name = name,
            author = author,
            normalizedName = normalizedName,
            normalizedAuthor = normalizedAuthor,
            title = title,
            normalizedTitle = normalizedTitle,
            relaxedTitle = relaxedTitle,
            ordinalTitle = ordinalTitle,
            chapterIndex = chapterIndex,
            sourceUrl = sourceUrl,
            sourceName = sourceName,
            sourceBookUrl = sourceBookUrl,
            sourceChapterIdentity = sourceChapterIdentity,
            contentHash = contentHash,
            updatedAt = updatedAt
        )
    }

    private fun v3CurrentPaths(chapter: BookChapter): List<String> {
        val chapterKey = LibraryCloudKeys.libraryChapterKey(chapter)
        return v3ReadBookKeys().map { bookKey ->
            LibraryCloudPaths.v3CurrentPath(bookKey, chapterKey)
        }.distinct()
    }

    private fun v3ManifestPaths(chapter: BookChapter): List<String> {
        val chapterKey = LibraryCloudKeys.libraryChapterKey(chapter)
        val bookKeys = listOf(
            LibraryCloudKeys.bookKey(book),
            LibraryCloudKeys.sharedBookKey(book)
        ).distinct()
        return bookKeys.map { bookKey ->
            LibraryCloudPaths.v3ManifestPath(bookKey, chapterKey)
        }.distinct()
    }

    private fun v3ReadBookKeys(): List<String> {
        return listOf(
            LibraryCloudKeys.sharedBookKey(book),
            LibraryCloudKeys.bookKey(book)
        ).distinct()
    }

    private fun currentChapterPaths(chapter: BookChapter): List<String> {
        return LibraryCloudKeys.bookKeys(book).flatMap { bookKey ->
            LibraryCloudKeys.matchKeys(chapter).map { matchKey ->
                LibraryCloudPaths.currentChapterPath(bookKey, matchKey)
            }
        }.distinct()
    }

    companion object {
        suspend fun open(book: Book, config: LibraryContainerConfig?): LibraryCloudSession {
            if (config == null) return LibraryCloudSession(null, book, LibraryCloudState.DISABLED)
            if (!NetworkUtils.isAvailable()) {
                return LibraryCloudSession(config, book, LibraryCloudState.ERROR, errorMessage = "网络不可用")
            }
            return LibraryCloudSession(config, book, LibraryCloudState.READY)
        }
    }
}

enum class LibraryCloudState {
    DISABLED,
    READY,
    ERROR
}
