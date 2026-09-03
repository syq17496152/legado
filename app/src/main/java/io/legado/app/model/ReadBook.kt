package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PageAnim.scrollPageAnim
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReadRecentBook
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReadRecord
import io.legado.app.help.AppWebDav
import io.legado.app.help.HighlightRuleMatcher
import io.legado.app.help.HighlightTextBuilder
import io.legado.app.help.ReadRecordDailyHelper
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.ParagraphRuleProcessor
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.isSameNameAuthor
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.globalExecutor
import io.legado.app.model.localBook.TextFile
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.CacheBookService
import io.legado.app.ui.about.ReadRecordWidgetStore
import io.legado.app.ui.book.read.config.HighlightRule
import io.legado.app.ui.book.read.config.HighlightRuleStore
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.utils.postEvent
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min


@Suppress("MemberVisibilityCanBePrivate")
object ReadBook : CoroutineScope by MainScope() {
    var book: Book? = null
    var callBack: WeakReference<CallBack>? = null
    var inBookshelf = false
    var chapterSize = 0
    var simulatedChapterSize = 0
    var durChapterIndex = 0
    var durChapterPos = 0
    var isLocalBook = true
    var chapterChanged = false
    var prevTextChapter: TextChapter? = null
    var curTextChapter: TextChapter? = null
    var nextTextChapter: TextChapter? = null
    var bookSource: BookSource? = null
    var msg: String? = null
    private val loadingChapters = arrayListOf<Int>()
    private val readRecord = ReadRecord()
    private val chapterLoadingJobs = ConcurrentHashMap<Int, Coroutine<*>>()
    private val paragraphRuleProcessingSemaphore = Semaphore(1)
    private val chapterLoadingLayoutKeys = ConcurrentHashMap<Int, String>()
    private val chapterLoadGenerations = ConcurrentHashMap<Int, Long>()
    private val chapterLoadGenerationSeed = AtomicLong(0L)
    private val chapterLayoutKeys = ConcurrentHashMap<Int, String>()
    @Volatile
    private var paragraphRuleLayoutKey: String = ""
    @Volatile
    private var paragraphRuleRefreshJob: Job? = null
    private val prevChapterLoadingLock = Mutex()
    private val curChapterLoadingLock = Mutex()
    private val nextChapterLoadingLock = Mutex()
    var readStartTime: Long = System.currentTimeMillis()
    private const val READ_ALOUD_USER_NAVIGATION_LOCK_MS = 1500L
    @Volatile
    private var readAloudUserNavigationUntil = 0L
    @Volatile
    private var readAloudPendingLoadChapterIndex = -1

    /* 跳转进度前进度记录 */
    var lastBookProgress: BookProgress? = null

    /* web端阅读进度记录 */
    var webBookProgress: BookProgress? = null

    var preDownloadTask: Job? = null
    val downloadedChapters = hashSetOf<Int>()
    val downloadFailChapters = hashMapOf<Int, Int>()
    var contentProcessor: ContentProcessor? = null
    val downloadScope = CoroutineScope(SupervisorJob() + IO)
    val preDownloadSemaphore = Semaphore(2)
    val executor = globalExecutor

    @Volatile
    var highlights: List<BookHighlight> = emptyList()
        private set

    @Volatile
    var highlightRules: List<HighlightRule> = emptyList()
        private set

    @Volatile
    var highlightRulesVersion = 0
        private set

    private fun markReadAloudUserNavigation(fromReadAloud: Boolean) {
        if (!fromReadAloud && BaseReadAloudService.isRun) {
            readAloudUserNavigationUntil =
                System.currentTimeMillis() + READ_ALOUD_USER_NAVIGATION_LOCK_MS
            // C1 手动翻页挂钩（OQ-7 收口：挂 UI 导航唯一汇合点 markReadAloudUserNavigation，
            // 引擎驱动路径 fromReadAloud=true 天然排除）
            callBack?.get()?.onManualPageChanged()
        }
    }

    fun isReadAloudUserNavigationActive(): Boolean {
        return BaseReadAloudService.isRun &&
                System.currentTimeMillis() < readAloudUserNavigationUntil
    }

    /**
     * C1 原语 B 配套闸门（LC :2120 同名同义）：原语 B/原语 A 跨章打开章节期间置 true，
     * 防止手动翻页挂钩/朗读重启链把"对齐动作"误判为用户翻页。
     */
    @Volatile
    var skipReadAloudSyncOnce = false

    /**
     * C1 引擎侧独立加载正文入口（LC :575-611 对齐）：三章缓存优先，未命中走既有异步装载链。
     * 只填充相邻章缓存槽，不触碰显示状态。
     * 简化说明:LC 为排版 channel await，本项目复用既有 loadContent 装载链 + 轮询等待（200ms/30s 上限）
     * | 已知上限:目标章仅支持相邻章（switchReadAloudChapterKeepingView 只 ±1）+ 加载失败超时返回 null
     * | 升级路径:P3 rebase 时若需任意章 seek，改为 getTextChapterAsync 直挂 channel await
     */
    suspend fun loadTextChapterForReadAloud(index: Int, scope: CoroutineScope): TextChapter? {
        when (index) {
            durChapterIndex -> curTextChapter?.takeIf { it.isCompleted }?.let { return it }
            durChapterIndex - 1 -> prevTextChapter?.takeIf { it.isCompleted }?.let { return it }
            durChapterIndex + 1 -> nextTextChapter?.takeIf { it.isCompleted }?.let { return it }
        }
        if (index !in durChapterIndex - 1..durChapterIndex + 1) return null
        withContext(IO) {
            loadContent(index, upContent = false, resetPageOffset = false)
        }
        val deadline = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            val chapter = when (index) {
                durChapterIndex -> curTextChapter
                durChapterIndex - 1 -> prevTextChapter
                durChapterIndex + 1 -> nextTextChapter
                else -> null
            }
            if (chapter?.isCompleted == true) return chapter
            delay(200)
        }
        AppLog.putDebugWithTag(
            AppLog.TAG_READ_ALOUD, "loadTextChapterForReadAloud 超时:$index", level = AppLog.Level.WARN
        )
        return null
    }

    fun markRecentRead(book: Book, readTime: Long = System.currentTimeMillis()) {
        executor.execute {
            kotlin.runCatching {
                book.durChapterTime = readTime
                book.update()
                appDb.readRecentBookDao.insert(ReadRecentBook(book.bookUrl, readTime))
                ReadRecordWidgetStore.updateRecentSnapshot(book, readTime)
            }.onFailure {
                AppLog.put("更新最近在读出错\n$it", it)
            }
        }
    }

    fun resetData(book: Book) {
        stopReadAloudForBookSwitch(book)
        releaseAndCancel()
        ReadBook.book = book
        refreshParagraphRuleLayoutKey()
        loadHighlightRules(book)
        readRecord.bookName = book.name
        readRecord.readTime = appDb.readRecordDao.getReadTime(book.name) ?: 0
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        contentProcessor = ContentProcessor.get(book)
        durChapterIndex = book.durChapterIndex
        durChapterPos = book.durChapterPos
        isLocalBook = book.isLocal
        clearTextChapter()
        callBack?.get()?.upContent()
        callBack?.get()?.upMenuView()
        callBack?.get()?.upPageAnim()
        upWebBook(book)
        lastBookProgress = null
        webBookProgress = null
        TextFile.clear()
        synchronized(this) {
            loadingChapters.clear()
            chapterLoadingLayoutKeys.clear()
            chapterLoadGenerations.clear()
            chapterLayoutKeys.clear()
            downloadedChapters.clear()
            downloadFailChapters.clear()
        }
    }

    fun upData(book: Book) {
        stopReadAloudForBookSwitch(book)
        releaseAndCancel()
        ReadBook.book = book
        refreshParagraphRuleLayoutKey()
        loadHighlightRules(book)
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        if (durChapterIndex != book.durChapterIndex) {
            durChapterIndex = book.durChapterIndex
            durChapterPos = book.durChapterPos
            clearTextChapter()
        }
        if (curTextChapter?.isCompleted == false) {
            curTextChapter = null
        }
        if (nextTextChapter?.isCompleted == false) {
            nextTextChapter = null
        }
        if (prevTextChapter?.isCompleted == false) {
            prevTextChapter = null
        }
        callBack?.get()?.upMenuView()
        upWebBook(book)
        synchronized(this) {
            loadingChapters.clear()
            chapterLoadingLayoutKeys.clear()
            chapterLoadGenerations.clear()
            chapterLayoutKeys.clear()
            downloadedChapters.clear()
            downloadFailChapters.clear()
        }
    }

    private fun stopReadAloudForBookSwitch(newBook: Book) {
        val oldBook = ReadBook.book ?: return
        if (oldBook.bookUrl == newBook.bookUrl || !BaseReadAloudService.isRun) return
        saveRead()
        ReadAloud.stopForBookSwitch(appCtx)
        postEvent(
            EventBus.READ_ALOUD_PLAYBACK_STATE,
            io.legado.app.help.readaloud.ReadAloudPlaybackState(
                phase = io.legado.app.help.readaloud.ReadAloudPlaybackState.PHASE_STOPPED,
                bookUrl = oldBook.bookUrl,
                chapterIndex = durChapterIndex,
                serviceRunning = false
            )
        )
        postEvent(EventBus.ALOUD_STATE, io.legado.app.constant.Status.STOP)
    }

    fun upWebBook(book: Book) {
        if (book.isLocal) {
            bookSource = null
            if (book.getImageStyle().isNullOrBlank() && (book.isImage || book.isPdf)) {
                book.setImageStyle(Book.imgStyleFull)
            }
        } else {
            appDb.bookSourceDao.getBookSource(book.origin)?.let {
                bookSource = it
                if (book.getImageStyle().isNullOrBlank()) {
                    var imageStyle = it.getContentRule().imageStyle
                    if (imageStyle.isNullOrBlank() && (book.isImage || book.isPdf)) {
                        imageStyle = Book.imgStyleFull
                    }
                    book.setImageStyle(imageStyle)
                    if (imageStyle.equals(Book.imgStyleSingle, true)) {
                        book.setPageAnim(0)
                    }
                }
            } ?: let {
                bookSource = null
            }
        }
    }

    fun upReadBookConfig(book: Book) {
        val oldIndex = ReadBookConfig.styleSelect
        ReadBookConfig.isComic = book.isImage
        if (oldIndex != ReadBookConfig.styleSelect) {
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
            if (AppConfig.readBarStyleFollowPage) {
                postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
            }
        }
    }

    fun setProgress(progress: BookProgress) {
        if (progress.durChapterIndex < chapterSize &&
            (durChapterIndex != progress.durChapterIndex
                    || durChapterPos != progress.durChapterPos)
        ) {
            durChapterIndex = progress.durChapterIndex
            durChapterPos = progress.durChapterPos
            saveRead()
            clearTextChapter()
            callBack?.get()?.upContent()
            loadContent(resetPageOffset = true)
        }
    }

    //暂时保存跳转前进度
    fun saveCurrentBookProgress() {
        if (lastBookProgress != null) return //避免进度条连续跳转不能覆盖最初的进度记录
        lastBookProgress = book?.let { BookProgress(it) }
    }

    //恢复跳转前进度
    fun restoreLastBookProgress() {
        lastBookProgress?.let {
            setProgress(it)
            lastBookProgress = null
        }
    }

    fun clearLastBookProgress() {
        lastBookProgress = null
    }

    fun clearTextChapter() {
        clearExpiredChapterLoadingJob(true)
        prevTextChapter = null
        curTextChapter = null
        nextTextChapter = null
        chapterLayoutKeys.clear()
    }

    fun reloadCurrentContent(reason: String = "", keepPosition: Boolean = true) {
        val chapterIndex = durChapterIndex
        if (chapterIndex !in 0 until chapterSize) return
        AppLog.putDebug("reloadCurrentContent: reason=$reason, chapter=$chapterIndex")
        refreshParagraphRuleLayoutKey()
        cancelChapterLoading(chapterIndex)
        chapterLayoutKeys.remove(chapterIndex)
        loadContent(
            chapterIndex,
            upContent = true,
            resetPageOffset = !keepPosition,
            forceReload = true
        )
        loadContent(chapterIndex + 1, upContent = false, resetPageOffset = false, forceReload = true)
        loadContent(chapterIndex - 1, upContent = false, resetPageOffset = false, forceReload = true)
    }

    fun relayoutCurrentContent(reason: String = "", keepPosition: Boolean = true) {
        AppLog.putDebug("relayoutCurrentContent: reason=$reason, chapter=$durChapterIndex")
        ChapterProvider.upStyle()
        reloadCurrentContent(reason, keepPosition)
    }

    fun invalidateParagraphRuleLayout() {
        ParagraphRuleProcessor.clearProcessCache(book?.bookUrl)
        refreshParagraphRuleLayoutKey()
        clearTextChapter()
    }

    fun refreshCurrentParagraphRuleResult(): Boolean {
        val currentBook = book ?: return false
        if (currentBook.isEpub) return false
        val chapterIndex = durChapterIndex
        if (chapterIndex !in 0 until chapterSize) return false
        if (paragraphRuleRefreshJob?.isActive == true) return true
        paragraphRuleRefreshJob = launch(Main) {
            delay(80)
            val activeBook = book ?: return@launch
            if (activeBook.bookUrl != currentBook.bookUrl || durChapterIndex != chapterIndex) {
                return@launch
            }
            ParagraphRuleProcessor.clearProcessCache(activeBook.bookUrl, chapterIndex)
            refreshParagraphRuleLayoutKey()
            cancelChapterLoading(chapterIndex)
            curTextChapter?.takeIf { it.chapter.index == chapterIndex }?.cancelLayout()
            curTextChapter = null
            chapterLayoutKeys.remove(chapterIndex)
            callBack?.get()?.upContent(resetPageOffset = false)
            loadContent(chapterIndex, upContent = true, resetPageOffset = false)
        }
        return true
    }

    fun loadHighlights(book: Book) {
        highlights = appDb.bookHighlightDao.getByBook(book.name, book.author)
    }

    fun highlightsOfChapter(chapterIndex: Int): List<BookHighlight> {
        return highlights.filter { it.chapterIndex == chapterIndex }
    }

    /**
     * 加载高亮规则: 当前项目用 SharedPreferences 存储, 非 Room
     * 简化说明: 不按 book.name/origin 过滤, 全量加载已启用规则 | 已知上限: 不支持按书过滤规则 | 升级路径: 后续迁移到 Room 后支持
     */
    fun loadHighlightRules(book: Book) {
        highlightRules = HighlightRuleStore.loadEnabled(appCtx)
        highlightRulesVersion++
    }

    /** 规则集变化后: 重载本书规则 + 升版本(令各 TextChapter 缓存失效) + 重绘当前页 */
    fun upHighlightRules() {
        book?.let { loadHighlightRules(it) }
        callBack?.get()?.upContent(resetPageOffset = false)
    }

    /** 本章规则命中(整章匹配, 缓存在 TextChapter 上, 随重排/规则版本失效) */
    fun ruleMatchesOfChapter(
        textChapter: TextChapter
    ): List<HighlightRuleMatcher.RuleMatch> {
        // Issue-3 修复：创建本地不可变副本，避免迭代时被其他线程修改触发 ConcurrentModificationException
        // 根因：@Volatile 只保证引用可见性，不保证 ArrayList 内部数据线程安全
        // 加强修复：highlightRules + textChapter.pages 都做 snapshot
        val rulesSnapshot = highlightRules.toList()
        if (rulesSnapshot.isEmpty()) return emptyList()
        val versionSnapshot = highlightRulesVersion
        if (textChapter.highlightRuleMatchesVersion == versionSnapshot) {
            return textChapter.highlightRuleMatches ?: emptyList()
        }
        val pagesSnapshot = textChapter.pages.toList()
        val lines = pagesSnapshot.flatMap { it.lines }.map { line ->
            HighlightTextBuilder.LineInput(
                line.columns.map { col -> (col as? TextColumn)?.charData ?: "" },
                line.charSize,
                line.isParagraphEnd
            )
        }
        val text = HighlightTextBuilder.build(lines)
        val rules = rulesSnapshot.map {
            HighlightRuleMatcher.Rule(
                id = it.id,
                pattern = it.pattern,
                isRegex = it.isRegex,
                style = it.toHighlightStyle(),
                timeoutMs = it.timeoutMillisecond,
                replacement = it.replacement,
                isDotAll = it.isDotAll
            )
        }
        val startMs = System.currentTimeMillis()
        val matches = HighlightRuleMatcher.matchWithTemplate(text, rules)
        runCatching {
            AppLog.putDebugWithTag(
                AppLog.TAG_HIGHLIGHT_STYLE,
                "匹配完成 规则${rules.size} 命中${matches.size} 耗时${System.currentTimeMillis() - startMs}ms",
                level = AppLog.Level.INFO
            )
        }
        if (textChapter.isCompleted) {
            textChapter.highlightRuleMatches = matches
            textChapter.highlightRuleMatchesVersion = versionSnapshot
        }
        return matches
    }

    fun addHighlight(highlight: BookHighlight) {
        appDb.bookHighlightDao.insert(highlight)
        highlights = highlights + highlight
        callBack?.get()?.upContent(resetPageOffset = false)
    }

    fun updateHighlight(highlight: BookHighlight) {
        appDb.bookHighlightDao.update(highlight)
        highlights = highlights.map { if (it.time == highlight.time) highlight else it }
        callBack?.get()?.upContent(resetPageOffset = false)
    }

    fun removeHighlight(highlight: BookHighlight) {
        appDb.bookHighlightDao.delete(highlight)
        highlights = highlights.filter { it.time != highlight.time }
        callBack?.get()?.upContent(resetPageOffset = false)
    }

    fun clearSearchResult() {
        curTextChapter?.clearSearchResult()
        prevTextChapter?.clearSearchResult()
        nextTextChapter?.clearSearchResult()
    }

    fun uploadProgress(toast: Boolean = false, successAction: (() -> Unit)? = null) {
        book?.let {
            launch(IO) {
                AppWebDav.uploadBookProgress(it, toast) {
                    successAction?.invoke()
                }
                ensureActive()
                it.update()
            }
        }
    }

    /**
     * 同步阅读进度
     * 如果当前进度快于服务器进度或者没有进度进行上传，如果慢与服务器进度则执行传入动作
     */
    fun syncProgress(
        newProgressAction: ((progress: BookProgress) -> Unit)? = null,
        uploadSuccessAction: (() -> Unit)? = null,
        syncSuccessAction: (() -> Unit)? = null
    ) {
        if (!AppConfig.syncBookProgress) return
        val book = book ?: return
        Coroutine.async {
            AppWebDav.getBookProgress(book)
        }.onError {
            AppLog.put("拉取阅读进度失败", it)
        }.onSuccess { progress ->
            if (progress == null || progress.durChapterIndex < book.durChapterIndex ||
                (progress.durChapterIndex == book.durChapterIndex
                        && progress.durChapterPos < book.durChapterPos)
            ) {
                // 服务器没有进度或者进度比服务器快，上传现有进度
                Coroutine.async {
                    AppWebDav.uploadBookProgress(BookProgress(book), uploadSuccessAction)
                    book.update()
                }
            } else if (progress.durChapterIndex > book.durChapterIndex ||
                progress.durChapterPos > book.durChapterPos
            ) {
                // 进度比服务器慢，执行传入动作
                newProgressAction?.invoke(progress)
            } else {
                syncSuccessAction?.invoke()
            }
        }
    }

    fun upReadTime(forceWidgetUpdate: Boolean = false) {
        if (!AppConfig.enableReadRecord) {
            return
        }
        executor.execute {
            val now = System.currentTimeMillis()
            val delta = now - readStartTime
            readRecord.readTime += delta
            readStartTime = now
            readRecord.lastRead = now
            appDb.readRecordDao.insert(readRecord)
            ReadRecordDailyHelper.record(delta, now, forceWidgetUpdate)
        }
    }

    fun upMsg(msg: String?) {
        if (ReadBook.msg != msg) {
            ReadBook.msg = msg
            callBack?.get()?.upContent()
        }
    }

    fun moveToNextPage(fromReadAloud: Boolean = false): Boolean {
        markReadAloudUserNavigation(fromReadAloud)
        var hasNextPage = false
        curTextChapter?.let {
            val nextPagePos = it.getNextPageLength(durChapterPos)
            if (nextPagePos >= 0) {
                hasNextPage = true
                // C1：页级高亮存储态已投影化（H6 删除），红字由 upContent 重绘时现算
                durChapterPos = nextPagePos
                callBack?.get()?.cancelSelect()
                callBack?.get()?.upContent()
                saveRead(true)
            }
        }
        return hasNextPage
    }

    fun moveToPrevPage(fromReadAloud: Boolean = false): Boolean {
        markReadAloudUserNavigation(fromReadAloud)
        var hasPrevPage = false
        curTextChapter?.let {
            val prevPagePos = it.getPrevPageLength(durChapterPos)
            if (prevPagePos >= 0) {
                hasPrevPage = true
                durChapterPos = prevPagePos
                callBack?.get()?.upContent()
                saveRead(true)
            }
        }
        return hasPrevPage
    }

    fun moveToNextChapter(
        upContent: Boolean,
        upContentInPlace: Boolean = true,
        fromReadAloud: Boolean = false
    ): Boolean {
        if (durChapterIndex < simulatedChapterSize - 1) {
            markReadAloudUserNavigation(fromReadAloud)
            val targetIndex = durChapterIndex + 1
            val loadedNextChapter = nextTextChapter?.takeIf { it.isCompleted }
            if (loadedNextChapter == null) cancelChapterLoading(targetIndex)
            durChapterPos = 0
            durChapterIndex++
            clearExpiredChapterLoadingJob()
            prevTextChapter = curTextChapter
            curTextChapter = loadedNextChapter
            nextTextChapter = null
            if (curTextChapter == null) {
                AppLog.putDebug("moveToNextChapter-章节未加载,开始加载")
                if (fromReadAloud) {
                    readAloudPendingLoadChapterIndex = durChapterIndex
                }
                if (upContentInPlace) callBack?.get()?.upContent()
                loadContent(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                AppLog.putDebug("moveToNextChapter-章节已加载,刷新视图")
                callBack?.get()?.upContent()
            }
            loadContent(durChapterIndex.plus(1), upContent, false)
            saveRead()
            callBack?.get()?.upMenuView()
            AppLog.putDebug("moveToNextChapter-curPageChanged()")
            curPageChanged(fromReadAloud = fromReadAloud)
            return true
        } else {
            AppLog.putDebug("跳转下一章失败,没有下一章")
            return false
        }
    }

    suspend fun moveToNextChapterAwait(
        upContent: Boolean,
        upContentInPlace: Boolean = true,
        fromReadAloud: Boolean = false
    ): Boolean {
        if (durChapterIndex < simulatedChapterSize - 1) {
            markReadAloudUserNavigation(fromReadAloud)
            val targetIndex = durChapterIndex + 1
            val loadedNextChapter = nextTextChapter?.takeIf { it.isCompleted }
            if (loadedNextChapter == null) cancelChapterLoading(targetIndex)
            durChapterPos = 0
            durChapterIndex++
            clearExpiredChapterLoadingJob()
            prevTextChapter = curTextChapter
            curTextChapter = loadedNextChapter
            nextTextChapter = null
            if (curTextChapter == null) {
                AppLog.putDebug("moveToNextChapter-章节未加载,开始加载")
                if (fromReadAloud) {
                    readAloudPendingLoadChapterIndex = durChapterIndex
                }
                if (upContentInPlace) callBack?.get()?.upContentAwait()
                loadContentAwait(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                AppLog.putDebug("moveToNextChapter-章节已加载,刷新视图")
                callBack?.get()?.upContentAwait()
            }
            loadContent(durChapterIndex.plus(1), upContent, false)
            saveRead()
            callBack?.get()?.upMenuView()
            AppLog.putDebug("moveToNextChapter-curPageChanged()")
            curPageChanged(fromReadAloud = fromReadAloud)
            return true
        } else {
            AppLog.putDebug("跳转下一章失败,没有下一章")
            return false
        }
    }

    fun moveToPrevChapter(
        upContent: Boolean,
        toLast: Boolean = true,
        upContentInPlace: Boolean = true,
        fromReadAloud: Boolean = false
    ): Boolean {
        if (durChapterIndex > 0) {
            markReadAloudUserNavigation(fromReadAloud)
            val targetIndex = durChapterIndex - 1
            val loadedPrevChapter = prevTextChapter?.takeIf { it.isCompleted }
            if (loadedPrevChapter == null) cancelChapterLoading(targetIndex)
            durChapterPos = if (toLast) loadedPrevChapter?.lastReadLength ?: Int.MAX_VALUE else 0
            durChapterIndex--
            clearExpiredChapterLoadingJob()
            nextTextChapter = curTextChapter
            curTextChapter = loadedPrevChapter
            prevTextChapter = null
            if (curTextChapter == null) {
                if (fromReadAloud) {
                    readAloudPendingLoadChapterIndex = durChapterIndex
                }
                if (upContentInPlace) callBack?.get()?.upContent()
                loadContent(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                callBack?.get()?.upContent()
            }
            loadContent(durChapterIndex.minus(1), upContent, false)
            saveRead()
            callBack?.get()?.upMenuView()
            curPageChanged(fromReadAloud = fromReadAloud)
            return true
        } else {
            return false
        }
    }

    fun skipToPage(index: Int, fromReadAloud: Boolean = false, success: (() -> Unit)? = null) {
        markReadAloudUserNavigation(fromReadAloud)
        durChapterPos = curTextChapter?.getReadLength(index) ?: index
        callBack?.get()?.upContent {
            success?.invoke()
        }
        curPageChanged(fromReadAloud = fromReadAloud)
        saveRead(true)
    }

    fun setPageIndex(index: Int, fromReadAloud: Boolean = false) {
        markReadAloudUserNavigation(fromReadAloud)
        recycleRecorders(durPageIndex, index)
        durChapterPos = curTextChapter?.getReadLength(index) ?: index
        saveRead(true)
        curPageChanged(pageChanged = true, fromReadAloud = fromReadAloud)
    }

    fun recycleRecorders(beforeIndex: Int, afterIndex: Int) {
        if (!AppConfig.optimizeRender) {
            return
        }
        executor.execute {
            val textChapter = curTextChapter ?: return@execute
            if (afterIndex > beforeIndex) {
                textChapter.getPage(afterIndex - 2)?.recycleRecorders()
            }
            if (afterIndex < beforeIndex) {
                textChapter.getPage(afterIndex + 3)?.recycleRecorders()
            }
        }
    }

    fun openChapter(
        index: Int,
        durChapterPos: Int = 0,
        upContent: Boolean = true,
        success: (() -> Unit)? = null
    ) {
        if (index < chapterSize) {
            clearTextChapter()
            if (upContent) callBack?.get()?.upContent()
            durChapterIndex = index
            ReadBook.durChapterPos = durChapterPos
            saveRead()
            loadContent(resetPageOffset = true) {
                success?.invoke()
            }
        }
    }

    /**
     * 当前页面变化
     */
    private fun curPageChanged(pageChanged: Boolean = false, fromReadAloud: Boolean = false) {
        callBack?.get()?.pageChanged()
        curTextChapter?.let {
            if (fromReadAloud && BaseReadAloudService.isRun && it.isCompleted) {
                val scrollPageAnim = pageAnim() == 3
                if (scrollPageAnim && pageChanged) {
                    ReadAloud.pause(appCtx)
                } else {
                    readAloud(!BaseReadAloudService.pause)
                }
            }
        }
        upReadTime()
        preDownload()
    }

    /**
     * 朗读
     * C1 原语 A 通道：setAloudStart 需指定朗读页（可与显示页不同），故增加 pageIndex 参数。
     */
    fun readAloud(play: Boolean = true, startPos: Int = 0, pageIndex: Int = durPageIndex) {
        book ?: return
        val textChapter = curTextChapter ?: return
        if (textChapter.isCompleted) {
            ReadAloud.play(appCtx, play, pageIndex = pageIndex, startPos = startPos)
        }
    }

    /**
     * 当前页数
     */
    val durPageIndex: Int
        get() {
            return curTextChapter?.getPageIndexByCharIndex(durChapterPos) ?: durChapterPos
        }

    /**
     * 是否排版到了当前阅读位置
     */
    val isLayoutAvailable inline get() = durPageIndex >= 0

    val isScroll inline get() = pageAnim() == scrollPageAnim

    val contentLoadFinish get() = curTextChapter != null || msg != null

    /**
     * chapterOnDur: 0为当前页,1为下一页,-1为上一页
     */
    fun textChapter(chapterOnDur: Int = 0): TextChapter? {
        return when (chapterOnDur) {
            0 -> curTextChapter
            1 -> nextTextChapter
            -1 -> prevTextChapter
            else -> null
        }
    }

    /**
     * 加载当前章节和前后一章内容
     * @param resetPageOffset 滚动阅读是否重置滚动位置
     * @param success 当前章节加载完成回调
     */
    fun loadContent(
        resetPageOffset: Boolean,
        success: (() -> Unit)? = null
    ) {
        val isEpub = book?.isEpub == true
        loadContent(durChapterIndex, resetPageOffset = resetPageOffset) {
            success?.invoke()
            if (isEpub) {
                loadContent(durChapterIndex + 1, upContent = false, resetPageOffset = false)
                loadContent(durChapterIndex - 1, upContent = false, resetPageOffset = false)
            }
        }
        if (!isEpub) {
            loadContent(durChapterIndex + 1, resetPageOffset = resetPageOffset)
            loadContent(durChapterIndex - 1, resetPageOffset = resetPageOffset)
        }
    }

    fun loadOrUpContent(success: (() -> Unit)? = null) {
        if (curTextChapter == null) {
            loadContent(durChapterIndex) {
                success?.invoke()
            }
        } else {
            callBack?.get()?.upContent()
        }
        if (nextTextChapter == null) {
            loadContent(durChapterIndex + 1)
        }
        if (prevTextChapter == null) {
            loadContent(durChapterIndex - 1)
        }
    }

    private fun currentChapterLayoutKey(): String {
        val paint = ChapterProvider.contentPaint
        val titlePaint = ChapterProvider.titlePaint
        val currentBook = book
        val processor = contentProcessor
        val replaceRuleKey = processor?.getContentReplaceRules()
            ?.joinToString(",") {
                "${it.id}:${it.pattern.hashCode()}:${it.replacement.hashCode()}:${it.isRegex}:${it.timeoutMillisecond}"
            }.orEmpty()
        return buildString {
            append(ChapterProvider.viewWidth).append('x').append(ChapterProvider.viewHeight)
            append('|').append(ChapterProvider.visibleWidth).append('x').append(ChapterProvider.visibleHeight)
            append('|').append(paint.textSize).append('|').append(paint.color)
            append('|').append(paint.typeface?.style ?: 0)
            append('|').append(titlePaint.textSize).append('|').append(titlePaint.color)
            append('|').append(titlePaint.typeface?.style ?: 0)
            append('|').append(ChapterProvider.contentPaintTextHeight)
            append('|').append(ChapterProvider.titlePaintTextHeight)
            append('|').append(ChapterProvider.lineSpacingExtra)
            append('|').append(ChapterProvider.paragraphSpacing)
            append('|').append(ReadBookConfig.paragraphIndent)
            append('|').append(ReadBookConfig.useZhLayout)
            append('|').append(ReadBookConfig.textFullJustify)
            append('|').append(AppConfig.adaptSpecialStyle)
            if (currentBook != null) {
                append('|').append(currentBook.getUseReplaceRule())
                append('|').append(currentBook.config.delTag)
                append('|').append(currentBook.getReSegment())
                append('|').append(currentBook.getImageStyle())
            }
            append('|').append(replaceRuleKey)
            append('|').append(paragraphRuleLayoutKey)
        }
    }

    private fun refreshParagraphRuleLayoutKey() {
        val currentBook = book
        paragraphRuleLayoutKey = if (currentBook == null || currentBook.isEpub) {
            ""
        } else {
            appDb.paragraphRuleDao.enabledRulesForBook(currentBook.bookUrl)
                .joinToString("|") { ParagraphRuleProcessor.stableKey(it) }
        }
    }

    private fun showCurrentChapterLoadError(index: Int, message: String, throwable: Throwable? = null) {
        if (index != durChapterIndex) return
        val msg = "加载正文失败\n$message"
        if (throwable != null) {
            AppLog.put(msg, throwable)
        } else {
            AppLog.put(msg)
        }
        launch(Main) {
            upMsg(msg)
            callBack?.get()?.contentLoadFinish()
        }
    }

    /**
     * 加载章节内容
     * @param index 章节序号
     * @param upContent 是否更新视图
     * @param resetPageOffset 滚动阅读是否重置滚动位置
     * @param success 加载完成回调
     */
    fun loadContent(
        index: Int,
        upContent: Boolean = true,
        resetPageOffset: Boolean = false,
        forceReload: Boolean = false,
        success: (() -> Unit)? = null
    ) {
        var requestGeneration: Long? = null
        if (forceReload) {
            refreshParagraphRuleLayoutKey()
            cancelChapterLoading(index)
            chapterLayoutKeys.remove(index)
        }
        val layoutKey = currentChapterLayoutKey()
        val cached = textChapter(index - durChapterIndex)
        if (!forceReload && cached?.isCompleted == true && chapterLayoutKeys[index] == layoutKey) {
            if (upContent) {
                callBack?.get()?.upContent(index - durChapterIndex, resetPageOffset)
            }
            success?.invoke()
            return
        }
        Coroutine.async {
            val book = book
            if (book == null) {
                logContentLoadSkip("book_null", index)
                showCurrentChapterLoadError(index, "书籍数据为空")
                return@async
            }
            refreshParagraphRuleLayoutKey()
            val requestedLayoutKey = currentChapterLayoutKey()
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index)
            if (chapter == null) {
                logContentLoadSkip("chapter_null", index, book)
                showCurrentChapterLoadError(index, "未找到当前章节，可能目录未加载成功")
                return@async
            }
            val generation = beginChapterLoad(index, requestedLayoutKey)
            if (generation != null) {
                requestGeneration = generation
                BookHelp.getContent(book, chapter)?.let {
                    contentLoadFinish(
                        book,
                        chapter,
                        it,
                        upContent,
                        resetPageOffset,
                        requestGeneration = generation,
                        success = success
                    )
                } ?: download(
                    downloadScope,
                    book,
                    chapter,
                    resetPageOffset,
                    requestGeneration = generation,
                    upContent = upContent,
                    success = success
                )
            } else {
                logContentLoadSkip("already_loading", index, book)
            }
        }.onError {
            requestGeneration?.let { generation -> finishChapterLoad(index, generation) }
            val message = it.localizedMessage ?: it.toString()
            showCurrentChapterLoadError(index, message, it)
            if (index != durChapterIndex) {
                AppLog.put("Load content error\n$message", it)
            }
        }
    }

    suspend fun loadContentAwait(
        index: Int,
        upContent: Boolean = true,
        resetPageOffset: Boolean = false,
        success: (() -> Unit)? = null
    ) = withContext(IO) {
        val requestedLayoutKey = currentChapterLayoutKey()
        val initialGeneration = beginChapterLoad(index, requestedLayoutKey)
        if (initialGeneration != null) {
            try {
                val book = book
                if (book == null) {
                    logContentLoadSkip("book_null_await", index)
                    showCurrentChapterLoadError(index, "Book is null")
                    return@withContext
                }
                refreshParagraphRuleLayoutKey()
                val refreshedLayoutKey = currentChapterLayoutKey()
                val generation = if (refreshedLayoutKey == requestedLayoutKey) {
                    initialGeneration
                } else {
                    updateChapterLoadLayoutKey(index, initialGeneration, refreshedLayoutKey)
                        ?: return@withContext
                }
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index)
                if (chapter == null) {
                    logContentLoadSkip("chapter_null_await", index, book)
                    showCurrentChapterLoadError(index, "Current chapter not found")
                    return@withContext
                }
                val content = BookHelp.getContent(book, chapter) ?: downloadAwait(book, chapter)
                val applied = contentLoadFinishAwait(
                    book,
                    chapter,
                    content,
                    upContent,
                    resetPageOffset,
                    requestGeneration = generation
                )
                if (applied) success?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.localizedMessage ?: e.toString()
                showCurrentChapterLoadError(index, message, e)
                if (index != durChapterIndex) {
                    AppLog.put("Load content error\n$message", e)
                }
            } finally {
                finishChapterLoad(index, initialGeneration)
            }
        } else {
            logContentLoadSkip("already_loading_await", index)
        }
    }

    /**
     * 下载正文
     */
    private suspend fun downloadIndex(index: Int) {
        if (index < 0) return
        if (index > chapterSize - 1) {
            upToc()
            return
        }
        val book = book ?: return
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index) ?: return
        if (BookHelp.hasContent(book, chapter)) {
            downloadedChapters.add(chapter.index)
        } else {
            delay(1000)
            if (this@ReadBook.book?.bookUrl != book.bookUrl || chapter.bookUrl != book.bookUrl) return
            val generation = beginChapterLoad(index, currentChapterLayoutKey())
            if (generation != null) {
                download(
                    downloadScope,
                    book,
                    chapter,
                    false,
                    preDownloadSemaphore,
                    requestGeneration = generation,
                    upContent = false
                )
            }
        }
    }

    /**
     * 下载正文
     */
    private fun download(
        scope: CoroutineScope,
        expectedBook: Book,
        chapter: BookChapter,
        resetPageOffset: Boolean,
        semaphore: Semaphore? = null,
        requestGeneration: Long? = null,
        upContent: Boolean = true,
        success: (() -> Unit)? = null
    ) {
        val book = expectedBook
        if (this.book?.bookUrl != book.bookUrl || chapter.bookUrl != book.bookUrl) {
            logContentLoadSkip("book_changed_download", chapter.index, book)
            requestGeneration?.let { finishChapterLoad(chapter.index, it) }
            return
        }
        val bookSource = bookSource?.takeIf { it.bookSourceUrl == book.origin }
        if (bookSource != null) {
            CacheBook.getOrCreate(bookSource, book).download(
                scope,
                chapter,
                semaphore,
                resetPageOffset,
                requestGeneration,
                upContent,
                success
            )
        } else {
            logContentLoadSkip("book_source_null", chapter.index, book)
            val msg = if (book.isLocal) "无内容" else "没有书源"
            contentLoadFinish(
                book,
                chapter,
                "加载正文失败\n$msg",
                upContent = upContent,
                resetPageOffset = resetPageOffset,
                requestGeneration = requestGeneration,
                success = success
            )
        }
    }

    private suspend fun downloadAwait(expectedBook: Book, chapter: BookChapter): String {
        val book = expectedBook
        if (this.book?.bookUrl != book.bookUrl || chapter.bookUrl != book.bookUrl) {
            logContentLoadSkip("book_changed_download_await", chapter.index, book)
            return "Load content canceled\nbook changed"
        }
        val bookSource = bookSource?.takeIf { it.bookSourceUrl == book.origin }
        if (bookSource != null) {
            return CacheBook.getOrCreate(bookSource, book).downloadAwait(chapter)
        } else {
            logContentLoadSkip("book_source_null_await", chapter.index, book)
            val msg = if (book.isLocal) "无内容" else "没有书源"
            return "加载正文失败\n$msg"
        }
    }

    private fun logContentLoadSkip(reason: String, index: Int, activeBook: Book? = book) {
        val dbChapterCount = activeBook?.let {
            runCatching { appDb.bookChapterDao.getChapterCount(it.bookUrl) }.getOrNull()
        }
        AppLog.put(
            "read-load skip: reason=$reason, index=$index, " +
                "durChapterIndex=$durChapterIndex, chapterSize=$chapterSize, " +
                "loading=${loadingChapters.contains(index)}, dbChapterCount=$dbChapterCount, " +
                "bookUrl=${activeBook?.bookUrl}, origin=${activeBook?.origin}, " +
                "bookSource=${bookSource?.bookSourceUrl}"
        )
    }

    @Synchronized
    private fun cancelChapterLoading(index: Int, expectedGeneration: Long? = null) {
        if (expectedGeneration != null && chapterLoadGenerations[index] != expectedGeneration) return
        chapterLoadGenerations.remove(index)
        chapterLoadingJobs.remove(index)?.cancel()
        chapterLoadingLayoutKeys.remove(index)
        loadingChapters.remove(index)
        sequenceOf(prevTextChapter, curTextChapter, nextTextChapter)
            .filterNotNull()
            .filter { it.chapter.index == index && !it.isCompleted }
            .toList()
            .forEach { clearIncompleteChapterSlot(index, it) }
    }

    @Synchronized
    private fun beginChapterLoad(index: Int, layoutKey: String): Long? {
        if (loadingChapters.contains(index)) return null
        loadingChapters.add(index)
        val generation = chapterLoadGenerationSeed.incrementAndGet()
        chapterLoadGenerations[index] = generation
        chapterLoadingLayoutKeys[index] = layoutKey
        return generation
    }

    @Synchronized
    private fun updateChapterLoadLayoutKey(
        index: Int,
        generation: Long,
        layoutKey: String
    ): Long? {
        if (chapterLoadGenerations[index] != generation) return null
        chapterLoadingLayoutKeys[index] = layoutKey
        return generation
    }

    @Synchronized
    private fun finishChapterLoad(index: Int, generation: Long) {
        if (chapterLoadGenerations[index] == generation) {
            chapterLoadGenerations.remove(index)
            chapterLoadingLayoutKeys.remove(index)
            loadingChapters.remove(index)
        }
    }

    private fun currentChapterLoadGeneration(index: Int): Long? =
        chapterLoadGenerations[index]

    internal fun cancelContentLoad(index: Int, generation: Long) {
        finishChapterLoad(index, generation)
    }

    private fun isChapterLoadRequestCurrent(
        bookUrl: String,
        chapterIndex: Int,
        layoutKey: String,
        generation: Long
    ): Boolean {
        return isChapterLoadTokenCurrent(bookUrl, chapterIndex, layoutKey, generation) &&
                currentChapterLayoutKey() == layoutKey
    }

    private fun isChapterLoadTokenCurrent(
        bookUrl: String,
        chapterIndex: Int,
        layoutKey: String,
        generation: Long
    ): Boolean {
        return book?.bookUrl == bookUrl &&
                chapterLoadGenerations[chapterIndex] == generation &&
                chapterLoadingLayoutKeys[chapterIndex] == layoutKey &&
                chapterIndex in durChapterIndex - 1..durChapterIndex + 1
    }

    private fun isChapterLoadSlotCurrent(
        bookUrl: String,
        chapterIndex: Int,
        layoutKey: String,
        generation: Long,
        expectedOffset: Int,
        expectedChapter: TextChapter? = null
    ): Boolean {
        if (!isChapterLoadTokenCurrent(bookUrl, chapterIndex, layoutKey, generation) ||
            chapterIndex - durChapterIndex != expectedOffset
        ) {
            return false
        }
        return expectedChapter == null || textChapter(expectedOffset) === expectedChapter
    }

    @Synchronized
    private fun installChapterSlot(
        bookUrl: String,
        chapterIndex: Int,
        layoutKey: String,
        generation: Long,
        expectedOffset: Int,
        textChapter: TextChapter
    ): Boolean {
        if (!isChapterLoadTokenCurrent(bookUrl, chapterIndex, layoutKey, generation) ||
            chapterIndex - durChapterIndex != expectedOffset
        ) return false
        when (expectedOffset) {
            -1 -> {
                prevTextChapter?.takeIf { it !== textChapter }?.cancelLayout()
                prevTextChapter = textChapter
            }
            0 -> {
                curTextChapter?.takeIf { it !== textChapter }?.cancelLayout()
                curTextChapter = textChapter
            }
            1 -> {
                nextTextChapter?.takeIf { it !== textChapter }?.cancelLayout()
                nextTextChapter = textChapter
            }
            else -> return false
        }
        chapterLayoutKeys[chapterIndex] = layoutKey
        return true
    }

    @Synchronized
    fun removeLoading(index: Int) {
        loadingChapters.remove(index)
    }

    @Synchronized
    private fun clearIncompleteChapterSlot(chapterIndex: Int, expectedChapter: TextChapter?) {
        if (expectedChapter == null || expectedChapter.isCompleted) return
        var cleared = false
        if (prevTextChapter === expectedChapter) {
            prevTextChapter = null
            cleared = true
        }
        if (curTextChapter === expectedChapter) {
            curTextChapter = null
            cleared = true
        }
        if (nextTextChapter === expectedChapter) {
            nextTextChapter = null
            cleared = true
        }
        if (cleared) {
            expectedChapter.cancelLayout()
            chapterLayoutKeys.remove(chapterIndex)
        }
    }

    /**
     * 内容加载完成
     */
    @Synchronized
    fun contentLoadFinish(
        book: Book,
        chapter: BookChapter,
        content: String,
        upContent: Boolean = true,
        resetPageOffset: Boolean,
        canceled: Boolean = false,
        requestGeneration: Long? = null,
        success: (() -> Unit)? = null
    ) {
        val generation = requestGeneration ?: return
        val requestedLayoutKey = chapterLoadingLayoutKeys[chapter.index]
        if (requestedLayoutKey == null || chapter.bookUrl != book.bookUrl || canceled ||
            !isChapterLoadRequestCurrent(
                book.bookUrl,
                chapter.index,
                requestedLayoutKey,
                generation
            )
        ) {
            finishChapterLoad(chapter.index, generation)
            return
        }
        if (chapterLoadingJobs[chapter.index]?.isActive == true) {
            return
        }
        var installedTextChapter: TextChapter? = null
        lateinit var job: Coroutine<Boolean>
        job = Coroutine.async(this, start = CoroutineStart.LAZY) {
            if (!isChapterLoadRequestCurrent(
                    book.bookUrl,
                    chapter.index,
                    requestedLayoutKey,
                    generation
                )
            ) {
                return@async false
            }
            val contentProcessor = ContentProcessor.get(book.name, book.origin)
            val displayTitle = chapter.getDisplayTitle(
                contentProcessor.getTitleReplaceRules(),
                book.getUseReplaceRule(),
                replaceBook = book.toReplaceBook()
            )
            val contents = paragraphRuleProcessingSemaphore.withPermit {
                ParagraphRuleProcessor.process(
                    book,
                    chapter,
                    contentProcessor.getContent(book, chapter, content, includeTitle = false)
                )
            }
            ensureActive()
            if (!isChapterLoadRequestCurrent(
                    book.bookUrl,
                    chapter.index,
                    requestedLayoutKey,
                    generation
                )
            ) {
                return@async false
            }
            val textChapter = ChapterProvider.getTextChapterAsync(
                this, book, chapter, displayTitle, contents, simulatedChapterSize
            )
            installedTextChapter = textChapter
            when (val offset = chapter.index - durChapterIndex) {
                0 -> curChapterLoadingLock.withLock {
                    val installed = withContext(Main) {
                        ensureActive()
                        installChapterSlot(
                            book.bookUrl,
                            chapter.index,
                            requestedLayoutKey,
                            generation,
                            offset,
                            textChapter
                        )
                    }
                    if (!installed) return@withLock false
                    if (!isChapterLoadSlotCurrent(
                            book.bookUrl,
                            chapter.index,
                            requestedLayoutKey,
                            generation,
                            offset,
                            textChapter
                        )
                    ) return@withLock false
                    callBack?.get()?.upMenuView()
                    var available = false
                    for (page in textChapter.layoutChannel) {
                        if (!isChapterLoadSlotCurrent(
                                book.bookUrl,
                                chapter.index,
                                requestedLayoutKey,
                                generation,
                                offset,
                                textChapter
                            )
                        ) {
                            textChapter.cancelLayout()
                            return@withLock false
                        }
                        val index = page.index
                        if (!available && page.containPos(durChapterPos)) {
                            if (upContent) {
                                callBack?.get()?.upContent(offset, resetPageOffset)
                            }
                            available = true
                        }
                        if (upContent && isScroll && available &&
                            index > durPageIndex && index <= durPageIndex + 2
                        ) {
                            callBack?.get()?.upContent(offset, false)
                        }
                        callBack?.get()?.onLayoutPageCompleted(index, page)
                    }
                    if (!isChapterLoadSlotCurrent(
                            book.bookUrl,
                            chapter.index,
                            requestedLayoutKey,
                            generation,
                            offset,
                            textChapter
                        )
                    ) return@withLock false
                    if (upContent) callBack?.get()?.upContent(offset, !available && resetPageOffset)
                    val fromReadAloud = readAloudPendingLoadChapterIndex == chapter.index
                    if (fromReadAloud) {
                        readAloudPendingLoadChapterIndex = -1
                    }
                    curPageChanged(fromReadAloud = fromReadAloud)
                    callBack?.get()?.contentLoadFinish()
                    isChapterLoadSlotCurrent(
                        book.bookUrl,
                        chapter.index,
                        requestedLayoutKey,
                        generation,
                        offset,
                        textChapter
                    )
                }

                -1 -> prevChapterLoadingLock.withLock {
                    val installed = withContext(Main) {
                        ensureActive()
                        installChapterSlot(
                            book.bookUrl,
                            chapter.index,
                            requestedLayoutKey,
                            generation,
                            offset,
                            textChapter
                        )
                    }
                    if (!installed) return@withLock false
                    for (page in textChapter.layoutChannel) {
                        if (!isChapterLoadSlotCurrent(
                                book.bookUrl,
                                chapter.index,
                                requestedLayoutKey,
                                generation,
                                offset,
                                textChapter
                            )
                        ) {
                            textChapter.cancelLayout()
                            return@withLock false
                        }
                    }
                    if (!isChapterLoadSlotCurrent(
                            book.bookUrl,
                            chapter.index,
                            requestedLayoutKey,
                            generation,
                            offset,
                            textChapter
                        )
                    ) return@withLock false
                    if (upContent) callBack?.get()?.upContent(offset, resetPageOffset)
                    isChapterLoadSlotCurrent(
                        book.bookUrl,
                        chapter.index,
                        requestedLayoutKey,
                        generation,
                        offset,
                        textChapter
                    )
                }

                1 -> nextChapterLoadingLock.withLock {
                    val installed = withContext(Main) {
                        ensureActive()
                        installChapterSlot(
                            book.bookUrl,
                            chapter.index,
                            requestedLayoutKey,
                            generation,
                            offset,
                            textChapter
                        )
                    }
                    if (!installed) return@withLock false
                    for (page in textChapter.layoutChannel) {
                        if (!isChapterLoadSlotCurrent(
                                book.bookUrl,
                                chapter.index,
                                requestedLayoutKey,
                                generation,
                                offset,
                                textChapter
                            )
                        ) {
                            textChapter.cancelLayout()
                            return@withLock false
                        }
                        if (page.index > 1) {
                            continue
                        }
                        if (upContent) callBack?.get()?.upContent(offset, resetPageOffset)
                    }
                    isChapterLoadSlotCurrent(
                        book.bookUrl,
                        chapter.index,
                        requestedLayoutKey,
                        generation,
                        offset,
                        textChapter
                    )
                }

                else -> false
            }
        }.onError {
            if (it is CancellationException) {
                return@onError
            }
            AppLog.put("ChapterProvider ERROR", it)
            if (chapter.index == durChapterIndex) {
                showCurrentChapterLoadError(
                    chapter.index,
                    "Layout failed: ${it.localizedMessage ?: it::class.java.simpleName}",
                    it
                )
                return@onError
            }
            appCtx.toastOnUi("ChapterProvider ERROR:\n${it.stackTraceStr}")
        }.onSuccess { applied ->
            if (applied) success?.invoke()
        }
        chapterLoadingJobs[chapter.index] = job
        job.invokeOnCompletion {
            if (chapterLoadingJobs.remove(chapter.index, job)) {
                clearIncompleteChapterSlot(chapter.index, installedTextChapter)
                finishChapterLoad(chapter.index, generation)
            }
        }
        job.start()
    }

    suspend fun contentLoadFinishAwait(
        book: Book,
        chapter: BookChapter,
        content: String,
        upContent: Boolean = true,
        resetPageOffset: Boolean,
        requestGeneration: Long
    ): Boolean {
        if (chapter.bookUrl != book.bookUrl) return false
        val requestedLayoutKey = chapterLoadingLayoutKeys[chapter.index] ?: return false
        if (!isChapterLoadRequestCurrent(
                book.bookUrl,
                chapter.index,
                requestedLayoutKey,
                requestGeneration
            )
        ) return false
        var textChapter: TextChapter? = null
        var applied = false
        try {
            val contentProcessor = ContentProcessor.get(book.name, book.origin)
            val displayTitle = chapter.getDisplayTitle(
                contentProcessor.getTitleReplaceRules(),
                book.getUseReplaceRule(),
                replaceBook = book.toReplaceBook()
            )
            val contents = paragraphRuleProcessingSemaphore.withPermit {
                ParagraphRuleProcessor.process(
                    book,
                    chapter,
                    contentProcessor.getContent(book, chapter, content, includeTitle = false)
                )
            }
            ensureActive()
            if (!isChapterLoadRequestCurrent(
                    book.bookUrl,
                    chapter.index,
                    requestedLayoutKey,
                    requestGeneration
                )
            ) return false
            val createdChapter = ChapterProvider.getTextChapterAsync(
                this@ReadBook, book, chapter, displayTitle, contents, simulatedChapterSize
            )
            textChapter = createdChapter
            applied = when (val offset = chapter.index - durChapterIndex) {
                0 -> curChapterLoadingLock.withLock {
                    val installed = withContext(Main) {
                        ensureActive()
                        installChapterSlot(
                            book.bookUrl,
                            chapter.index,
                            requestedLayoutKey,
                            requestGeneration,
                            offset,
                            createdChapter
                        )
                    }
                    if (!installed) return@withLock false
                    callBack?.get()?.upMenuView()
                    var available = false
                    for (page in createdChapter.layoutChannel) {
                        if (!isChapterLoadSlotCurrent(
                                book.bookUrl,
                                chapter.index,
                                requestedLayoutKey,
                                requestGeneration,
                                offset,
                                createdChapter
                            )
                        ) {
                            createdChapter.cancelLayout()
                            return@withLock false
                        }
                        val index = page.index
                        if (!available && page.containPos(durChapterPos)) {
                            if (upContent) {
                                callBack?.get()?.upContent(offset, resetPageOffset)
                            }
                            available = true
                        }
                        if (upContent && isScroll && available &&
                            index > durPageIndex && index <= durPageIndex + 2
                        ) {
                            callBack?.get()?.upContent(offset, false)
                        }
                        callBack?.get()?.onLayoutPageCompleted(index, page)
                    }
                    if (!isChapterLoadSlotCurrent(
                            book.bookUrl,
                            chapter.index,
                            requestedLayoutKey,
                            requestGeneration,
                            offset,
                            createdChapter
                        )
                    ) return@withLock false
                    if (upContent) callBack?.get()?.upContent(offset, !available && resetPageOffset)
                    val fromReadAloud = readAloudPendingLoadChapterIndex == chapter.index
                    if (fromReadAloud) {
                        readAloudPendingLoadChapterIndex = -1
                    }
                    curPageChanged(fromReadAloud = fromReadAloud)
                    callBack?.get()?.contentLoadFinish()
                    isChapterLoadSlotCurrent(
                        book.bookUrl,
                        chapter.index,
                        requestedLayoutKey,
                        requestGeneration,
                        offset,
                        createdChapter
                    )
                }

                -1 -> prevChapterLoadingLock.withLock {
                    val installed = withContext(Main) {
                        ensureActive()
                        installChapterSlot(
                            book.bookUrl,
                            chapter.index,
                            requestedLayoutKey,
                            requestGeneration,
                            offset,
                            createdChapter
                        )
                    }
                    if (!installed) return@withLock false
                    for (page in createdChapter.layoutChannel) {
                        if (!isChapterLoadSlotCurrent(
                                book.bookUrl,
                                chapter.index,
                                requestedLayoutKey,
                                requestGeneration,
                                offset,
                                createdChapter
                            )
                        ) {
                            createdChapter.cancelLayout()
                            return@withLock false
                        }
                    }
                    if (upContent) callBack?.get()?.upContent(offset, resetPageOffset)
                    isChapterLoadSlotCurrent(
                        book.bookUrl,
                        chapter.index,
                        requestedLayoutKey,
                        requestGeneration,
                        offset,
                        createdChapter
                    )
                }

                1 -> nextChapterLoadingLock.withLock {
                    val installed = withContext(Main) {
                        ensureActive()
                        installChapterSlot(
                            book.bookUrl,
                            chapter.index,
                            requestedLayoutKey,
                            requestGeneration,
                            offset,
                            createdChapter
                        )
                    }
                    if (!installed) return@withLock false
                    for (page in createdChapter.layoutChannel) {
                        if (!isChapterLoadSlotCurrent(
                                book.bookUrl,
                                chapter.index,
                                requestedLayoutKey,
                                requestGeneration,
                                offset,
                                createdChapter
                            )
                        ) {
                            createdChapter.cancelLayout()
                            return@withLock false
                        }
                        if (page.index > 1) {
                            continue
                        }
                        if (upContent) callBack?.get()?.upContent(offset, resetPageOffset)
                    }
                    isChapterLoadSlotCurrent(
                        book.bookUrl,
                        chapter.index,
                        requestedLayoutKey,
                        requestGeneration,
                        offset,
                        createdChapter
                    )
                }

                else -> false
            }
            return applied
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.put("ChapterProvider ERROR", e)
            if (chapter.index == durChapterIndex) {
                showCurrentChapterLoadError(
                    chapter.index,
                    "Layout failed: ${e.localizedMessage ?: e::class.java.simpleName}",
                    e
                )
            } else {
                appCtx.toastOnUi("ChapterProvider ERROR:\n${e.stackTraceStr}")
            }
            return false
        } finally {
            if (!applied) clearIncompleteChapterSlot(chapter.index, textChapter)
        }
    }

    fun invalidateEpubResource(bookUrl: String, chapterIndex: Int, src: String) {
        val currentBook = book ?: return
        if (currentBook.bookUrl != bookUrl || !currentBook.isEpub) return
        launch(Main) {
            val changed = sequenceOf(prevTextChapter, curTextChapter, nextTextChapter)
                .filterNotNull()
                .filter { it.chapter.index == chapterIndex }
                .flatMap { it.pages.asSequence() }
                .filter { it.invalidateEpubResource(src) }
                .any()
            if (changed) {
                callBack?.get()?.upContent(0, false)
            }
        }
    }

    /**
     * 预下载时，章节已完，更新目录
     */
    @Synchronized
    fun upToc() {
        val bookSource = bookSource ?: return
        val book = book ?: return
        if (!book.canUpdate) return
        if (chapterSize - durChapterIndex - 1 >= 3) return
        if (System.currentTimeMillis() - book.lastCheckTime < 600000) return
        book.lastCheckTime = System.currentTimeMillis()
        val oldBook = book.copy()
        WebBook.getChapterList(this, bookSource, book).onSuccess(IO) { cList ->
            ensureActive()
            if (cList.size > chapterSize) {
                val oldChapterList = appDb.bookChapterDao.getChapterList(oldBook.bookUrl)
                BookHelp.remapContentCache(oldBook, oldChapterList, cList)
                if (oldBook.bookUrl == book.bookUrl) {
                    appDb.bookDao.update(book)
                } else {
                    appDb.bookDao.replace(oldBook, book)
                    BookHelp.updateCacheFolder(oldBook, book)
                }
                appDb.runInTransaction {
                    appDb.bookChapterDao.delByBook(oldBook.bookUrl)
                    appDb.bookChapterDao.insert(*cList.toTypedArray())
                }
                onChapterListUpdated(book, false)
                nextTextChapter ?: loadContent(durChapterIndex + 1)
            }
        }
    }

    fun pageAnim(): Int {
        return book?.getPageAnim() ?: ReadBookConfig.pageAnim
    }

    fun setCharset(charset: String) {
        book?.let {
            it.charset = charset
            callBack?.get()?.loadChapterList(it)
        }
        saveRead(fullUpdate = true)
    }

    fun saveRead(pageChanged: Boolean = false, fullUpdate: Boolean = false) {
        val targetBook = book ?: return
        val targetChapterIndex = durChapterIndex
        val targetChapterPos = durChapterPos
        val targetBookSource = bookSource
        val durTime = System.currentTimeMillis()
        val chapterChanged = targetBook.durChapterIndex != targetChapterIndex
        targetBook.lastCheckCount = 0
        targetBook.durChapterIndex = targetChapterIndex
        targetBook.durChapterPos = targetChapterPos
        targetBook.durChapterTime = durTime
        curTextChapter
            ?.takeIf {
                it.chapter.bookUrl == targetBook.bookUrl &&
                        it.chapter.index == targetChapterIndex
            }
            ?.title
            ?.takeIf(String::isNotBlank)
            ?.let { targetBook.durChapterTitle = it }
        val bookSnapshot = targetBook.copy().also {
            it.infoHtml = targetBook.infoHtml
            it.tocHtml = targetBook.tocHtml
            it.downloadUrls = targetBook.downloadUrls
        }
        executor.execute {
            kotlin.runCatching {
                if (!pageChanged || chapterChanged) {
                    appDb.bookChapterDao.getChapter(
                        bookSnapshot.bookUrl,
                        targetChapterIndex
                    )?.let {
                        bookSnapshot.durChapterTitle = it.getDisplayTitle(
                            ContentProcessor.get(
                                bookSnapshot.name,
                                bookSnapshot.origin
                            ).getTitleReplaceRules(),
                            bookSnapshot.getUseReplaceRule(),
                            replaceBook = bookSnapshot.toReplaceBook()
                        )
                        SourceCallBack.callBackBook(
                            SourceCallBack.SAVE_READ,
                            targetBookSource,
                            bookSnapshot,
                            it,
                            durTime.toString()
                        )
                    }
                }
                if (fullUpdate) {
                    bookSnapshot.update()
                } else {
                    appDb.bookDao.updateReadProgress(
                        bookUrl = bookSnapshot.bookUrl,
                        lastCheckCount = bookSnapshot.lastCheckCount,
                        durChapterTitle = bookSnapshot.durChapterTitle,
                        durChapterIndex = bookSnapshot.durChapterIndex,
                        durChapterPos = bookSnapshot.durChapterPos,
                        durChapterTime = bookSnapshot.durChapterTime
                    )
                }
                appDb.readRecentBookDao.insert(ReadRecentBook(bookSnapshot.bookUrl, durTime))
                ReadRecordWidgetStore.updateRecentSnapshot(bookSnapshot, durTime)
            }.onFailure {
                AppLog.put("保存书籍阅读进度信息出错\n$it", it)
            }
        }
    }

    /**
     * 预下载
     */
    private fun preDownload() {
        if (book?.isLocal == true) return
        executor.execute {
            if (AppConfig.preDownloadNum < 2) {
                upToc()
                return@execute
            }
            preDownloadTask?.cancel()
            preDownloadTask = launch(IO) {
                //预下载
                launch {
                    val maxChapterIndex =
                        min(durChapterIndex + AppConfig.preDownloadNum, chapterSize)
                    for (i in durChapterIndex.plus(2)..maxChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i)
                    }
                }
                launch {
                    val minChapterIndex = durChapterIndex - min(5, AppConfig.preDownloadNum)
                    for (i in durChapterIndex.minus(2) downTo minChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i)
                    }
                }
            }
        }
    }

    fun cancelPreDownloadTask() {
        if (contentLoadFinish) {
            preDownloadTask?.cancel()
            downloadScope.coroutineContext.cancelChildren()
        }
    }

    fun onChapterListUpdated(newBook: Book, loadContent: Boolean = true) {
        if (newBook.isSameNameAuthor(book)) {
            book = newBook
            chapterSize = newBook.totalChapterNum
            simulatedChapterSize = newBook.simulatedTotalChapterNum()
            if (simulatedChapterSize > 0 && durChapterIndex > simulatedChapterSize - 1) {
                durChapterIndex = simulatedChapterSize - 1
            }
            callBack?.get()?.upMenuView()
            if (callBack == null) {
                clearTextChapter()
            } else if (loadContent) {
                loadContent(true)
            }
        }
    }

    private fun clearExpiredChapterLoadingJob(clearAll: Boolean = false) {
        for ((index, job) in chapterLoadingJobs.entries.toList()) {
            if (clearAll || index !in durChapterIndex - 1..durChapterIndex + 1) {
                if (chapterLoadingJobs.remove(index, job)) {
                    job.cancel()
                }
            }
        }
        for ((index, generation) in chapterLoadGenerations.entries.toList()) {
            if (clearAll || index !in durChapterIndex - 1..durChapterIndex + 1) {
                cancelChapterLoading(index, generation)
            }
        }
    }

    /**
     * 注册回调
     */
    fun register(cb: CallBack) {
        callBack?.get()?.notifyBookChanged()
        callBack = WeakReference(cb)
    }

    /**
     * 取消注册回调
     */
    fun unregister(cb: CallBack): Boolean {
        if (callBack?.get() !== cb) return false
        callBack = null
        releaseAndCancel()
        return true
    }

    private fun releaseAndCancel() {
        msg = null
        preDownloadTask?.cancel()
        downloadScope.coroutineContext.cancelChildren()
        coroutineContext.cancelChildren()
        ImageProvider.clear()
        clearExpiredChapterLoadingJob(true)
        if (!CacheBookService.isRun) {
            CacheBook.close()
        }
    }

    interface CallBack : LayoutProgressListener {
        fun upMenuView()

        fun loadChapterList(book: Book)

        fun upContent(
            relativePosition: Int = 0,
            resetPageOffset: Boolean = true,
            success: (() -> Unit)? = null
        )

        suspend fun upContentAwait(
            relativePosition: Int = 0,
            resetPageOffset: Boolean = true,
            success: (() -> Unit)? = null
        )

        fun pageChanged()

        fun contentLoadFinish()

        fun upPageAnim(upRecorder: Boolean = false)

        fun notifyBookChanged()

        fun sureNewProgress(progress: BookProgress)

        fun cancelSelect()

        /**
         * C1 手动翻页挂钩（LC onManualPageChanged :2289-2301）：仅在朗读中发生用户主动导航时回调，
         * 由 markReadAloudUserNavigation 触发；默认空实现，朗读页覆写做强制追页/面板派生刷新。
         */
        fun onManualPageChanged() {}
    }

}
