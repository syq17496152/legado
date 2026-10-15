package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.IntentData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.sortUrls
import io.legado.app.model.CheckRssSource
import io.legado.app.model.FailureKind
import io.legado.app.model.QualityReportApplier
import io.legado.app.model.SourceCheckGroups
import io.legado.app.model.SourceQualityChecker
import io.legado.app.model.RssCheckResult
import io.legado.app.model.SourceWeightCalculator
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.model.rss.Rss
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
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
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors

/**
 * 校验订阅源（参考 [CheckSourceService]）
 */
class CheckRssSourceService : BaseService() {
    private var threadCount = AppConfig.searchThreadCount
    private var searchCoroutine =
        Executors.newFixedThreadPool(threadCount).asCoroutineDispatcher()
    private var notificationMsg = appCtx.getString(R.string.service_starting)
    private var checkJob: Job? = null
    private var originSize = 0
    private var finishCount = 0
    // 校验结果暂存（用于去重）
    private val checkResults = mutableListOf<CheckResult>()

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdReadAloud)
            .setSmallIcon(R.drawable.ic_network_check)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.check_rss_source))
            .setContentIntent(
                activityPendingIntent<RssSourceActivity>("activity")
            )
            .addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<CheckRssSourceService>(IntentAction.stop)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> IntentData.get<List<String>>("checkRssSourceSelectedIds")?.let {
                check(it)
            }

            IntentAction.resume -> upNotification()
            IntentAction.stop -> stopSelf()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        searchCoroutine.close()
        postEvent(EventBus.CHECK_RSS_SOURCE_DONE, 0)
        notificationManager.cancel(NotificationId.CheckRssSourceService)
    }

    private fun check(ids: List<String>) {
        if (checkJob?.isActive == true) {
            toastOnUi("已有订阅源在校验,等完成后再试")
            return
        }
        checkJob = lifecycleScope.launch(searchCoroutine) {
            flow {
                for (origin in ids) {
                    runBlocking(IO) { appDb.rssSourceDao.getByKey(origin) }?.let {
                        emit(it)
                    }
                }
            }.onStart {
                originSize = ids.size
                finishCount = 0
                checkResults.clear()
                notificationMsg = getString(R.string.progress_show, "", 0, originSize)
                upNotification()
            }.onEachParallel(threadCount) {
                checkRssSource(it)
            }.onEach {
                finishCount++
                notificationMsg = getString(
                    R.string.progress_show,
                    it.sourceName,
                    finishCount,
                    originSize
                )
                upNotification()
                runBlocking(IO) { appDb.rssSourceDao.update(it) }
            }.onCompletion {
                // 校验完成后执行去重
                kotlin.runCatching {
                    dedupSources(checkResults.toList())
                }
                checkResults.clear()
                stopSelf()
            }.collect()
        }
    }

    private suspend fun checkRssSource(source: RssSource) {
        kotlin.runCatching {
            withTimeout(CheckRssSource.timeout) {
                val result = doCheckRssSource(source)
                synchronized(checkResults) {
                    checkResults.add(result)
                }
            }
        }.onSuccess {
            AppLog.put("订阅源校验成功: ${source.sourceUrl}")
        }.onFailure {
            currentCoroutineContext().ensureActive()
            // 存疑源（需登录 / jsLib 依赖 / 源变量依赖 / webView 型 / 内网地址）失败时**不写失效分组** ——
            // 与书源侧 `CheckSourceService` 及质检底座「存疑类不作为确定性失败证据（不误杀）」同口径；
            // 否则用户按「失效」筛选订阅源时会误删这些其实可用的源
            val suspectReasons = SourceQualityChecker.staticSuspectsOf(source)
            if (suspectReasons.isNotEmpty()) {
                AppLog.put("订阅源校验跳过：${suspectSkipLabel(suspectReasons)}")
            } else {
                // 归因口径单源（与书源侧共用 `checkFailureGroupName`）：业务异常（NoStackTraceException）
                // 自带引擎结论，不再额外打「网站失效」
                checkFailureGroupName(it)?.let { group -> source.addGroup(group) }
            }
            AppLog.put("订阅源校验失败: ${source.sourceUrl}", it)
        }
    }

    /**
     * 域名探测核心委托 SourceQualityChecker（AD-08 等价重构）
     * 行为保持：原用 CheckRssSource.timeout 按原值传入
     */
    private suspend fun isDomainReachable(domain: String): Boolean {
        return SourceQualityChecker.isDomainReachable(domain)
    }

    /**
     * 通过 AnalyzeUrl 发起真实请求校验域名可达性（委托公共组件）
     */
    private suspend fun checkDomainReachable(source: RssSource): Pair<Boolean, String?> {
        return SourceQualityChecker.checkDomainReachableRss(source, CheckRssSource.timeout)
    }

    private suspend fun doCheckRssSource(source: RssSource): CheckResult {
        var successCount = 0
        var realDomain: String? = null
        source.removeInvalidGroups()
        // 「重复源」不含「失效」子串 ⇒ removeInvalidGroups() 不会清它，必须每轮显式清一次，
        // 否则同域仅剩一个源后旧标记永久残留（与书源侧「规则为空」同族，见 SourceCheckGroups.RSS_DUPLICATE）
        source.removeGroup(SourceCheckGroups.RSS_DUPLICATE)

        var result = RssCheckResult()

        // 维度1: 域名可达性（前置条件，不可并发）
        if (CheckRssSource.checkDomain) {
            val domain = source.sourceUrl
            if (!domain.startsWith("http", ignoreCase = true)) {
                // 行为保持（旧内联）：打「域名失效」+ 权重归零 + 终止（组由共享编排统一回填）
                applyRssGroupsFromResult(source, result.copy(domainReachable = false))
                source.weight = 0
                throw NoStackTraceException("源地址不是http链接")
            }
            val (reachable, host) = when (CheckRssSource.domainCheckMode) {
                0 -> Pair(isDomainReachable(domain), null)
                else -> checkDomainReachable(source)
            }
            result = result.copy(domainReachable = reachable, realHost = host)
            if (reachable) {
                // 可达无需清「域名失效」组：本轮开头 removeInvalidGroups() 已清空历史失效组
                realDomain = host
                // 回填真实域名到lastHost,UI分组用此字段优先于源URL截取
                if (!host.isNullOrBlank()) source.lastHost = host
                successCount++
            } else {
                // 「域名失效」交共享编排统一回填（与体检侧同调，缺口2 续），随后终止本轮
                applyRssGroupsFromResult(source, result)
                source.weight = 0
                throw NoStackTraceException("源地址不可访问")
            }
        }

        // 域名校验关闭时，如启用去重则单独构造AnalyzeUrl提取域名（不发起请求）
        if (realDomain == null && CheckRssSource.enableDedup) {
            realDomain = kotlin.runCatching {
                val analyzeUrl = AnalyzeUrl(source.sourceUrl, source = source, ruleData = RuleData())
                URI(analyzeUrl.url).host
            }.getOrNull()
            // 同步回填lastHost,确保UI分组一致
            if (!realDomain.isNullOrBlank()) source.lastHost = realDomain
        }

        // 维度2-5: 列表/搜索/分类/正文 并发执行（Phase 6 重构）
        // 正文维度等待列表维度完成以复用结果,搜索/分类完全并发
        coroutineScope {
            val articlesDeferred = async {
                if (CheckRssSource.checkArticles && !source.ruleArticles.isNullOrBlank()) {
                    kotlin.runCatching {
                        val (result, _) = Rss.getArticlesAwait(
                            sortName = "",
                            sortUrl = source.sortUrl ?: source.sourceUrl,
                            rssSource = source,
                            page = 1
                        )
                        result
                    }.getOrElse { null }
                } else null
            }

            val searchDeferred = async {
                if (CheckRssSource.checkSearch && !source.searchUrl.isNullOrBlank()) {
                    kotlin.runCatching {
                        val (searchResults, _) = Rss.getArticlesAwait(
                            sortName = "",
                            sortUrl = source.searchUrl!!,
                            rssSource = source,
                            page = 1,
                            key = "测试"
                        )
                        searchResults
                    }.getOrElse { emptyList() }
                } else null
            }

            val sortDeferred = async {
                if (CheckRssSource.checkSort && !source.sortUrl.isNullOrBlank()) {
                    kotlin.runCatching {
                        source.sortUrls()
                    }.getOrElse { emptyList() }
                } else null
            }

            val contentDeferred = async {
                if (CheckRssSource.checkContent && !source.ruleContent.isNullOrBlank()) {
                    kotlin.runCatching {
                        // 等待列表维度完成以复用结果,避免重复请求
                        val articleList = articlesDeferred.await() ?: Rss.getArticlesAwait(
                            "", source.sortUrl ?: source.sourceUrl, source, 1
                        ).first
                        if (articleList.isNotEmpty()) {
                            val firstArticle = articleList.first()
                            if (firstArticle.link.isNotBlank()) {
                                val content = Rss.getContentAwait(
                                    firstArticle, source.ruleContent!!, source
                                )
                                content.isNotBlank()
                            } else false
                        } else false
                    }.getOrElse { false }
                } else null
            }

            val articlesResult = articlesDeferred.await()
            val searchResult = searchDeferred.await()
            val sortResult = sortDeferred.await()
            val contentResult = contentDeferred.await()

            // 收集本轮结论（分组回填与权重计算共用同一份结果）
            if (CheckRssSource.checkArticles && !source.ruleArticles.isNullOrBlank()) {
                val success = articlesResult != null && articlesResult.isNotEmpty()
                result = result.copy(
                    articlesChecked = true,
                    articlesSuccess = success,
                    articlesCount = if (success) (articlesResult?.size ?: 0) else 0
                )
                if (success) successCount++
            }
            if (CheckRssSource.checkSearch && !source.searchUrl.isNullOrBlank()) {
                val success = searchResult != null && searchResult.isNotEmpty()
                result = result.copy(
                    searchChecked = true,
                    searchSuccess = success,
                    searchResultCount = if (success) (searchResult?.size ?: 0) else 0
                )
                if (success) successCount++
            }
            if (CheckRssSource.checkSort && !source.sortUrl.isNullOrBlank()) {
                val success = sortResult != null && sortResult.isNotEmpty()
                result = result.copy(
                    sortChecked = true,
                    sortSuccess = success,
                    sortCount = if (success) (sortResult?.size ?: 0) else 0
                )
                if (success) successCount++
            }
            if (CheckRssSource.checkContent && !source.ruleContent.isNullOrBlank()) {
                val success = contentResult == true
                result = result.copy(contentChecked = true, contentSuccess = success)
                if (success) successCount++
            }
        }

        // 失效分组回填：唯一编排＝QualityReportApplier（与体检侧同源同调，缺口2 续）。
        // 旧写法此处有 14 处内联 add/removeGroup，靠"与体检侧逐字一致"维系 ⇒ 已收口。
        applyRssGroupsFromResult(source, result)

        // 权重计算（基于关键元素获取结果，Phase 6 重构）
        source.weight = SourceWeightCalculator.calculateRssWeightFromResult(
            result, CheckRssSource.checkDomain
        )
        return CheckResult(source, successCount, realDomain)
    }

    /**
     * 本轮探测结果 → 失效分组回填（**共享编排入口**，与书源侧 `applyGroupsFromResult` 同构）。
     *
     * `RssCheckResult` 经唯一适配器 [QualityReportApplier.rssResultToDimStates] 映射为维度态，
     * 再交 [QualityReportApplier.applyRssGroupsFromDims] 写组 —— 与体检侧
     * （`QualityReportApplier.applyRssReports`）走同一实现，杜绝"加组规则"两处漂移。
     * `failureKind = NONE`：本阶段是维度判定路径，异常归因由 `checkRssSource` 的 onFailure 处理。
     */
    private fun applyRssGroupsFromResult(source: RssSource, result: RssCheckResult) {
        QualityReportApplier.applyRssGroupsFromDims(
            source = source,
            dims = QualityReportApplier.rssResultToDimStates(result),
            failureKind = FailureKind.NONE,
            suspect = false,
            checkDomain = CheckRssSource.checkDomain,
        )
    }

    /**
     * 去重逻辑：按真实域名+type多维度分组，保留维度成功多的源，其余标记 [SourceCheckGroups.RSS_DUPLICATE]
     * （该标记每轮校验开头会被显式清除，见 doCheckRssSource，避免旧标记残留）
     */
    private suspend fun dedupSources(results: List<CheckResult>) {
        if (!CheckRssSource.enableDedup) return

        val byDomain = results.filter { !it.realDomain.isNullOrBlank() }
            .groupBy { it.realDomain!! }

        val toUpdate = mutableListOf<RssSource>()
        byDomain.forEach { (_, list) ->
            val byType = list.groupBy { it.source.type }
            byType.forEach { (_, sameTypeList) ->
                if (sameTypeList.size > 1) {
                    val sorted = sameTypeList.sortedByDescending { it.successCount }
                    sorted.drop(1).forEach { result ->
                        result.source.addGroup(SourceCheckGroups.RSS_DUPLICATE)
                        toUpdate.add(result.source)
                    }
                }
            }
        }

        if (toUpdate.isNotEmpty()) {
            appDb.rssSourceDao.update(*toUpdate.toTypedArray())
        }
    }

    private fun upNotification() {
        notificationBuilder.setContentText(notificationMsg)
        notificationBuilder.setProgress(originSize, finishCount, false)
        postEvent(EventBus.CHECK_RSS_SOURCE, notificationMsg)
        notificationManager.notify(NotificationId.CheckRssSourceService, notificationBuilder.build())
    }

    override fun startForegroundNotification() {
        notificationBuilder.setContentText(notificationMsg)
        notificationBuilder.setProgress(originSize, finishCount, false)
        postEvent(EventBus.CHECK_RSS_SOURCE, notificationMsg)
        startForeground(NotificationId.CheckRssSourceService, notificationBuilder.build())
    }

    /**
     * 校验结果数据结构（运行时中间态，不持久化）
     * @param source 订阅源
     * @param successCount 校验成功维度数
     * @param realDomain 从AnalyzeUrl处理后的最终URL提取的host
     */
    private data class CheckResult(
        val source: RssSource,
        val successCount: Int,
        val realDomain: String?
    )
}
