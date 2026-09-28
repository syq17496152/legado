package io.legado.app.service.kernel

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import com.bumptech.glide.Glide
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.CacheManager
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.model.BookCover
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.relay.RelayContentSanitizer
import io.legado.app.utils.cnCompare
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/**
 * 书籍域业务内核（web-mcp-productization 一期 · 2.1 / REQ-1-201 · REQ-1-202）。
 *
 * **契约**（design §1.3.1 / §1.4.3）：
 * - **只返回领域对象**（`Book` / `BookChapter` / `Bitmap` / `String`），**不返回 HTTP 信封** `ReturnData`
 *   —— 这样二期 MCP 可直接复用本层，不必把 HTTP 语义带进 MCP；
 * - **全链挂起 + 零 `runBlocking`**（REQ-1-202）：DAO 访问一律 `withContext(IO)`；
 *   HTTP 同步边界（`serve()` / Controller 门面）才允许 `runBlocking`；
 * - 失败用**异常**表达（[NoStackTraceException] 等），由调用方决定话术（Web 门面 → `errorMsg`，
 *   MCP → 工具错误）。
 *
 * **只搬家不改行为**（design §2「原则」）：本类逻辑自 `api/controller/BookController.kt` 原样迁入，
 * 仅把 `runBlocking(IO) { … }` 换成 `withContext(IO) { … }`；错误话术仍由门面层拼装，故对外可见文案不变。
 *
 * 已知上限（**pre-existing，本次仅搬家未改**）：封面/正文图片缓存用的是非线程安全的 `WeakHashMap`，
 * 而 NanoHTTPD 为多线程处理请求 ⇒ 并发下理论上有竞态；升级路径：换 `ConcurrentHashMap` +
 * 显式淘汰策略（不属本期范围，避免"搬家顺手重构"引入回归）。
 */
object BookKernel {

    /** 正文分页默认不启用（`length = null` ⇒ 返回整章），见 [sliceContent]。 */
    const val CONTENT_NO_LIMIT: Int = -1

    private var cachedBook: Book? = null
    private var cachedBookSource: BookSource? = null
    private var cachedBookUrl: String = ""
    private val defaultCoverCache by lazy { WeakHashMap<Drawable, Bitmap>() }

    // ============================================================ 读

    /** 书架全部书籍，**已按 `AppConfig.bookshelfSort` 排序**（与原 Controller 行为一致）。 */
    suspend fun bookshelf(): List<Book> {
        val books = withContext(IO) { appDb.bookDao.all }
        return sortedByMode(books, AppConfig.bookshelfSort)
    }

    /** 章节列表（空列表表示尚未拉取过目录，由调用方决定是否触发 [refreshToc]）。 */
    suspend fun chapterList(bookUrl: String): List<BookChapter> =
        withContext(IO) { appDb.bookChapterDao.getChapterList(bookUrl) }

    /**
     * 刷新目录并落库，返回新目录。
     *
     * @throws NoStackTraceException 书籍不存在（"未在数据库找到对应书籍，请先添加"）
     *   或书源缺失（"未找到对应书源,请换源"）—— 话术与原 Controller 完全一致。
     */
    suspend fun refreshToc(bookUrl: String): List<BookChapter> {
        val book = withContext(IO) { appDb.bookDao.getBook(bookUrl) }
            ?: throw NoStackTraceException("未在数据库找到对应书籍，请先添加")
        val toc = if (book.isLocal) {
            LocalBook.getChapterList(book)
        } else {
            val bookSource = withContext(IO) { appDb.bookSourceDao.getBookSource(book.origin) }
                ?: throw NoStackTraceException("未找到对应书源,请换源")
            if (book.tocUrl.isBlank()) {
                WebBook.getBookInfoAwait(bookSource, book)
            }
            WebBook.getChapterListAwait(bookSource, book).getOrThrow()
        }
        withContext(IO) {
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*toc.toTypedArray())
            appDb.bookDao.update(book)
        }
        return toc
    }

    /**
     * 取正文（已过内容处理链：替换规则 / 简繁 / 重新分段）。
     *
     * @return null = 书籍或章节不存在（调用方话术："未找到"）
     * @throws Exception 书源缺失或网络取正文失败（调用方转 errorMsg）
     */
    suspend fun bookContent(bookUrl: String, index: Int): String? =
        bookContent(bookUrl, index, 0, CONTENT_NO_LIMIT)

    /**
     * 取正文（可选分页，见 [sliceContent] / 决策 #13）：缺省参数即整章，与老行为一致。
     */
    suspend fun bookContent(bookUrl: String, index: Int, offset: Int, length: Int): String? {
        val full = loadContent(bookUrl, index) ?: return null
        return sliceContent(full, offset, length)
    }

    private suspend fun loadContent(bookUrl: String, index: Int): String? {
        val book = withContext(IO) { appDb.bookDao.getBook(bookUrl) } ?: return null
        val chapter = withContext(IO) {
            // 章节可能正被目录刷新写入 ⇒ 轮询等待（沿用原实现的 30s 上限）
            var chapter = appDb.bookChapterDao.getChapter(bookUrl, index)
            var wait = 0
            while (chapter == null && wait < 30) {
                delay(1000)
                chapter = appDb.bookChapterDao.getChapter(bookUrl, index)
                wait++
            }
            chapter
        } ?: return null
        val content = BookHelp.getContent(book, chapter) ?: run {
            val bookSource = withContext(IO) { appDb.bookSourceDao.getBookSource(book.origin) }
                ?: throw NoStackTraceException("未找到书源")
            WebBook.getContentAwait(bookSource, book, chapter)
        }
        return ContentProcessor.get(book.name, book.origin)
            .getContent(book, chapter, content, includeTitle = false)
            .toString()
    }

    /**
     * 封面位图（含默认封面兜底）。
     *
     * 失败语义与原 Controller 一致：**兜底封面也失败时抛异常**（由门面转 `errorMsg`），
     * 而不是静默返回 null —— 否则"取封面失败"会变成"返回空图"，前端无从区分。
     */
    suspend fun cover(path: String?): Bitmap = withContext(IO) {
        val ftBitmap = ImageLoader.loadBitmap(appCtx, path)
            .override(84, 112)
            .centerCrop()
            .submit()
        try {
            ftBitmap.get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // 缓存键是 Drawable 本身（原实现语义）；非线程安全为 pre-existing（见类注释）
            defaultCoverCache.getOrPut(BookCover.defaultDrawable) {
                Glide.with(appCtx)
                    .asBitmap()
                    .load(BookCover.defaultDrawable.toBitmap())
                    .override(84, 112)
                    .centerCrop()
                    .submit()
                    .get()
            }
        }
    }

    /**
     * 正文内嵌图片位图。
     *
     * 复用 [cachedBook]/[cachedBookSource]（同书连续取图免重复查库，原 Controller 行为）。
     */
    suspend fun image(bookUrl: String, src: String, width: Int): Bitmap {
        if (cachedBookUrl != bookUrl || cachedBook == null) {
            val book = withContext(IO) { appDb.bookDao.getBook(bookUrl) }
                ?: throw NoStackTraceException("bookUrl不对")
            cachedBook = book
            cachedBookSource = withContext(IO) { appDb.bookSourceDao.getBookSource(book.origin) }
            cachedBookUrl = bookUrl
        }
        val book = cachedBook!!
        val bookSource = cachedBookSource
        ImageProvider.cacheImage(book, src, bookSource)
        return withContext(IO) { ImageProvider.getImage(book, src, width) }
    }

    /** Web 阅读界面配置（无配置返回 null）。 */
    suspend fun webReadConfig(): String? = CacheManager.get("webReadConfig")

    // ============================================================ 写

    suspend fun saveBook(book: Book) {
        AppWebDav.uploadBookProgress(book)
        book.save()
    }

    suspend fun deleteBook(book: Book) {
        book.delete()
    }

    /**
     * 保存阅读进度。
     *
     * @return false = 数据库中不存在该书（与原 Controller 的"格式不对"分支等价）
     */
    suspend fun saveBookProgress(bookProgress: BookProgress): Boolean {
        val book = withContext(IO) {
            appDb.bookDao.getBook(bookProgress.name, bookProgress.author)
        } ?: return false
        book.durChapterIndex = bookProgress.durChapterIndex
        book.durChapterPos = bookProgress.durChapterPos
        book.durChapterTitle = bookProgress.durChapterTitle
        book.durChapterTime = bookProgress.durChapterTime
        AppWebDav.uploadBookProgress(bookProgress) {
            book.syncTime = System.currentTimeMillis()
        }
        withContext(IO) { appDb.bookDao.update(book) }
        ReadBook.book?.let {
            if (it.name == bookProgress.name && it.author == bookProgress.author) {
                ReadBook.webBookProgress = bookProgress
            }
        }
        return true
    }

    /** 导入本地书籍（保存上传文件 + 入库）。 */
    suspend fun addLocalBook(fileName: String, filePath: String) {
        val uri = LocalBook.saveBookFile(File(filePath).inputStream(), fileName)
        LocalBook.importFile(uri)
    }

    suspend fun saveWebReadConfig(json: String?) {
        if (json != null) {
            CacheManager.put("webReadConfig", json)
        } else {
            CacheManager.delete("webReadConfig")
        }
    }

    // ============================================================ relay 专用

    /** relay（订阅内容中转）封面：按书籍展示封面取图。 */
    suspend fun relayBookCover(bookUrl: String): Bitmap {
        val book = withContext(IO) { appDb.bookDao.getBook(bookUrl) }
            ?: throw NoStackTraceException("bookUrl不对")
        return cover(book.getDisplayCover())
    }

    /** relay 正文：取正文后**去掉图片标记**（中转省流）。 */
    suspend fun relayBookContent(bookUrl: String, index: Int): String {
        val content = bookContent(bookUrl, index) ?: throw NoStackTraceException("正文格式无效")
        return RelayContentSanitizer.removeImages(content)
    }

    // ============================================================ 纯函数（可 JVM 直测）

    /**
     * 书架排序（原 Controller 的 `when (AppConfig.bookshelfSort)` 分支原样搬出）。
     *
     * 1 = 最近更新章节倒序；2 = 书名中文排序；3 = 用户自定义 `order`；其余（含 0）= 最近阅读倒序。
     */
    internal fun sortedByMode(books: List<Book>, mode: Int): List<Book> = when (mode) {
        1 -> books.sortedByDescending { it.latestChapterTime }
        2 -> books.sortedWith { o1, o2 -> o1.name.cnCompare(o2.name) }
        3 -> books.sortedBy { it.order }
        else -> books.sortedByDescending { it.durChapterTime }
    }

    /**
     * 阅读进度合法性校验（2.1.3 / REQ-1-309，**口径已按决策 #12 改写**）。
     *
     * 原设计的「进度 0-1 校验」在本 API **不存在**：`BookProgress.durChapterPos` 是**字符偏移**，
     * `durChapterIndex` 是章节序号。且该端点被**内置阅读器跨端同步**使用 —— 若照设计做
     * 「`durChapterIndex in chapters.indices`」硬校验，会在**本机章节列表为空/不一致**时
     * **拒绝合法进度**（安全回归）。
     *
     * 故只做**非负**这一条**不可能误伤**的校验。
     *
     * @return null = 合法；否则返回给用户看的错误话术
     */
    internal fun validateProgress(progress: BookProgress): String? = when {
        progress.durChapterIndex < 0 -> "章节序号不能为负数（${progress.durChapterIndex}）"
        progress.durChapterPos < 0 -> "阅读位置不能为负数（${progress.durChapterPos}）"
        else -> null
    }

    /**
     * 正文分页切片（2.1.5 / REQ-1-308）。
     *
     * **设计口径修订（决策 #13）**：设计写「单次长度上限，超限分段」，但**服务端强制截断会直接
     * 破坏内置阅读器**（它需要整章正文自行分页），而分段协议需客户端配合（客户端在**独立前端仓**）。
     * 故本层提供**可选**分页能力：`offset`/`length` 缺省时**原样返回整章**（老页/阅读器零影响），
     * 显式传参时按 `[offset, offset+length)` 切段 —— 二期 MCP / 三期控制台可直接采用。
     *
     * @param offset 起始字符偏移（<0 视为 0）
     * @param length 取用长度；[CONTENT_NO_LIMIT]（或 <0）表示不限
     */
    internal fun sliceContent(content: String, offset: Int, length: Int): String {
        val start = offset.coerceAtLeast(0)
        if (start >= content.length) return ""
        val end = if (length < 0) content.length else (start + length).coerceAtMost(content.length)
        return content.substring(start, end)
    }
}
