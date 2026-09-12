package io.legado.app.model

import android.annotation.SuppressLint
import android.util.Log
import io.legado.app.BuildConfig
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.*
import io.legado.app.help.book.isWebFile
import io.legado.app.help.coroutine.CompositeCoroutine
import io.legado.app.help.source.sortUrls
import io.legado.app.model.rss.Rss
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

object Debug {
    var callback: Callback? = null
    private var debugSource: String? = null
    private val tasks: CompositeCoroutine = CompositeCoroutine()
    val debugMessageMap = HashMap<String, String>()
    private val debugTimeMap = HashMap<String, Long>()
    var isChecking: Boolean = false

    /**
     * log-compliance-cleanup 批次E：调试会话缓冲（业务侧第四通道，不并入 AppLog——用户裁决口径）。
     * 痛点：调试日志此前仅存于 UI 内存列表，Activity 关闭即丢、release 包 logcat 也被 DEBUG 守卫拦截，
     * 用户真机调试书源失败后无法提供日志给 AI/他人分析（只能截图）。
     * 方案：Debug.log 单点同步写入环形缓冲（上限 500 行，超限淘汰最旧），供调试页一键复制/分享。
     */
    private const val MAX_SESSION_LINES = 500
    private val sessionLines = ArrayDeque<String>(64)

    @SuppressLint("ConstantLocale")
    private val debugTimeFormat = SimpleDateFormat("[mm:ss.SSS]", Locale.getDefault())
    private var startTime: Long = System.currentTimeMillis()

    /** 取当前会话调试日志全文（含时间戳，最旧在前；会话结束/新会话开始时清空）。
     *  @Synchronized 与 log()/cancelDebug() 同锁（this）：ArrayDeque 遍历中并发 addLast/clear 会 CME */
    @Synchronized
    fun getSessionLogs(): String = sessionLines.joinToString("\n")

    /** 「清空日志」：仅清文本缓冲，不影响进行中的调试任务（与 clearEvents 配套） */
    @Synchronized
    fun clearSessionLogs() {
        sessionLines.clear()
    }

    /**
     * debug-page-redesign AD-01：结构化事件流（对标 MD3阅读）。
     * kind 复用既有 state 码：1 过程 / -1 错误 / 1000 完成 / 10 搜索(列表)响应 / 20 详情(内容)响应 /
     * 30 目录响应 / 40 正文响应——产生点：BookList/BookInfo/BookChapterList/BookContent/RssParserByRule/Rss。
     * UI 侧 collectAsState 消费；callback/isChecking 校验链保持原语义不动（AD-01 决策：不全量 Session 化）。
     */
    data class DebugEvent(
        val kind: Int,
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
        val elapsedMillis: Long = timestamp - startTime,
    )

    private const val MAX_EVENT_ENTRIES = 1000
    private val _events = MutableStateFlow<List<DebugEvent>>(emptyList())
    val events: StateFlow<List<DebugEvent>> = _events.asStateFlow()

    /** 新调试会话开始前清空事件流（UI 侧调用；@Synchronized 防与 log() 并发丢条目/竞态） */
    @Synchronized
    fun clearEvents() {
        _events.value = emptyList()
    }

    @Synchronized
    fun log(
        sourceUrl: String?,
        msg: String = "",
        print: Boolean = true,
        isHtml: Boolean = false,
        showTime: Boolean = true,
        state: Int = 1
    ) {
        // P0 截断保护：单点截断覆盖下游 Log.d + callback + isChecking 分支（复用 AppLog.truncateSafely 避免循环依赖）
        val safeMsg = AppLog.truncateSafely(msg)
        // 批次E：logcat 守卫放宽——recordLog 开启时 release 包也输出（调试是用户主动行为，
        // 会话短暂不构成噪音；AI 可 adb logcat -s sourceDebug 从用户真机采集）
        if (BuildConfig.DEBUG || AppLog.recordLogEnabled()) {
            Log.d("sourceDebug", safeMsg)
        }
        // 批次E：会话缓冲同步写入（不受 callback 过滤影响，全量留痕）
        var bufferMsg = if (isHtml) HtmlFormatter.format(safeMsg) else safeMsg
        if (showTime) {
            val time = debugTimeFormat.format(Date(System.currentTimeMillis() - startTime))
            bufferMsg = "$time $bufferMsg"
        }
        sessionLines.addLast(bufferMsg)
        while (sessionLines.size > MAX_SESSION_LINES) {
            sessionLines.removeFirst()
        }
        // debug-page-redesign AD-01：同步发射结构化事件（log() 已 @Synchronized，追加无竞态）
        _events.value = (_events.value + DebugEvent(
            kind = state,
            message = bufferMsg,
        )).takeLast(MAX_EVENT_ENTRIES)
        //调试信息始终要执行
        callback?.let {
            if ((debugSource != sourceUrl || !print)) return
            var printMsg = safeMsg
            if (isHtml) {
                printMsg = HtmlFormatter.format(safeMsg)
            }
            if (showTime) {
                val time = debugTimeFormat.format(Date(System.currentTimeMillis() - startTime))
                printMsg = "$time $printMsg"
            }
            it.printLog(state, printMsg)
        }
        if (isChecking && sourceUrl != null && safeMsg.length < 30) {
            var printMsg = safeMsg
            if (isHtml) {
                printMsg = HtmlFormatter.format(safeMsg)
            }
            if (showTime && debugTimeMap[sourceUrl] != null) {
                val time =
                    debugTimeFormat.format(Date(System.currentTimeMillis() - debugTimeMap[sourceUrl]!!))
                printMsg = printMsg.replace(AppPattern.debugMessageSymbolRegex, "")

                debugMessageMap[sourceUrl] = "$time $printMsg"
            }
        }
    }

    @Synchronized
    fun log(msg: String?) {
        log(debugSource, msg ?: "", true)
    }

    @Synchronized
    fun cancelDebug(destroy: Boolean = false) {
        tasks.clear()
        // 批次E：会话结束（新调试开始或页面销毁）时清空缓冲，避免跨会话串日志（同锁防与 log()/getSessionLogs 并发）
        sessionLines.clear()

        if (destroy) {
            debugSource = null
            callback = null
        }
    }

    /**
     * 临时切换调试上下文执行代码块（archive ParagraphRuleProcessor 依赖）
     * 执行结束后恢复原 debugSource/callback/startTime
     */
    suspend fun <T> withDebugSource(sourceUrl: String?, callback: Callback?, block: suspend () -> T): T {
        val oldSource = debugSource
        val oldCallback = this.callback
        debugSource = sourceUrl
        this.callback = callback
        startTime = System.currentTimeMillis()
        return try {
            block()
        } finally {
            debugSource = oldSource
            this.callback = oldCallback
        }
    }

    fun startChecking(source: BookSource) {
        isChecking = true
        debugTimeMap[source.bookSourceUrl] = System.currentTimeMillis()
        debugMessageMap[source.bookSourceUrl] = "${debugTimeFormat.format(Date(0))} 开始校验"
    }

    fun finishChecking() {
        isChecking = false
    }

    fun getRespondTime(sourceUrl: String): Long {
        return debugTimeMap[sourceUrl] ?: CheckSource.timeout
    }

    fun updateFinalMessage(sourceUrl: String, state: String) {
        if (debugTimeMap[sourceUrl] != null && debugMessageMap[sourceUrl] != null) {
            val spendingTime = System.currentTimeMillis() - debugTimeMap[sourceUrl]!!
            debugTimeMap[sourceUrl] =
                if (state == "校验成功") spendingTime else CheckSource.timeout + spendingTime
            val printTime = debugTimeFormat.format(Date(spendingTime))
            debugMessageMap[sourceUrl] = "$printTime $state"
        }
    }

    suspend fun startDebug(scope: CoroutineScope, rssSource: RssSource) {
        cancelDebug()
        debugSource = rssSource.sourceUrl
        log(debugSource, "︾开始解析")
        val sort = rssSource.sortUrls().first()
        Rss.getArticles(scope, sort.first, sort.second, rssSource, 1)
            .onSuccess {
                if (it.first.isEmpty()) {
                    log(debugSource, "⇒列表页解析成功，为空")
                    log(debugSource, "︽解析完成", state = 1000)
                } else {
                    val ruleContent = rssSource.ruleContent
                    if (!rssSource.ruleArticles.isNullOrBlank() && rssSource.ruleDescription.isNullOrBlank()) {
                        log(debugSource, "︽列表页解析完成")
                        log(debugSource, showTime = false)
                        if (ruleContent.isNullOrEmpty()) {
                            log(debugSource, "⇒内容规则为空，默认获取整个网页", state = 1000)
                        } else {
                            rssContentDebug(scope, it.first[0], ruleContent, rssSource)
                        }
                    } else {
                        log(debugSource, "⇒存在描述规则，不解析内容页")
                        log(debugSource, "︽解析完成", state = 1000)
                    }
                }
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
    }

    fun startDebug(scope: CoroutineScope, rssSource: RssSource, key: String) {
        cancelDebug()
        debugSource = rssSource.sourceUrl
        startTime = System.currentTimeMillis()
        when {
            key.contains("::") -> {
                val name = key.substringBefore("::")
                val url = key.substringAfter("::")
                log(debugSource, "⇒开始访问分类页:$url")
                log(debugSource, "︾开始解析分类页")
                sortDebug(scope, rssSource, name, url)
            }

            key.isAbsUrl() -> {
                val ruleContent = rssSource.ruleContent
                if (!rssSource.ruleArticles.isNullOrBlank() && rssSource.ruleDescription.isNullOrBlank()) {
                    if (ruleContent.isNullOrEmpty()) {
                        log(debugSource, "⇒内容规则为空，默认获取整个网页", state = 1000)
                    } else {
                        val rssArticle = RssArticle()
                        rssArticle.origin = rssSource.sourceUrl
                        rssArticle.link = key
                        log(debugSource, "⇒开始访问内容页:$key")
                        rssContentDebug(scope, rssArticle, ruleContent, rssSource)
                    }
                } else {
                    log(debugSource, "⇒存在描述规则，不解析内容页")
                    log(debugSource, "︽解析完成", state = 1000)
                }
            }

            else -> {
                val searchUrl = rssSource.searchUrl
                if (searchUrl.isNullOrEmpty()) {
                    log(debugSource, "⇒搜索URL为空", state = -1)
                    return
                }
                log(debugSource, "⇒开始搜索关键字:$key")
                log(debugSource, "︾开始解析搜索页")
                sortDebug(scope, rssSource, "搜索", searchUrl, key)
            }
        }
    }

    private fun sortDebug(scope: CoroutineScope, rssSource: RssSource, name: String, url: String, key: String? = null) {
        Rss.getArticles(scope, name, url, rssSource, 1, key)
            .onSuccess {
                if (it.first.isEmpty()) {
                    log(debugSource, "⇒列表页解析成功，为空")
                    log(debugSource, "︽解析完成", state = 1000)
                } else {
                    val ruleContent = rssSource.ruleContent
                    if (!rssSource.ruleArticles.isNullOrBlank() && rssSource.ruleDescription.isNullOrBlank()) {
                        log(debugSource, "︽列表页解析完成")
                        log(debugSource, showTime = false)
                        if (ruleContent.isNullOrEmpty()) {
                            log(debugSource, "⇒内容规则为空，默认获取整个网页", state = 1000)
                        } else {
                            rssContentDebug(scope, it.first[0], ruleContent, rssSource)
                        }
                    } else {
                        log(debugSource, "⇒存在描述规则，不解析内容页")
                        log(debugSource, "︽解析完成", state = 1000)
                    }
                }
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
    }

    private fun rssContentDebug(
        scope: CoroutineScope,
        rssArticle: RssArticle,
        ruleContent: String,
        rssSource: RssSource
    ) {
        log(debugSource, "︾开始解析内容页")
        Rss.getContent(scope, rssArticle, ruleContent, rssSource)
            .onSuccess {
                log(debugSource, it)
                log(debugSource, "︽内容页解析完成", state = 1000)
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
    }

    fun startDebug(scope: CoroutineScope, bookSource: BookSource, key: String) {
        cancelDebug()
        debugSource = bookSource.bookSourceUrl
        startTime = System.currentTimeMillis()
        when {
            key.isAbsUrl() -> {
                val book = Book()
                book.origin = bookSource.bookSourceUrl
                book.bookUrl = key
                log(debugSource, "⇒开始访问详情页:$key")
                infoDebug(scope, bookSource, book)
            }

            key.contains("::") -> {
                val url = key.substringAfter("::")
                log(debugSource, "⇒开始访问发现页:$url")
                exploreDebug(scope, bookSource, url)
            }

            key.startsWith("++") -> {
                val url = key.substring(2)
                val book = Book()
                book.origin = bookSource.bookSourceUrl
                book.tocUrl = url
                log(debugSource, "⇒开始访目录页:$url")
                tocDebug(scope, bookSource, book)
            }

            key.startsWith("--") -> {
                val url = key.substring(2)
                val book = Book()
                book.origin = bookSource.bookSourceUrl
                log(debugSource, "⇒开始访正文页:$url")
                val chapter = BookChapter()
                chapter.title = "调试"
                chapter.url = url
                contentDebug(scope, bookSource, book, chapter, null)
            }

            else -> {
                log(debugSource, "⇒开始搜索关键字:$key")
                searchDebug(scope, bookSource, key)
            }
        }
    }

    private fun exploreDebug(scope: CoroutineScope, bookSource: BookSource, url: String) {
        log(debugSource, "︾开始解析发现页")
        val explore = WebBook.exploreBook(scope, bookSource, url, 1)
            .onSuccess { exploreBooks ->
                if (exploreBooks.isNotEmpty()) {
                    log(debugSource, "︽发现页解析完成")
                    log(debugSource, showTime = false)
                    infoDebug(scope, bookSource, exploreBooks[0].toBook())
                } else {
                    log(debugSource, "︽未获取到书籍", state = -1)
                }
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
        tasks.add(explore)
    }

    private fun searchDebug(scope: CoroutineScope, bookSource: BookSource, key: String) {
        log(debugSource, "︾开始解析搜索页")
        val search = WebBook.searchBook(scope, bookSource, key, 1)
            .onSuccess { searchBooks ->
                if (searchBooks.isNotEmpty()) {
                    log(debugSource, "︽搜索页解析完成")
                    log(debugSource, showTime = false)
                    infoDebug(scope, bookSource, searchBooks[0].toBook())
                } else {
                    log(debugSource, "︽未获取到书籍", state = -1)
                }
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
        tasks.add(search)
    }

    private fun infoDebug(scope: CoroutineScope, bookSource: BookSource, book: Book) {
        if (book.tocUrl.isNotBlank()) {
            log(debugSource, "≡已获取目录链接,跳过详情页")
            log(debugSource, showTime = false)
            tocDebug(scope, bookSource, book)
            return
        }
        log(debugSource, "︾开始解析详情页")
        val info = WebBook.getBookInfo(scope, bookSource, book)
            .onSuccess {
                log(debugSource, "︽详情页解析完成")
                log(debugSource, showTime = false)
                if (!book.isWebFile) {
                    tocDebug(scope, bookSource, book)
                } else {
                    log(debugSource, "≡文件类书源跳过解析目录", state = 1000)
                }
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
        tasks.add(info)
    }

    private fun tocDebug(scope: CoroutineScope, bookSource: BookSource, book: Book) {
        log(debugSource, "︾开始解析目录页")
        val chapterList = WebBook.getChapterList(scope, bookSource, book)
            .onSuccess { chapters ->
                log(debugSource, "︽目录页解析完成")
                log(debugSource, showTime = false)
                val toc = chapters.filter { !(it.isVolume && it.url.startsWith(it.title)) }
                if (toc.isEmpty()) {
                    log(debugSource, "≡没有正文章节")
                    return@onSuccess
                }
                val nextChapterUrl = toc.getOrNull(1)?.url ?: toc.first().url
                contentDebug(scope, bookSource, book, toc.first(), nextChapterUrl)
            }
            .onError {
                log(debugSource, it.stackTraceStr, state = -1)
            }
        tasks.add(chapterList)
    }

    private fun contentDebug(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String?
    ) {
        log(debugSource, "︾开始解析正文页")
        val content = WebBook.getContent(
            scope = scope,
            bookSource = bookSource,
            book = book,
            bookChapter = bookChapter,
            nextChapterUrl = nextChapterUrl,
            needSave = false
        ).onSuccess {
            log(debugSource, "︽正文页解析完成", state = 1000)
        }.onError {
            log(debugSource, it.stackTraceStr, state = -1)
        }
        tasks.add(content)
    }

    interface Callback {
        fun printLog(state: Int, msg: String)
    }
}