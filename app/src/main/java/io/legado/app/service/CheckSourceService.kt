package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.script.ScriptException
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.IntentData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.BookCheckResult
import io.legado.app.model.CheckSource
import io.legado.app.model.CheckSourceStage
import io.legado.app.model.CheckSourceTaskStore
import io.legado.app.model.Debug
import io.legado.app.model.SourceQualityChecker
import io.legado.app.model.SourceWeightCalculator
import io.legado.app.model.QualityReportApplier
import io.legado.app.model.FailureKind
import io.legado.app.model.SuspectReason
import io.legado.app.model.sampleProbeChapters
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.mozilla.javascript.WrappedException
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors

/**
 * 校验书源
 */
class CheckSourceService : BaseService() {
    private var threadCount = AppConfig.searchThreadCount
    private var searchCoroutine =
        Executors.newFixedThreadPool(threadCount).asCoroutineDispatcher()
    private var notificationMsg = appCtx.getString(R.string.service_starting)
    private var checkJob: Job? = null
    private var originSize = 0
    private var finishCount = 0

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdReadAloud)
            .setSmallIcon(R.drawable.ic_network_check)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.check_book_source))
            .setContentIntent(
                activityPendingIntent<BookSourceActivity>("activity")
            )
            .addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<CheckSourceService>(IntentAction.stop)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> IntentData.get<List<String>>("checkSourceSelectedIds")?.let {
                check(it)
            }

            IntentAction.resume -> upNotification()
            IntentAction.stop -> stopSelf()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        Debug.finishChecking()
        searchCoroutine.close()
        // Q-N5：服务异常销毁（未走完 onCompletion）时兜底收口，避免状态机停在 RUNNING
        CheckSourceTaskStore.finish(cancelled = true)
        postEvent(EventBus.CHECK_SOURCE_DONE, 0)
        notificationManager.cancel(NotificationId.CheckSourceService)
    }

    private fun check(ids: List<String>) {
        if (checkJob?.isActive == true) {
            toastOnUi("已有书源在校验,等完成后再试")
            return
        }
        checkJob = lifecycleScope.launch(searchCoroutine) {
            flow {
                for (origin in ids) {
                    runBlocking(IO) { appDb.bookSourceDao.getBookSource(origin) }?.let {
                        emit(it)
                    }
                }
            }.onStart {
                originSize = ids.size
                finishCount = 0
                // Q-N5：任务开始即建结构化状态（服务与页面共享，跨 Activity 重建可恢复）
                CheckSourceTaskStore.begin(ids.map { BookSourcePart(bookSourceUrl = it) })
                notificationMsg = getString(R.string.progress_show, "", 0, originSize)
                upNotification()
            }.onEachParallel(threadCount) {
                checkSource(it)
            }.onEach {
                finishCount++
                notificationMsg = getString(
                    R.string.progress_show,
                    it.bookSourceName,
                    finishCount,
                    originSize
                )
                upNotification()
                runBlocking(IO) { appDb.bookSourceDao.update(it) }
            }.onCompletion { cause ->
                CheckSourceTaskStore.finish(cancelled = cause is CancellationException)
                stopSelf()
            }.collect()
        }
    }

    private suspend fun checkSource(source: BookSource) {
        val start = System.currentTimeMillis()
        CheckSourceTaskStore.markRunning(
            source.bookSourceUrl,
            source.bookSourceName,
            source.bookSourceType
        )
        kotlin.runCatching {
            withTimeout(CheckSource.timeout) {
                doCheckSource(source)
            }
        }.onSuccess {
            Debug.updateFinalMessage(source.bookSourceUrl, "校验成功")
            // Q-N5：结果评分沿用本仓既有权重口径（不新造分数体系）
            CheckSourceTaskStore.markPassed(
                origin = source.bookSourceUrl,
                durationMillis = System.currentTimeMillis() - start,
                score = source.weight
            )
        }.onFailure {
            currentCoroutineContext().ensureActive()
            // 存疑源（需登录 / jsLib 依赖 / 源变量依赖 / webView 型 / 内网地址）失败时判**跳过·存疑**，
            // 不写失败分组与源注释 —— 与质检底座「存疑类不作为确定性失败证据（不误杀）」同口径；
            // 否则用户按「失败/失效」筛选时会误删这些其实可用的源
            val skipLabel = suspectSkipLabel(SourceQualityChecker.staticSuspectsOf(source))
            if (skipLabel != null) {
                // 存疑放行须**同时清掉本轮已写下的失效分组**：探测中已按维度写过组（如域名不可达 / 搜索失效
                // 恰为最终 throw 触发点）后，本轮会被判「跳过·存疑」，若不清则源上残留"失效"标签，
                // 与「存疑不写失效分组」的判定自相矛盾（用户按「失效」筛选时会误删可用源）
                source.removeInvalidGroups()
                Debug.updateFinalMessage(
                    source.bookSourceUrl,
                    "跳过:$skipLabel ${it.localizedMessage.orEmpty()}".trim()
                )
                CheckSourceTaskStore.markBlocked(
                    origin = source.bookSourceUrl,
                    durationMillis = System.currentTimeMillis() - start,
                    message = skipLabel
                )
            } else {
                // 归因口径单源（见 checkFailureGroupName）：业务异常（NoStackTraceException）自带结论，
                // 不额外打「网站失效」——否则会把"目录取样空/规则为空"等业务失败误标成死站（NEW-21 同族）
                checkFailureGroupName(it)?.let { group -> source.addGroup(group) }
                if (CheckSource.wSourceComment) {
                    source.addErrorComment(it)
                }
                Debug.updateFinalMessage(source.bookSourceUrl, "校验失败:${it.localizedMessage}")
                CheckSourceTaskStore.markFailed(
                    origin = source.bookSourceUrl,
                    message = it.localizedMessage.orEmpty(),
                    durationMillis = System.currentTimeMillis() - start,
                    // NEW-102 归因内置化：异常短路路径的 FailureKind 由单源工具推出（超时 / 脚本 / 站点），
                    // 无异常者为 NONE（维度判定失败 ⇒ 规则层）；横幅 / CSV / REST-MCP 三端读同一字段，
                    // 使用户在「源站不可达」之外还能区分「探测超时」「规则缺陷」
                    failureKind = QualityReportApplier.failureKindOf(it),
                )
            }
        }
        source.respondTime = Debug.getRespondTime(source.bookSourceUrl)
    }

    /** Q-N5：单源阶段打点（过程七阶段可视；并发场景下 stage 仅作展示提示，判定不依赖它） */
    private fun markStage(source: BookSource, stage: CheckSourceStage) {
        CheckSourceTaskStore.markStage(source.bookSourceUrl, source.bookSourceName, stage)
    }

    /**
     * 域名探测核心委托 SourceQualityChecker（AD-08 等价重构：判定核心单一权威源）
     * 行为保持：Socket 快探 2s 外壳/1.6s connect
     */
    private suspend fun isDomainReachable(domain: String): Boolean {
        return SourceQualityChecker.isDomainReachable(domain)
    }

    /**
     * 通过 AnalyzeUrl 发起真实请求校验域名可达性（委托公共组件）
     * 行为保持：原硬编码 30s 超时按原值传入（AD-08 防走样④）
     * @return Pair(是否可达, 真实域名host) - host用于回填source.lastHost,UI分组优先使用
     */
    private suspend fun checkDomainReachable(source: BookSource): Pair<Boolean, String?> {
        return SourceQualityChecker.checkDomainReachable(source, 30000L)
    }

    private suspend fun doCheckSource(source: BookSource) {
        Debug.startChecking(source)
        source.removeInvalidGroups()
        if (CheckSource.wSourceComment) {
            source.removeErrorComment()
        }

        var result = BookCheckResult()

        // 维度1: 域名校验（前置条件，不可并发）
        if (CheckSource.checkDomain) {
            markStage(source, CheckSourceStage.DOMAIN)
            val domain = source.bookSourceUrl
            if (!domain.startsWith("http", ignoreCase = true)) {
                throw NoStackTraceException("源地址不是http链接")
            }
            // 域名校验方式：0=Socket快速检测, 1=AnalyzeUrl真实请求(默认)
            val (reachable, host) = when (CheckSource.domainCheckMode) {
                0 -> Pair(isDomainReachable(domain), null)
                else -> checkDomainReachable(source)
            }
            result = result.copy(domainReachable = reachable, realHost = host)
            if (reachable) {
                // 回填真实域名到lastHost,UI分组用此字段优先于源URL截取
                // （可达无需清「域名失效」组：本轮开头 removeInvalidGroups() 已清空历史失效组）
                if (!host.isNullOrBlank()) source.lastHost = host
            } else {
                // 域名失效分组交共享编排统一回填（与体检侧同调，缺口2 收敛），随后终止本轮
                applyGroupsFromResult(source, result)
                source.weight = 0
                throw NoStackTraceException("源地址不可访问")
            }
        }

        // 维度2+3: 搜索和发现并发执行（Phase 6 重构）
        // 各维度收集结果到局部变量,完成后串行更新分组(避免竞态)
        coroutineScope {
            val searchDeferred = async {
                if (CheckSource.checkSearch) {
                    markStage(source, CheckSourceStage.SEARCH)
                    val searchWord = source.getCheckKeyword(CheckSource.keyword)
                    if (source.searchUrl.isNullOrBlank()) {
                        BookCheckResult(searchChecked = true, searchUrlEmpty = true)
                    } else {
                        val searchBooks = WebBook.searchBookAwait(source, searchWord)
                        if (searchBooks.isEmpty()) {
                            BookCheckResult(searchChecked = true, searchSuccess = false)
                        } else {
                            // 搜索成功,执行 checkBook 收集详情/目录/正文结果
                            val bookResult = checkBook(searchBooks.first().toBook(), source)
                            BookCheckResult(
                                searchChecked = true,
                                searchSuccess = true,
                                searchResultCount = searchBooks.size,
                                searchInfoSuccess = bookResult.infoSuccess,
                                searchCategorySuccess = bookResult.categorySuccess,
                                searchContentSuccess = bookResult.contentSuccess
                            )
                        }
                    }
                } else {
                    BookCheckResult(searchChecked = false)
                }
            }

            val discoveryDeferred = async {
                if (CheckSource.checkDiscovery && !source.exploreUrl.isNullOrBlank()) {
                    markStage(source, CheckSourceStage.DISCOVERY)
                    val url = source.exploreKinds().firstOrNull { !it.url.isNullOrBlank() }?.url
                    if (url.isNullOrBlank()) {
                        BookCheckResult(discoveryChecked = true, discoveryRuleEmpty = true)
                    } else {
                        val exploreBooks = WebBook.exploreBookAwait(source, url)
                        if (exploreBooks.isEmpty()) {
                            BookCheckResult(discoveryChecked = true, discoverySuccess = false)
                        } else {
                            val bookResult = checkBook(exploreBooks.first().toBook(), source, false)
                            BookCheckResult(
                                discoveryChecked = true,
                                discoverySuccess = true,
                                discoveryResultCount = exploreBooks.size,
                                discoveryInfoSuccess = bookResult.infoSuccess,
                                discoveryCategorySuccess = bookResult.categorySuccess,
                                discoveryContentSuccess = bookResult.contentSuccess
                            )
                        }
                    }
                } else {
                    BookCheckResult(discoveryChecked = false)
                }
            }

            val searchResult = searchDeferred.await()
            val discoveryResult = discoveryDeferred.await()

            // 合并结果：分组回填（下一段）与权重计算共用同一份结果
            result = result.copy(
                searchChecked = searchResult.searchChecked,
                searchUrlEmpty = searchResult.searchUrlEmpty,
                searchSuccess = searchResult.searchSuccess,
                searchResultCount = searchResult.searchResultCount,
                searchInfoSuccess = searchResult.searchInfoSuccess,
                searchCategorySuccess = searchResult.searchCategorySuccess,
                searchContentSuccess = searchResult.searchContentSuccess,
                discoveryChecked = discoveryResult.discoveryChecked,
                discoveryRuleEmpty = discoveryResult.discoveryRuleEmpty,
                discoverySuccess = discoveryResult.discoverySuccess,
                discoveryResultCount = discoveryResult.discoveryResultCount,
                discoveryInfoSuccess = discoveryResult.discoveryInfoSuccess,
                discoveryCategorySuccess = discoveryResult.discoveryCategorySuccess,
                discoveryContentSuccess = discoveryResult.discoveryContentSuccess
            )
        }

        // 失效分组回填：唯一编排＝QualityReportApplier（与体检侧同源同调，缺口2 收敛）。
        // 旧写法此处有约 50 行内联 add/removeGroup，靠"与体检侧逐字一致"维系 ⇒ 已收口。
        applyGroupsFromResult(source, result)

        // 权重计算（基于关键元素获取结果，Phase 6 重构）
        source.weight = SourceWeightCalculator.calculateBookWeightFromResult(
            result, CheckSource.checkDomain
        )
        val finalCheckMessage = source.getInvalidGroupNames()
        if (finalCheckMessage.isNotBlank()) {
            throw NoStackTraceException(finalCheckMessage)
        }
    }

    /**
     * 本轮探测结果 → 失效分组回填（**共享编排入口**）。
     *
     * 结论形态（`BookCheckResult`）经唯一适配器 [QualityReportApplier.bookResultToDimStates] 映射为
     * 维度态，再交 [QualityReportApplier.applyBookGroupsFromDims] 写组 —— 与体检侧
     * （`QualityReportApplier.applyBookReports`）走同一实现，杜绝两份"加组规则"漂移。
     *
     * `failureKind = NONE`：本阶段是"维度判定"路径，非异常归因路径（异常归因由 `checkSource` 的
     * onFailure 分支用 [checkFailureGroupName] 处理）。
     */
    private fun applyGroupsFromResult(source: BookSource, result: BookCheckResult) {
        QualityReportApplier.applyBookGroupsFromDims(
            source = source,
            dims = QualityReportApplier.bookResultToDimStates(result),
            failureKind = FailureKind.NONE,
            suspect = false,
            checkDomain = CheckSource.checkDomain,
        )
    }

    /**
     * 校验书源的详情目录正文（Phase 6 重构：返回结果而非直接修改分组）
     *
     * @param book 搜索或发现的第一本书
     * @param source 书源
     * @param isSearchBook true=搜索来源, false=发现来源
     * @return CheckBookDetailResult 各维度成功状态（失败维度=false）
     * @throws Exception 严重异常（非 ContentEmptyException/TocEmptyException）向上传播
     */
    private suspend fun checkBook(book: Book, source: BookSource, isSearchBook: Boolean = true): CheckBookDetailResult {
        var infoSuccess = false
        var categorySuccess = false
        var contentSuccess = false

        try {
            if (!CheckSource.checkInfo) {
                return CheckBookDetailResult()
            }
            // 校验详情
            markStage(source, CheckSourceStage.INFO)
            if (book.tocUrl.isBlank()) {
                WebBook.getBookInfoAwait(source, book)
            }
            infoSuccess = true

            if (!CheckSource.checkCategory || source.bookSourceType == BookSourceType.file) {
                return CheckBookDetailResult(infoSuccess = infoSuccess)
            }
            // 校验目录
            markStage(source, CheckSourceStage.CATALOG)
            val toc = sampleProbeChapters(
                WebBook.getChapterListAwait(source, book).getOrThrow()
            )
            if (toc.isEmpty()) {
                // NEW-21：目录条目**全为卷标题占位**时取样为空 ⇒ 判「目录失效」。
                // 此处若沿用 `first()` 会抛 NoSuchElementException，被上游 when 归为「网站失效」
                // ⇒ 可用源被写成死站。空目录本身由引擎抛 TocEmptyException，走外层 catch 同口径。
                return CheckBookDetailResult(
                    infoSuccess = infoSuccess,
                    categorySuccess = false,
                    contentSuccess = false
                )
            }
            categorySuccess = true

            val nextChapterUrl = toc.getOrNull(1)?.url ?: toc.first().url
            if (!CheckSource.checkContent) {
                return CheckBookDetailResult(infoSuccess = infoSuccess, categorySuccess = categorySuccess)
            }
            // 校验正文
            markStage(source, CheckSourceStage.CONTENT)
            WebBook.getContentAwait(
                bookSource = source,
                book = book,
                bookChapter = toc.first(),
                nextChapterUrl = nextChapterUrl,
                needSave = false
            )
            contentSuccess = true
        } catch (e: Exception) {
            // ContentEmptyException/TocEmptyException 不中断,标记对应维度失败
            when (e) {
                is ContentEmptyException -> contentSuccess = false
                is TocEmptyException -> categorySuccess = false
                else -> throw e  // 严重异常向上传播
            }
        }

        return CheckBookDetailResult(infoSuccess = infoSuccess, categorySuccess = categorySuccess, contentSuccess = contentSuccess)
    }

    /**
     * checkBook 结果数据类
     */
    private data class CheckBookDetailResult(
        val infoSuccess: Boolean = false,
        val categorySuccess: Boolean = false,
        val contentSuccess: Boolean = false
    )

    private fun upNotification() {
        notificationBuilder.setContentText(notificationMsg)
        notificationBuilder.setProgress(originSize, finishCount, false)
        postEvent(EventBus.CHECK_SOURCE, notificationMsg)
        notificationManager.notify(NotificationId.CheckSourceService, notificationBuilder.build())
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        notificationBuilder.setContentText(notificationMsg)
        notificationBuilder.setProgress(originSize, finishCount, false)
        postEvent(EventBus.CHECK_SOURCE, notificationMsg)
        startForeground(NotificationId.CheckSourceService, notificationBuilder.build())
    }

}

/**
 * 巡检失败归因 → 源分组名（**单一权威口径**，JVM 可测；`null` = 不加分组）。
 *
 * 语义（与改造前的内联 `when` 等价，改造只为可测 + 单源）：
 *  · 超时（`TimeoutCancellationException`）→ 「校验超时」
 *  · 脚本异常（`ScriptException` / `WrappedException`）→ 「js失效」
 *  · **业务异常（`NoStackTraceException` 及其子类，如 `TocEmptyException`/`ContentEmptyException`）→ null**：
 *    这类异常携带的是**引擎侧的明确结论**（目录为空 / 正文为空 / 规则为空…），上游已按维度打点，
 *    此处若再打「网站失效」会把可用源误标成死站（NEW-21 同族误判）
 *  · 其余（网络/解析类）→ 「网站失效」
 */
internal fun checkFailureGroupName(e: Throwable): String? =
    QualityReportApplier.failureKindGroup(QualityReportApplier.failureKindOf(e))

/**
 * 「存疑源」跳过口径（**与质检底座 [SuspectReason] 同源**，纯函数、JVM 可测）。
 *
 * 存疑＝静态可判定其失败**不可归因于源本身**（需登录 / 依赖 jsLib / 依赖源变量 / webView 型 /
 * 内网地址）⇒ 失败时判**跳过·存疑**，而非「网站失效」等确定性失败，避免误杀可用源。
 *
 * @return `null` = 非存疑源（按常规失败归因）；否则返回面向用户的跳过原因（如 `存疑（登录依赖）`）
 */
internal fun suspectSkipLabel(reasons: List<SuspectReason>): String? {
    if (reasons.isEmpty()) return null
    val text = reasons.joinToString("、") { reason ->
        when (reason) {
            SuspectReason.LOGIN -> "登录依赖"
            SuspectReason.JS_LIB -> "jsLib依赖"
            SuspectReason.SOURCE_VARIABLE -> "源变量依赖"
            SuspectReason.WEB_VIEW -> "webView型"
            SuspectReason.PRIVATE_ADDRESS -> "内网地址"
        }
    }
    return "存疑（$text）"
}