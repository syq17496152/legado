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
import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.IntentData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.BookCheckResult
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.model.SourceWeightCalculator
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
            }.onCompletion {
                stopSelf()
            }.collect()
        }
    }

    private suspend fun checkSource(source: BookSource) {
        kotlin.runCatching {
            withTimeout(CheckSource.timeout) {
                doCheckSource(source)
            }
        }.onSuccess {
            Debug.updateFinalMessage(source.bookSourceUrl, "校验成功")
        }.onFailure {
            currentCoroutineContext().ensureActive()
            when (it) {
                is TimeoutCancellationException -> source.addGroup("校验超时")
                is ScriptException, is WrappedException -> source.addGroup("js失效")
                !is NoStackTraceException -> source.addGroup("网站失效")
            }
            if (CheckSource.wSourceComment) {
                source.addErrorComment(it)
            }
            Debug.updateFinalMessage(source.bookSourceUrl, "校验失败:${it.localizedMessage}")
        }
        source.respondTime = Debug.getRespondTime(source.bookSourceUrl)
    }

    private suspend fun isDomainReachable(domain: String): Boolean {
        return kotlin.runCatching {
            withTimeout(2000) {
                val url = URI(domain.substringBefore("#"))
                val port = url.port.takeIf { it > 0 } ?: 80
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(url.host, port), 1600)
                    true
                }
            }
        }.getOrDefault(false)
    }

    /**
     * 通过 AnalyzeUrl 发起真实请求校验域名可达性
     * 支持 jslib/注释/#规避/空格等复杂源URL
     * @return Pair(是否可达, 真实域名host) - host用于回填source.lastHost,UI分组优先使用
     */
    private suspend fun checkDomainReachable(source: BookSource): Pair<Boolean, String?> {
        return kotlin.runCatching {
            withTimeout(30000) {
                val analyzeUrl = AnalyzeUrl(
                    source.bookSourceUrl,
                    source = source,
                    ruleData = RuleData(),
                    coroutineContext = currentCoroutineContext()
                )
                analyzeUrl.getStrResponseAwait()
                // 记录真实域名(从AnalyzeUrl处理后的最终URL提取,支持jslib/注释/#规避)
                val realDomain = kotlin.runCatching { URI(analyzeUrl.url).host }.getOrNull()
                Pair(true, realDomain)
            }
        }.getOrDefault(Pair(false, null))
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
                source.removeGroup("域名失效")
                // 回填真实域名到lastHost,UI分组用此字段优先于源URL截取
                if (!host.isNullOrBlank()) source.lastHost = host
            } else {
                source.addGroup("域名失效")
                source.weight = 0
                throw NoStackTraceException("源地址不可访问")
            }
        }

        // 维度2+3: 搜索和发现并发执行（Phase 6 重构）
        // 各维度收集结果到局部变量,完成后串行更新分组(避免竞态)
        coroutineScope {
            val searchDeferred = async {
                if (CheckSource.checkSearch) {
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

            // 串行更新分组(避免竞态) - 搜索维度
            if (searchResult.searchChecked) {
                if (searchResult.searchUrlEmpty) {
                    source.addGroup("搜索链接规则为空")
                } else {
                    source.removeGroup("搜索链接规则为空")
                    if (searchResult.searchSuccess) {
                        source.removeGroup("搜索失效")
                        // checkBook 结果分组（搜索来源）
                        if (searchResult.searchCategorySuccess) {
                            source.removeGroup("搜索目录失效")
                        } else {
                            source.addGroup("搜索目录失效")
                        }
                        if (searchResult.searchContentSuccess) {
                            source.removeGroup("搜索正文失效")
                        } else {
                            source.addGroup("搜索正文失效")
                        }
                    } else {
                        source.addGroup("搜索失效")
                    }
                }
            }
            // 串行更新分组 - 发现维度
            if (discoveryResult.discoveryChecked) {
                if (discoveryResult.discoveryRuleEmpty) {
                    source.addGroup("发现规则为空")
                } else {
                    source.removeGroup("发现规则为空")
                    if (discoveryResult.discoverySuccess) {
                        source.removeGroup("发现失效")
                        // checkBook 结果分组（发现来源）
                        if (discoveryResult.discoveryCategorySuccess) {
                            source.removeGroup("发现目录失效")
                        } else {
                            source.addGroup("发现目录失效")
                        }
                        if (discoveryResult.discoveryContentSuccess) {
                            source.removeGroup("发现正文失效")
                        } else {
                            source.addGroup("发现正文失效")
                        }
                    } else {
                        source.addGroup("发现失效")
                    }
                }
            }

            // 合并结果用于权重计算
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
            if (book.tocUrl.isBlank()) {
                WebBook.getBookInfoAwait(source, book)
            }
            infoSuccess = true

            if (!CheckSource.checkCategory || source.bookSourceType == BookSourceType.file) {
                return CheckBookDetailResult(infoSuccess = infoSuccess)
            }
            // 校验目录
            val toc = WebBook.getChapterListAwait(source, book).getOrThrow().asSequence()
                .filter { !(it.isVolume && it.url.startsWith(it.title)) }
                .take(2)
                .toList()
            categorySuccess = true

            val nextChapterUrl = toc.getOrNull(1)?.url ?: toc.first().url
            if (!CheckSource.checkContent) {
                return CheckBookDetailResult(infoSuccess = infoSuccess, categorySuccess = categorySuccess)
            }
            // 校验正文
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