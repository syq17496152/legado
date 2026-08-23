package io.legado.app.model.localBook.epubcore.facade

import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getLocalUri
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.localBook.epubcore.cache.EpubCoreDiskCache
import io.legado.app.model.localBook.epubcore.font.EpubTypefaceResolver
import io.legado.app.model.localBook.epubcore.image.EpubImageResolver
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import io.legado.app.model.localBook.epubcore.layout.EpubCorePage
import io.legado.app.model.localBook.epubcore.web.EpubWebSelectionAction
import io.legado.app.model.localBook.epubcore.web.EpubWebSelectionPayload
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.isContentScheme
import splitties.init.appCtx
import java.io.File

object EpubCoreProvider {

    private var holder: Holder? = null

    @Synchronized
    fun getChapterList(book: Book): ArrayList<BookChapter> {
        val cacheDir = BookHelp.getCacheDir(book)
        val signature = EpubCoreDiskCache.bookSignature(book)
        EpubCoreDiskCache.readChapterList(cacheDir, signature)?.let { cached ->
            return ArrayList(cached.mapIndexed { index, chapter ->
                chapter.copy(bookUrl = book.bookUrl, index = index)
            })
        }
        return ArrayList(open(book).facade.chapters().mapIndexed { index, chapter ->
            chapter.copy(bookUrl = book.bookUrl, index = index)
        })
    }

    suspend fun paginate(
        book: Book,
        chapterIndex: Int,
        config: EpubCoreLayoutConfig,
        backgroundSlot: Int? = null
    ): List<EpubCorePage> {
        val facade = synchronized(this) { open(book).facade }
        return facade.paginate(chapterIndex, config, backgroundSlot)
    }

    suspend fun paginate(
        book: Book,
        chapter: BookChapter,
        config: EpubCoreLayoutConfig,
        backgroundSlot: Int? = null
    ): List<EpubCorePage> {
        val facade = synchronized(this) { open(book).facade }
        return facade.paginate(chapter, config, backgroundSlot)
    }

    fun peekPages(
        book: Book,
        chapterIndex: Int,
        config: EpubCoreLayoutConfig
    ): List<EpubCorePage>? {
        val facade = synchronized(this) { holder?.takeIf { it.bookUrl == book.bookUrl }?.facade }
            ?: return null
        return facade.peekPages(chapterIndex, config)
    }

    fun peekPages(
        book: Book,
        chapter: BookChapter,
        config: EpubCoreLayoutConfig
    ): List<EpubCorePage>? {
        val facade = synchronized(this) { holder?.takeIf { it.bookUrl == book.bookUrl }?.facade }
            ?: return null
        return facade.peekPages(chapter, config)
    }

    suspend fun selectText(
        book: Book,
        page: EpubCorePage,
        chapterIndex: Int,
        pageIndex: Int,
        config: EpubCoreLayoutConfig,
        action: EpubWebSelectionAction,
        x: Float,
        y: Float
    ): EpubWebSelectionPayload? {
        val facade = synchronized(this) { open(book).facade }
        return facade.selectText(page, chapterIndex, pageIndex, config, action, x, y)
    }

    @Synchronized
    fun imageResolver(book: Book): EpubImageResolver {
        return open(book).facade.imageResolver()
    }

    @Synchronized
    fun imageResolverOrNull(book: Book): EpubImageResolver? {
        return runCatching {
            open(book).facade.imageResolver()
        }.onFailure {
            AppLog.putDebug("EPUB core image resolver unavailable: ${it.localizedMessage}", it)
        }.getOrNull()
    }

    @Synchronized
    fun typefaceResolver(book: Book): EpubTypefaceResolver {
        return open(book).facade.typefaceResolver()
    }

    @Synchronized
    fun typefaceResolverOrNull(book: Book): EpubTypefaceResolver? {
        return runCatching {
            open(book).facade.typefaceResolver()
        }.onFailure {
            AppLog.putDebug("EPUB core typeface resolver unavailable: ${it.localizedMessage}", it)
        }.getOrNull()
    }

    @Synchronized
    fun cancelBackgroundLayouts(book: Book? = null) {
        val current = holder ?: return
        if (book != null && current.bookUrl != book.bookUrl) return
        current.facade.cancelBackgroundLayouts()
    }

    @Synchronized
    fun cancelForegroundLayout(book: Book? = null) {
        val current = holder ?: return
        if (book != null && current.bookUrl != book.bookUrl) return
        current.facade.cancelForegroundLayout()
    }

    @Synchronized
    fun clear() {
        holder?.close()
        holder = null
    }

    @Synchronized
    fun clear(bookUrl: String) {
        if (holder?.bookUrl == bookUrl) {
            clear()
        }
    }

    @Synchronized
    fun clearBookCache(book: Book) {
        clear(book.bookUrl)
        EpubCoreDiskCache.clear(BookHelp.getCacheDir(book))
    }

    private fun open(book: Book): Holder {
        holder?.takeIf { it.bookUrl == book.bookUrl }?.let { return it }
        holder?.close()
        val file = resolveReadableFile(book)
        val facade = EpubCoreFacade.open(
            file = file,
            bookUrl = book.bookUrl,
            bookCacheDir = BookHelp.getCacheDir(book)
        )
        return Holder(book.bookUrl, file, facade).also { holder = it }
    }

    private fun resolveReadableFile(book: Book): File {
        val uri = book.getLocalUri()
        if (!uri.isContentScheme()) {
            uri.path?.let { path ->
                val file = File(path)
                if (file.isFile) return file
            }
        }
        val cacheDir = File(appCtx.cacheDir, "epub-core").apply { mkdirs() }
        val suffix = book.originName.substringAfterLast('.', "epub").ifBlank { "epub" }
        val signature = if (uri.isContentScheme()) {
            val doc = DocumentFile.fromSingleUri(appCtx, uri)
            "${book.bookUrl}|${doc?.length() ?: 0L}|${doc?.lastModified() ?: 0L}"
        } else {
            val file = File(uri.path.orEmpty())
            "${book.bookUrl}|${file.length()}|${file.lastModified()}"
        }
        val cacheFile = File(cacheDir, "${MD5Utils.md5Encode16(signature)}.$suffix")
        if (cacheFile.isFile && cacheFile.length() > 0L) {
            return cacheFile
        }
        LocalBook.getBookInputStream(book).use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return cacheFile
    }

    private data class Holder(
        val bookUrl: String,
        val file: File,
        val facade: EpubCoreFacade
    ) {
        fun close() {
            runCatching { facade.close() }.onFailure {
                AppLog.putDebug("EPUB core close failed: ${it.localizedMessage}", it)
            }
        }
    }
}
