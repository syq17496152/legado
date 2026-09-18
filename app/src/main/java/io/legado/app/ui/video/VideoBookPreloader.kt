package io.legado.app.ui.video

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.addType
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.source.isVideoSource
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

object VideoBookPreloader {

    private const val DEFAULT_MAX_PRELOAD = 4
    private val runningKeys = ConcurrentHashMap.newKeySet<String>()
    private val preloadSemaphore = Semaphore(DEFAULT_MAX_PRELOAD)

    fun preloadSearchBooks(
        scope: CoroutineScope,
        books: List<SearchBook>,
        maxCount: Int = DEFAULT_MAX_PRELOAD
    ) {
        books.asSequence()
            .filter { it.bookUrl.isNotBlank() && it.origin.isNotBlank() }
            .take(maxCount.coerceAtLeast(1) * 3)
            .forEach { preload(scope, it) }
    }

    fun preload(scope: CoroutineScope, searchBook: SearchBook) {
        val key = "${searchBook.origin}|${searchBook.bookUrl}"
        if (!runningKeys.add(key)) return
        scope.launch(IO) {
            try {
                preloadSemaphore.withPermit {
                    preloadAwait(searchBook)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("Video toc preload failed: ${searchBook.name}\n${e.localizedMessage}", e)
            } finally {
                runningKeys.remove(key)
            }
        }
    }

    private suspend fun preloadAwait(searchBook: SearchBook): Boolean {
        if (searchBook.bookUrl.isBlank() || searchBook.origin.isBlank()) return false
        if (appDb.bookChapterDao.getChapterList(searchBook.bookUrl).isNotEmpty()) return true
        val source = appDb.bookSourceDao.getBookSource(searchBook.origin) ?: return false
        // video-source-dual-track AD-02：判定收口为统一 helper（静态源类型 OR 运行时 book.type）
        if (!source.isVideoSource(searchBook.type)) {
            return false
        }
        val stored = appDb.bookDao.getBook(searchBook.bookUrl)
        val inBookshelf = stored?.isNotShelf == false
        val book = stored ?: searchBook.toBook()
        val resolvedBook = loadInfoIfNeeded(book)
        val chapters = WebBook.getChapterListAwait(
            bookSource = source,
            book = resolvedBook,
            runPerJs = inBookshelf,
            isFromBookInfo = true
        ).getOrThrow()
        if (chapters.isEmpty()) return false
        if (!inBookshelf) {
            resolvedBook.addType(BookType.notShelf)
        }
        resolvedBook.save()
        appDb.bookChapterDao.delByBook(resolvedBook.bookUrl)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        return true
    }

    /**
     * video-source-multiline-l0-preload AD-02：播放器切换链目录预取（按 bookUrl，fire-and-forget）
     *
     * 进入播放器/切换影片完成后预取"前方一部"目录写库，使 initSource 命中 DB 缓存秒起播。
     * - 已入库跳过（幂等）；源缺失/非视频书源跳过；失败静默（绝对不阻塞播放）
     * - 复用 preloadSemaphore 限流防并发风暴
     * - 诊断日志（VideoRoutesDiag）：inDb/volumes/total/loadMs 供复测验证预取生效
     */
    fun preloadBookByUrl(scope: CoroutineScope, bookUrl: String, origin: String) {
        if (bookUrl.isBlank() || origin.isBlank()) return
        val key = "$origin|$bookUrl"
        if (!runningKeys.add(key)) return
        val logTag = bookUrl.takeLast(24)
        scope.launch(IO) {
            try {
                preloadSemaphore.withPermit {
                    if (appDb.bookChapterDao.getChapterList(bookUrl).isNotEmpty()) {
                        AppLog.put("VideoRoutesDiag preloadBook: urlEnd=$logTag, inDb=true, skip")
                        return@withPermit
                    }
                    val source = appDb.bookSourceDao.getBookSource(origin) ?: run {
                        AppLog.put("VideoRoutesDiag preloadBook: urlEnd=$logTag, source=null, skip")
                        return@withPermit
                    }
                    // video-source-dual-track AD-02：判定收口为统一 helper；此处复用下方原有的 stored 查询
                    // （上移至此），不新增 DB 访问——源静态类型与运行时 book.type 任一侧为视频即预取
                    val stored = appDb.bookDao.getBook(bookUrl)
                    if (!source.isVideoSource(stored?.type)) {
                        AppLog.put("VideoRoutesDiag preloadBook: urlEnd=$logTag, notVideo, skip")
                        return@withPermit
                    }
                    val startMs = System.currentTimeMillis()
                    val inBookshelf = stored?.isNotShelf == false
                    // 书源多线路直产修复（2026-09-14 用户真机铁证 bindLegacyInfo: book=http）：
                    // name 保持空串而非 bookUrl，WebBook.getBookInfoAwait(canReName=false) 内
                    // analyzeBookInfo 仅当 book.name.isEmpty() 才用真实剧名覆盖——若预设 URL 名将永不纠正，
                    // 传统模式标题区会直接显示 URL（旧 bug：书源信息区/切换后标题显示 http...）
                    val book: Book = stored ?: Book().apply {
                        this.bookUrl = bookUrl
                        this.origin = origin
                        name = ""
                    }
                    val resolvedBook = loadInfoIfNeeded(book)
                    val chapters = WebBook.getChapterListAwait(
                        bookSource = source,
                        book = resolvedBook,
                        runPerJs = inBookshelf,
                        isFromBookInfo = true
                    ).getOrThrow()
                    if (chapters.isEmpty()) {
                        AppLog.put("VideoRoutesDiag preloadBook: urlEnd=$logTag, chapters=0, skip")
                        return@withPermit
                    }
                    if (!inBookshelf) {
                        resolvedBook.addType(BookType.notShelf)
                    }
                    resolvedBook.save()
                    appDb.bookChapterDao.delByBook(resolvedBook.bookUrl)
                    appDb.bookChapterDao.insert(*chapters.toTypedArray())
                    AppLog.put(
                        "VideoRoutesDiag preloadBook: urlEnd=$logTag, inDb=false, " +
                            "volumes=${chapters.count { it.isVolume }}, total=${chapters.size}, " +
                            "loadMs=${System.currentTimeMillis() - startMs}"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("VideoRoutesDiag preloadBook failed: urlEnd=$logTag", e)
            } finally {
                runningKeys.remove(key)
            }
        }
    }

    private suspend fun loadInfoIfNeeded(book: Book): Book {
        val source = appDb.bookSourceDao.getBookSource(book.origin) ?: return book
        return if (book.tocUrl.isBlank()) {
            WebBook.getBookInfoAwait(source, book, canReName = false)
        } else {
            book
        }
    }
}
