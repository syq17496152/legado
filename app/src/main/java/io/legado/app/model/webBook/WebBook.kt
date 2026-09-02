package io.legado.app.model.webBook

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.VideoBookChapterHelper
import io.legado.app.help.book.addType
import io.legado.app.help.book.removeAllBookType
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.SourceNetworkClient
import io.legado.app.help.source.getBookType
import io.legado.app.help.video.MacCmsNormalizer
import io.legado.app.help.webView.WebViewPool
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.ui.main.explore.ExploreAdapter.Companion.exploreInfoMapList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

@Suppress("MemberVisibilityCanBePrivate")
object WebBook {

    /**
     * 目录加载结果快照：book 用 copy() 隔离，防止跟随者读到主任务加载过程中的半初始化状态
     */
    private data class ChapterListResult(
        val book: Book,
        val chapters: List<BookChapter>
    )

    /**
     * 在飞目录加载任务表：按 key 去重，同 key 并发进入时跟随者复用主任务结果
     */
    private val chapterListJobs = ConcurrentHashMap<String, Deferred<Result<ChapterListResult>>>()

    /**
     * 搜索
     */
    fun searchBook(
        scope: CoroutineScope,
        bookSource: BookSource,
        key: String,
        page: Int? = 1,
        context: CoroutineContext = Dispatchers.IO,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        executeContext: CoroutineContext = Dispatchers.Main,
    ): Coroutine<ArrayList<SearchBook>> {
        return Coroutine.async(scope, context, start = start, executeContext = executeContext) {
            searchBookAwait(bookSource, key, page)
        }
    }

    suspend fun searchBookAwait(
        bookSource: BookSource,
        key: String,
        page: Int? = 1,
        filter: ((name: String, author: String, kind: String?) -> Boolean)? = null,
        shouldBreak: ((size: Int) -> Boolean)? = null
    ): ArrayList<SearchBook> {
        val searchUrl = bookSource.searchUrl
        if (searchUrl.isNullOrBlank()) {
            throw NoStackTraceException("搜索url不能为空")
        }
        val ruleData = RuleData()
        AppLog.putDebugWithTag(AppLog.TAG_WEB_BOOK, "搜索开始 page=$page keyLen=${key.length}", level = AppLog.Level.INFO)
        val analyzeUrl = AnalyzeUrl(
            mUrl = searchUrl,
            key = key,
            page = page,
            baseUrl = bookSource.bookSourceUrl,
            source = bookSource,
            ruleData = ruleData,
            coroutineContext = currentCoroutineContext()
        )
        // M6 SourceNetworkClient 统一网络请求 + 登录检测 + 重定向检测 + lastHost 回填
        val res = SourceNetworkClient.requestWithLoginCheck(
            analyzeUrl = analyzeUrl,
            source = bookSource,
            checkJs = bookSource.loginCheckJs
        )
        return BookList.analyzeBookList(
            bookSource = bookSource,
            ruleData = ruleData,
            analyzeUrl = analyzeUrl,
            baseUrl = res.url,
            body = res.body,
            isSearch = true,
            isRedirect = res.raw.priorResponse?.isRedirect == true,
            filter = filter,
            shouldBreak = shouldBreak
        )
    }

    /**
     * 发现
     */
    fun exploreBook(
        scope: CoroutineScope,
        bookSource: BookSource,
        url: String,
        page: Int? = 1,
        context: CoroutineContext = Dispatchers.IO,
        webViewPoolScope: WebViewPool.Scope = WebViewPool.Scope.GLOBAL,
        shouldBreak: ((size: Int) -> Boolean)? = null,
    ): Coroutine<List<SearchBook>> {
        return Coroutine.async(scope, context) {
            exploreBookAwait(bookSource, url, page, webViewPoolScope, shouldBreak)
        }
    }

    suspend fun exploreBookAwait(
        bookSource: BookSource,
        url: String,
        page: Int? = 1,
        webViewPoolScope: WebViewPool.Scope = WebViewPool.Scope.GLOBAL,
        shouldBreak: ((size: Int) -> Boolean)? = null,
    ): ArrayList<SearchBook> {
        val ruleData = RuleData()
        AppLog.putDebugWithTag(AppLog.TAG_WEB_BOOK, "发现页请求开始 page=$page", level = AppLog.Level.INFO)
        val sourceUrl = bookSource.bookSourceUrl
        val exploreInfoMap = exploreInfoMapList[sourceUrl]
        val analyzeUrl = AnalyzeUrl(
            mUrl = url,
            page = page,
            baseUrl = sourceUrl,
            source = bookSource,
            ruleData = ruleData,
            coroutineContext = currentCoroutineContext(),
            infoMap = exploreInfoMap,
            webViewPoolScope = webViewPoolScope
        )
        // M6 SourceNetworkClient 统一网络请求 + 登录检测 + 重定向检测 + lastHost 回填
        val res = SourceNetworkClient.requestWithLoginCheck(
            analyzeUrl = analyzeUrl,
            source = bookSource,
            checkJs = bookSource.loginCheckJs
        )
        return BookList.analyzeBookList(
            bookSource = bookSource,
            ruleData = ruleData,
            analyzeUrl = analyzeUrl,
            baseUrl = res.url,
            body = res.body,
            isSearch = false,
            shouldBreak = shouldBreak
        )
    }

    /**
     * 书籍信息
     */
    fun getBookInfo(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        context: CoroutineContext = Dispatchers.IO,
        canReName: Boolean = true,
    ): Coroutine<Book> {
        return Coroutine.async(scope, context) {
            getBookInfoAwait(bookSource, book, canReName)
        }
    }

    suspend fun getBookInfoAwait(
        bookSource: BookSource,
        book: Book,
        canReName: Boolean = true,
    ): Book {
        AppLog.putDebugWithTag(AppLog.TAG_WEB_BOOK, "获取书籍信息开始", level = AppLog.Level.INFO)
        book.removeAllBookType()
        book.addType(bookSource.getBookType())
        if (!book.infoHtml.isNullOrEmpty()) {
            BookInfo.analyzeBookInfo(
                bookSource = bookSource,
                book = book,
                baseUrl = book.bookUrl,
                redirectUrl = book.bookUrl,
                body = book.infoHtml,
                canReName = canReName
            )
        } else {
            val analyzeUrl = AnalyzeUrl(
                mUrl = book.bookUrl,
                baseUrl = bookSource.bookSourceUrl,
                source = bookSource,
                ruleData = book,
                coroutineContext = currentCoroutineContext()
            )
            // M6 SourceNetworkClient 统一网络请求 + 登录检测 + 重定向检测 + lastHost 回填
            val res = SourceNetworkClient.requestWithLoginCheck(
                analyzeUrl = analyzeUrl,
                source = bookSource,
                checkJs = bookSource.loginCheckJs
            )
            BookInfo.analyzeBookInfo(
                bookSource = bookSource,
                book = book,
                baseUrl = book.bookUrl,
                redirectUrl = res.url,
                body = res.body,
                canReName = canReName
            )
        }
        return book
    }

    /**
     * 目录
     */
    fun getChapterList(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        runPerJs: Boolean = false,
        context: CoroutineContext = Dispatchers.IO,
        isFromBookInfo : Boolean = false
    ): Coroutine<List<BookChapter>> {
        return Coroutine.async(scope, context) {
            getChapterListAwait(bookSource, book, runPerJs,isFromBookInfo).getOrThrow()
        }
    }

    suspend fun runPreUpdateJs(bookSource: BookSource, book: Book, isFromBookInfo : Boolean = false): Result<Unit> {
        return kotlin.runCatching {
            val preUpdateJs = bookSource.ruleToc?.preUpdateJs
            if (!preUpdateJs.isNullOrBlank()) {
                AnalyzeRule(book, bookSource, true, isFromBookInfo)
                    .setCoroutineContext(currentCoroutineContext())
                    .evalJS(preUpdateJs)
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("执行preUpdateJs规则失败 书源:${bookSource.bookSourceName}", it)
        }
    }

    suspend fun getChapterListAwait(
        bookSource: BookSource,
        book: Book,
        runPerJs: Boolean = false,
        isFromBookInfo : Boolean = false
    ): Result<List<BookChapter>> {
        val key = chapterListLoadKey(bookSource, book, runPerJs)
        val job = CoroutineScope(currentCoroutineContext()).async(start = CoroutineStart.LAZY) {
            loadChapterListAwait(bookSource, book, runPerJs, isFromBookInfo)
        }
        val runningJob = chapterListJobs.putIfAbsent(key, job)
        return if (runningJob == null) {
            // 主任务：执行加载并在结束后移除在飞标记（两参 remove 防误删后继任务）
            job.await()
                .onFailure {
                    currentCoroutineContext().ensureActive()
                }
                .map { it.chapters }
                .also {
                    chapterListJobs.remove(key, job)
                }
        } else {
            // 跟随者：LAZY 未启动取消零成本，等待主任务结果并回填书籍状态
            job.cancel()
            runningJob.await()
                .onSuccess {
                    applyChapterListBookState(book, it.book)
                }
                .onFailure {
                    currentCoroutineContext().ensureActive()
                }
                .map { it.chapters }
        }
    }

    /**
     * 目录加载体（同 key 去重后的唯一执行体）
     */
    private suspend fun loadChapterListAwait(
        bookSource: BookSource,
        book: Book,
        runPerJs: Boolean = false,
        isFromBookInfo : Boolean = false
    ): Result<ChapterListResult> {
        AppLog.putDebugWithTag(AppLog.TAG_WEB_BOOK, "获取目录开始", level = AppLog.Level.INFO)
        book.removeAllBookType()
        book.addType(bookSource.getBookType())
        return kotlin.runCatching {
            if (runPerJs) {
                runPreUpdateJs(bookSource, book, isFromBookInfo).getOrThrow()
            }
            // video-booksource-multiroute：视频书源目录分流（严格隔离分支，文本书源/订阅源路径零改动）
            // L0 零规则（MacCMS 自动规范化直产卷章）/ L1 规则写法（注入双结构后走既有解析）/
            // L2 CSS·XPath·正则 / L3 JS（非 MacCMS body 原样返回，走既有解析路径不受影响）
            if (bookSource.bookSourceType == BookSourceType.video) {
                val videoChapters = videoBookChapterListAwait(bookSource, book)
                if (videoChapters != null) {
                    return@runCatching ChapterListResult(book.copy(), videoChapters)
                }
                // videoBookChapterListAwait 返回 null：无 tocUrl 等基础数据缺失，交回通用路径报错
            }
            val chapters = if (book.bookUrl == book.tocUrl && !book.tocHtml.isNullOrEmpty()) {
                BookChapterList.analyzeChapterList(
                    bookSource = bookSource,
                    book = book,
                    baseUrl = book.tocUrl,
                    redirectUrl = book.tocUrl,
                    body = book.tocHtml,
                    isFromBookInfo = isFromBookInfo
                )
            } else {
                val analyzeUrl = AnalyzeUrl(
                    mUrl = book.tocUrl,
                    baseUrl = book.bookUrl,
                    source = bookSource,
                    ruleData = book,
                    coroutineContext = currentCoroutineContext()
                )
                // M6 SourceNetworkClient 统一网络请求 + 登录检测 + 重定向检测 + lastHost 回填
                val res = SourceNetworkClient.requestWithLoginCheck(
                    analyzeUrl = analyzeUrl,
                    source = bookSource,
                    checkJs = bookSource.loginCheckJs
                )
                BookChapterList.analyzeChapterList(
                    bookSource = bookSource,
                    book = book,
                    baseUrl = book.tocUrl,
                    redirectUrl = res.url,
                    body = res.body,
                    isFromBookInfo = isFromBookInfo
                )
            }
            ChapterListResult(book.copy(), chapters)
        }.onFailure {
            currentCoroutineContext().ensureActive()
        }
    }

    /**
     * video-booksource-multiroute：视频书源目录加载（L0/L1/L2/L3 分流）
     *
     * 1. 请求 tocUrl 得 body（tocHtml 缓存逻辑与通用路径一致）
     * 2. MacCmsNormalizer 规范化（非 MacCMS body 原样返回）
     * 3. MacCMS 且 chapterList 规则为空 → L0：Helper 直产卷章
     * 4. 其他 → 既有 analyzeChapterList（L1 消费注入的 $.chapters[*]；L2/L3 原样走既有解析）
     *
     * @return 卷章列表；book.tocUrl 为空返回 null（交回通用路径自然报错）
     */
    private suspend fun videoBookChapterListAwait(
        bookSource: BookSource,
        book: Book
    ): List<BookChapter>? {
        if (book.tocUrl.isNullOrBlank()) return null
        val body: String = if (book.bookUrl == book.tocUrl && !book.tocHtml.isNullOrEmpty()) {
            book.tocHtml!!
        } else {
            val analyzeUrl = AnalyzeUrl(
                mUrl = book.tocUrl,
                baseUrl = book.bookUrl,
                source = bookSource,
                ruleData = book,
                coroutineContext = currentCoroutineContext()
            )
            // M6 SourceNetworkClient 统一网络请求 + 登录检测（与通用路径同款）
            SourceNetworkClient.requestWithLoginCheck(
                analyzeUrl = analyzeUrl,
                source = bookSource,
                checkJs = bookSource.loginCheckJs
            ).body.orEmpty()
        }
        val normalized = MacCmsNormalizer.normalize(body)
        val isMacCms = normalized != body
        if (isMacCms && bookSource.ruleToc?.chapterList.isNullOrBlank()) {
            // L0 零规则：routes 结构直产卷章
            val chapters = VideoBookChapterHelper.buildFromMacCms(normalized.orEmpty(), book, book.tocUrl)
            if (chapters != null) {
                // 缓存规范化 body，覆盖安装/重进复用（与通用路径 tocHtml 语义一致）
                book.tocHtml = normalized
                return chapters
            }
        }
        // L1 规则写法 / L2 HTML 站 / L3 JS：走既有解析（MacCMS 时传注入双结构后的 body）
        return BookChapterList.analyzeChapterList(
            bookSource = bookSource,
            book = book,
            baseUrl = book.tocUrl,
            redirectUrl = book.tocUrl,
            body = normalized ?: body,
            isFromBookInfo = false
        )
    }

    /**
     * 目录加载去重键：四因素，不含 isFromBookInfo（跟随者直接复用主任务结果）
     */
    private fun chapterListLoadKey(
        bookSource: BookSource,
        book: Book,
        runPerJs: Boolean
    ): String {
        return listOf(
            bookSource.bookSourceUrl,
            book.bookUrl,
            book.tocUrl,
            runPerJs
        ).joinToString("\n")
    }

    /**
     * 跟随者回填主任务解析后的书籍状态，保证与主任务实际解析用的 book 一致
     */
    private fun applyChapterListBookState(target: Book, source: Book) {
        target.bookUrl = source.bookUrl
        target.tocUrl = source.tocUrl
        target.origin = source.origin
        target.originName = source.originName
        target.name = source.name
        target.author = source.author
        target.kind = source.kind
        target.coverUrl = source.coverUrl
        target.intro = source.intro
        target.charset = source.charset
        target.type = source.type
        target.latestChapterTitle = source.latestChapterTitle
        target.latestChapterTime = source.latestChapterTime
        target.lastCheckTime = source.lastCheckTime
        target.lastCheckCount = source.lastCheckCount
        target.totalChapterNum = source.totalChapterNum
        target.wordCount = source.wordCount
        target.originOrder = source.originOrder
        target.variable = source.variable
        target.syncTime = source.syncTime
        target.infoHtml = source.infoHtml
        target.tocHtml = source.tocHtml
        target.downloadUrls = source.downloadUrls
    }

    /**
     * 章节内容
     */
    fun getContent(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String? = null,
        needSave: Boolean = true,
        context: CoroutineContext = Dispatchers.IO,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        executeContext: CoroutineContext = Dispatchers.Main,
        semaphore: Semaphore? = null,
    ): Coroutine<String> {
        return Coroutine.async(
            scope,
            context,
            start = start,
            executeContext = executeContext,
            semaphore = semaphore
        ) {
            getContentAwait(bookSource, book, bookChapter, nextChapterUrl, needSave)
        }
    }

    suspend fun getContentAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String? = null,
        needSave: Boolean = true
    ): String {
        AppLog.putDebugWithTag(AppLog.TAG_WEB_BOOK, "获取正文开始", level = AppLog.Level.INFO)
        val contentRule = bookSource.getContentRule()
        if (contentRule.content.isNullOrEmpty()) {
            Debug.log(bookSource.bookSourceUrl, "⇒正文规则为空,使用章节链接:${bookChapter.url}")
            return bookChapter.url
        }
        if (bookChapter.isVolume && bookChapter.url.startsWith(bookChapter.title)) {
            Debug.log(bookSource.bookSourceUrl, "⇒一级目录正文不解析规则")
            return bookChapter.tag ?: ""
        }
        return if (bookChapter.url == book.bookUrl && !book.tocHtml.isNullOrEmpty()) {
            BookContent.analyzeContent(
                bookSource = bookSource,
                book = book,
                bookChapter = bookChapter,
                baseUrl = bookChapter.getAbsoluteURL(),
                redirectUrl = bookChapter.getAbsoluteURL(),
                body = book.tocHtml,
                nextChapterUrl = nextChapterUrl,
                needSave = needSave
            )
        } else {
            val analyzeUrl = AnalyzeUrl(
                mUrl = bookChapter.getAbsoluteURL(),
                baseUrl = book.tocUrl,
                source = bookSource,
                ruleData = book,
                chapter = bookChapter,
                coroutineContext = currentCoroutineContext()
            )
            // M6 SourceNetworkClient 统一网络请求 + 登录检测 + 重定向检测 + lastHost 回填
            // 仅此处传入 jsStr/sourceRegex（contentRule.webJs/sourceRegex）
            val res = SourceNetworkClient.requestWithLoginCheck(
                analyzeUrl = analyzeUrl,
                source = bookSource,
                checkJs = bookSource.loginCheckJs,
                jsStr = contentRule.webJs,
                sourceRegex = contentRule.sourceRegex
            )
            BookContent.analyzeContent(
                bookSource = bookSource,
                book = book,
                bookChapter = bookChapter,
                baseUrl = bookChapter.getAbsoluteURL(),
                redirectUrl = res.url,
                body = res.body,
                nextChapterUrl = nextChapterUrl,
                needSave = needSave
            )
        }
    }

    /**
     * 精准搜索
     */
    fun preciseSearch(
        scope: CoroutineScope,
        bookSourceParts: List<BookSourcePart>,
        name: String,
        author: String,
        context: CoroutineContext = Dispatchers.IO,
        semaphore: Semaphore? = null,
    ): Coroutine<Pair<Book, BookSource>> {
        return Coroutine.async(scope, context, semaphore = semaphore) {
            for (s in bookSourceParts) {
                val source = s.getBookSource() ?: continue
                val book = preciseSearchAwait(source, name, author).getOrNull()
                if (book != null) {
                    return@async Pair(book, source)
                }
            }
            throw NoStackTraceException("没有搜索到<$name>$author")
        }
    }

    suspend fun preciseSearchAwait(
        bookSource: BookSource,
        name: String,
        author: String,
    ): Result<Book> {
        return kotlin.runCatching {
            currentCoroutineContext().ensureActive()
            searchBookAwait(
                bookSource, name,
                filter = { fName, fAuthor, _ -> fName == name && fAuthor == author },
                shouldBreak = { it > 0 }
            ).firstOrNull()?.let { searchBook ->
                currentCoroutineContext().ensureActive()
                return@runCatching searchBook.toBook()
            }
            throw NoStackTraceException("未搜索到 $name($author) 书籍")
        }.onFailure {
            currentCoroutineContext().ensureActive()
        }
    }

}