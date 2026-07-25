package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.script.ScriptException
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
            when (it) {
                is TimeoutCancellationException -> source.addGroup("校验超时")
                is ScriptException, is WrappedException -> source.addGroup("js失效")
                !is NoStackTraceException -> source.addGroup("网站失效")
            }
            AppLog.put("订阅源校验失败: ${source.sourceUrl}", it)
        }
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
     */
    private suspend fun checkDomainReachable(source: RssSource): Pair<Boolean, String?> {
        return kotlin.runCatching {
            withTimeout(CheckRssSource.timeout) {
                val analyzeUrl = AnalyzeUrl(
                    source.sourceUrl,
                    source = source,
                    ruleData = RuleData(),
                    coroutineContext = currentCoroutineContext()
                )
                analyzeUrl.getStrResponseAwait()
                // 记录真实域名（复用AnalyzeUrl处理结果，供去重复用）
                val realDomain = kotlin.runCatching { URI(analyzeUrl.url).host }.getOrNull()
                Pair(true, realDomain)
            }
        }.getOrDefault(Pair(false, null))
    }

    private suspend fun doCheckRssSource(source: RssSource): CheckResult {
        var successCount = 0
        var realDomain: String? = null
        source.removeInvalidGroups()

        var result = RssCheckResult()

        // 维度1: 域名可达性（前置条件，不可并发）
        if (CheckRssSource.checkDomain) {
            val domain = source.sourceUrl
            if (!domain.startsWith("http", ignoreCase = true)) {
                source.addGroup("域名失效")
                source.weight = 0
                throw NoStackTraceException("源地址不是http链接")
            }
            val (reachable, host) = when (CheckRssSource.domainCheckMode) {
                0 -> Pair(isDomainReachable(domain), null)
                else -> checkDomainReachable(source)
            }
            result = result.copy(domainReachable = reachable, realHost = host)
            if (reachable) {
                source.removeGroup("域名失效")
                realDomain = host
                // 回填真实域名到lastHost,UI分组用此字段优先于源URL截取
                if (!host.isNullOrBlank()) source.lastHost = host
                successCount++
            } else {
                source.addGroup("域名失效")
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

            // 串行更新分组(避免竞态) + 收集结果
            if (CheckRssSource.checkArticles && !source.ruleArticles.isNullOrBlank()) {
                result = result.copy(articlesChecked = true)
                if (articlesResult != null && articlesResult.isNotEmpty()) {
                    source.removeGroup("列表失效")
                    result = result.copy(articlesSuccess = true, articlesCount = articlesResult.size)
                    successCount++
                } else {
                    source.addGroup("列表失效")
                }
            }
            if (CheckRssSource.checkSearch && !source.searchUrl.isNullOrBlank()) {
                result = result.copy(searchChecked = true)
                if (searchResult != null && searchResult.isNotEmpty()) {
                    source.removeGroup("搜索失效")
                    result = result.copy(searchSuccess = true, searchResultCount = searchResult.size)
                    successCount++
                } else {
                    source.addGroup("搜索失效")
                }
            }
            if (CheckRssSource.checkSort && !source.sortUrl.isNullOrBlank()) {
                result = result.copy(sortChecked = true)
                if (sortResult != null && sortResult.isNotEmpty()) {
                    source.removeGroup("分类失效")
                    result = result.copy(sortSuccess = true, sortCount = sortResult.size)
                    successCount++
                } else {
                    source.addGroup("分类失效")
                }
            }
            if (CheckRssSource.checkContent && !source.ruleContent.isNullOrBlank()) {
                result = result.copy(contentChecked = true)
                if (contentResult == true) {
                    source.removeGroup("正文失效")
                    result = result.copy(contentSuccess = true)
                    successCount++
                } else {
                    source.addGroup("正文失效")
                }
            }
        }

        // 权重计算（基于关键元素获取结果，Phase 6 重构）
        source.weight = SourceWeightCalculator.calculateRssWeightFromResult(
            result, CheckRssSource.checkDomain
        )
        return CheckResult(source, successCount, realDomain)
    }

    /**
     * 去重逻辑：按真实域名+type多维度分组，保留维度成功多的源，其余标记"重复源"
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
                        result.source.addGroup("重复源")
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
