package io.legado.app.model

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.model.webBook.WebBook

object MediaTocRefresh {

    /**
     * 章节地址是否属「占位」——源侧按章名动态给出（`url` 为空或就是章名本身）时，
     * 目录必须重新获取才能拿到真实地址。
     *
     * 纯判定（不读设置、不联网）⇒ JVM 可测（[io.legado.app.model.MediaTocRefreshTest]）。
     * 判据口径与 [BookChapter.getAbsoluteURL] 的占位分支（`url.startsWith(title)`）一致。
     */
    fun isPlaceholderUrl(chapter: BookChapter): Boolean {
        if (chapter.isVolume) return false
        val url = chapter.url.trim()
        return url.isBlank() || url.startsWith(chapter.title)
    }

    fun shouldRefresh(chapter: BookChapter): Boolean {
        if (!AppConfig.autoRefreshMediaToc) return false
        return isPlaceholderUrl(chapter)
    }

    suspend fun refresh(
        source: BookSource,
        book: Book,
        index: Int,
        runPreUpdateJs: Boolean = false,
        fromBookInfo: Boolean = false
    ): BookChapter? {
        if (!AppConfig.autoRefreshMediaToc) return null
        val oldBook = book.copy()
        val oldChapters = appDb.bookChapterDao.getChapterList(oldBook.bookUrl)
        val chapters = WebBook.getChapterListAwait(
            source,
            book,
            runPerJs = runPreUpdateJs,
            isFromBookInfo = fromBookInfo
        ).getOrThrow()
        validateChapters(oldChapters, chapters, index)
        appDb.runInTransaction {
            BookHelp.remapContentCache(oldBook, oldChapters, chapters)
            if (oldBook.bookUrl == book.bookUrl) {
                appDb.bookDao.update(book)
            } else {
                appDb.bookDao.replace(oldBook, book)
                BookHelp.updateCacheFolder(oldBook, book)
            }
            appDb.bookChapterDao.delByBook(oldBook.bookUrl)
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
        }
        return chapters.getOrNull(index)
    }

    private fun validateChapters(
        oldChapters: List<BookChapter>,
        newChapters: List<BookChapter>,
        targetIndex: Int
    ) {
        if (newChapters.isEmpty()) {
            throw NoStackTraceException("media catalog refresh returned empty toc")
        }
        if (newChapters.getOrNull(targetIndex) == null) {
            throw NoStackTraceException("media catalog refresh lost current chapter")
        }
        if (oldChapters.size >= 3 && newChapters.size < oldChapters.size / 2) {
            throw NoStackTraceException("media catalog refresh returned too few chapters")
        }
    }
}
