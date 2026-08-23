package io.legado.app.help.book.library

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookCloudEntryMode
import io.legado.app.help.book.BookCloudEntryModeStore
import io.legado.app.help.book.isLocal
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

object LibraryCloudSync {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadLocks = ConcurrentHashMap<String, Mutex>()
    private val dailyUploadLock = Mutex()
    private val sessions = ConcurrentHashMap<String, LibraryCloudSession>()
    private val activeBooks = ConcurrentHashMap<String, Boolean>()

    fun clearSessions() {
        sessions.clear()
    }

    fun enqueueUpload(book: Book, chapter: BookChapter, content: String) {
        if (!canUpload(book, chapter, content)) return
        val config = LibraryContainerManager.matchForSource(book.origin) ?: return
        val uploadContent = LibraryCloudContent.toUploadText(content)
        if (!shouldUploadContent(config, uploadContent)) return
        val lockKey = "${config.id}\u001F${LibraryCloudKeys.bookKey(book)}\u001F${LibraryCloudKeys.libraryChapterKey(chapter)}"
        val lock = uploadLocks.getOrPut(lockKey) { Mutex() }
        scope.launch {
            lock.withLock {
                runCatching {
                    uploadChapter(config, book, chapter, uploadContent)
                }.onFailure {
                    AppLog.put("上传书库章节失败 ${book.name} ${chapter.title}\n${it.localizedMessage}", it)
                }
            }
        }
    }

    suspend fun openSession(book: Book): LibraryCloudSession {
        val config = readConfig(book)
        val key = sessionKey(config, book)
        if (key != null) {
            sessions[key]?.let { return it }
        }
        val session = LibraryCloudSession.open(book, config)
        if (key != null) {
            sessions[key] = session
        }
        return session
    }

    suspend fun refreshSession(book: Book): LibraryCloudSession {
        val config = readConfig(book)
        val key = sessionKey(config, book)
        if (key != null) sessions.remove(key)
        return openSession(book)
    }

    fun setCloudReadingActive(book: Book, active: Boolean) {
        val key = activeKey(book)
        if (active) {
            activeBooks[key] = true
        } else {
            activeBooks.remove(key)
        }
    }

    fun isCloudReadingActive(book: Book): Boolean {
        return activeBooks[activeKey(book)] == true
    }

    suspend fun tryCloudFirst(book: Book, chapter: BookChapter): String? {
        val session = openSession(book)
        return session.downloadCurrentChapter(chapter)
    }

    suspend fun tryCloudFallback(book: Book, chapter: BookChapter): String? {
        readConfig(book) ?: return null
        val session = openSession(book)
        return session.downloadCurrentChapter(chapter)
    }

    suspend fun tryActiveCloud(book: Book, chapter: BookChapter): String? {
        if (!isCloudReadingActive(book)) return null
        return tryCloudFirst(book, chapter)
    }

    private fun readConfig(book: Book): LibraryContainerConfig? {
        if (BookCloudEntryModeStore.get(book.bookUrl) != BookCloudEntryMode.LIBRARY_CHAPTER) {
            return null
        }
        return LibraryContainerManager.readContainer()
    }

    private fun canUpload(book: Book, chapter: BookChapter, content: String): Boolean {
        if (book.isLocal) return false
        if (chapter.isVolume) return false
        if (content.isBlank()) return false
        if (content.startsWith("获取正文失败") || content.startsWith("加载正文失败")) return false
        if (!NetworkUtils.isAvailable()) return false
        return true
    }

    private fun shouldUploadContent(config: LibraryContainerConfig, content: String): Boolean {
        if (content.isBlank()) return false
        val minChars = config.minUploadChars
        if (minChars <= 0) return true
        return LibraryCloudContent.meaningfulCharCount(content) >= minChars
    }

    private fun sessionKey(config: LibraryContainerConfig?, book: Book): String? {
        config ?: return null
        return "${config.id}\u001F${LibraryCloudKeys.sharedBookKey(book)}"
    }

    private fun activeKey(book: Book): String {
        return "${LibraryCloudKeys.bookKey(book)}\u001F${book.bookUrl}"
    }

    private suspend fun uploadChapter(
        config: LibraryContainerConfig,
        book: Book,
        chapter: BookChapter,
        content: String
    ) {
        val backend = LibraryCloudBackend(config)
        val sharedBookKey = LibraryCloudKeys.sharedBookKey(book)
        val exactBookKey = LibraryCloudKeys.bookKey(book)
        val chapterKey = LibraryCloudKeys.libraryChapterKey(chapter)
        val sourceKey = LibraryCloudKeys.sourceKey(book.origin)
        val now = System.currentTimeMillis()
        val sharedCurrentPath = LibraryCloudPaths.v3CurrentPath(sharedBookKey, chapterKey)
        val exactCurrentPath = LibraryCloudPaths.v3CurrentPath(exactBookKey, chapterKey)
        val contentHash = LibraryCloudKeys.contentHash(content)
        val payload = LibraryChapterPayloadV3(
            bookKey = sharedBookKey,
            chapterKey = chapterKey,
            sourceKey = sourceKey,
            name = book.name,
            author = book.getRealAuthor(),
            normalizedName = LibraryCloudKeys.normalize(book.name),
            normalizedAuthor = LibraryCloudKeys.normalize(book.getRealAuthor()),
            title = chapter.title,
            normalizedTitle = LibraryCloudKeys.normalize(chapter.title),
            relaxedTitle = LibraryCloudKeys.relaxedTitle(chapter.title),
            ordinalTitle = LibraryCloudKeys.chapterOrdinal(chapter.title).orEmpty(),
            chapterIndex = chapter.index,
            sourceUrl = book.origin,
            sourceName = book.originName,
            sourceBookUrl = book.bookUrl,
            sourceChapterIdentity = chapter.contentCacheIdentity(),
            contentHash = contentHash,
            content = content,
            updatedAt = now
        )
        val sharedState = readCurrentState(backend, config, book, chapter, sharedCurrentPath, contentHash)
        if (sharedState == CurrentState.MATCHED) {
            return
        }
        val targetPath = if (sharedState == CurrentState.MISSING || sharedState == CurrentState.CONTENT_CHANGED) {
            sharedCurrentPath
        } else {
            val exactState = readCurrentState(backend, config, book, chapter, exactCurrentPath, contentHash)
            if (exactState == CurrentState.MATCHED) return
            exactCurrentPath
        }
        val targetBookKey = if (targetPath == sharedCurrentPath) sharedBookKey else exactBookKey
        if (!reserveDailyUpload(config)) return
        backend.upload(
            targetPath,
            LibraryCloudCrypto.encodeJson(payload.copy(bookKey = targetBookKey), config.password, gzip = true),
            "application/json"
        )
        sessions.remove(sessionKey(config, book))
    }

    private suspend fun reserveDailyUpload(config: LibraryContainerConfig): Boolean {
        val limit = config.dailyUploadLimit
        if (limit <= 0) return true
        return dailyUploadLock.withLock {
            val today = LocalDate.now().toString()
            val state = readDailyUploadState()
            val counts = if (state.day == today) {
                state.counts.toMutableMap()
            } else {
                mutableMapOf()
            }
            val current = counts[config.id] ?: 0
            if (current >= limit) {
                false
            } else {
                counts[config.id] = current + 1
                appCtx.putPrefString(PREF_DAILY_UPLOAD_STATE, GSON.toJson(LibraryDailyUploadState(today, counts)))
                true
            }
        }
    }

    private fun readDailyUploadState(): LibraryDailyUploadState {
        return GSON.fromJsonObject<LibraryDailyUploadState>(
            appCtx.getPrefString(PREF_DAILY_UPLOAD_STATE).orEmpty()
        ).getOrNull() ?: LibraryDailyUploadState()
    }

    private suspend fun readCurrentState(
        backend: LibraryCloudBackend,
        config: LibraryContainerConfig,
        book: Book,
        chapter: BookChapter,
        path: String,
        contentHash: String
    ): CurrentState {
        val bytes = backend.downloadOrNull(path) ?: return CurrentState.MISSING
        val payload = runCatching {
            val json = LibraryCloudCrypto.decodeString(bytes, config.password)
            GSON.fromJsonObject<LibraryChapterPayloadV3>(json).getOrThrow()
        }.onFailure {
            AppLog.put("读取书库current章节失败 $path\n${it.localizedMessage}", it)
        }.getOrNull() ?: return CurrentState.CONFLICT
        return if (LibraryCloudKeys.payloadMatches(book, chapter, payload)) {
            if (payload.contentHash == contentHash) {
                CurrentState.MATCHED
            } else {
                CurrentState.CONTENT_CHANGED
            }
        } else {
            CurrentState.CONFLICT
        }
    }

    private const val PREF_DAILY_UPLOAD_STATE = "libraryCloudDailyUploadState"

    private enum class CurrentState {
        MISSING,
        CONTENT_CHANGED,
        MATCHED,
        CONFLICT
    }
}

private data class LibraryDailyUploadState(
    val day: String = "",
    val counts: Map<String, Int> = emptyMap()
)
