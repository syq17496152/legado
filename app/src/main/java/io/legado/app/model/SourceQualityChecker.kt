package io.legado.app.model

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.CacheManager
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.model.rss.Rss
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import com.script.ScriptException
import org.mozilla.javascript.WrappedException
import java.util.concurrent.ConcurrentHashMap
import splitties.init.appCtx

/**
 * 质量校验维度（评分模板与判定共用）
 */
enum class QualityDim {
    DOMAIN, SEARCH, DISCOVERY, INFO, CATEGORY, CONTENT,
    ARTICLES, RSS_SEARCH, RSS_SORT, FIRST_SCREEN, EPISODES
}

/**
 * 维度三态（评分 v2：字段缺失合法=NOT_APPLICABLE 不计分，未校验=NOT_CHECKED 不计分）
 */
enum class DimState { PASS, FAIL, NOT_APPLICABLE, NOT_CHECKED }

/**
 * 存疑五类（探测失败不作为导入过滤证据；宽松/标准档强制放行）
 */
enum class SuspectReason {
    LOGIN,          // 登录依赖（loginCheckJs/loginUi，未登录探测必失败）
    JS_LIB,         // jsLib 依赖（规则引用 jsLib 函数，库外探测缺依赖）
    SOURCE_VARIABLE, // 源变量依赖（{{source.getVariable()}} 拼接，无变量时 URL 残缺）
    WEB_VIEW,       // useWebView/startHtml 型（headless 校验易失败或无规则可测）
    PRIVATE_ADDRESS // 内网地址（防被恶意集合利用做内网扫描，跳过探测不误杀局域网源）
}

/**
 * 失败分类（供现有校验服务映射分组；组件自身零写库）
 */
enum class FailureKind { TIMEOUT, SCRIPT, NETWORK, EMPTY, NONE }

/**
 * 维度结果
 */
data class DimResult(
    val state: DimState,
    val resultCount: Int = 0,
    val durationMs: Long = 0,
    val evidence: String = "",
    // fail 细分：true=网络/超时异常（宽松档放行），false=规则解析明确 0 结果（确定性失败）
    val failedByException: Boolean = false
)

/**
 * 源质量报告（评分 v2：形态模板 + 已校验维度归一化）
 */
data class SourceQualityReport(
    val sourceUrl: String,
    val sourceType: Int,
    val checkedDepth: Int,                              // 1=L1 / 2=L2 / 3=L3
    val dimensions: Map<QualityDim, DimResult>,
    val suspect: Boolean = false,
    val suspectReasons: List<SuspectReason> = emptyList(),
    val failureKind: FailureKind = FailureKind.NONE,
    val deterministicFail: Boolean = false,             // 宽松档过滤证据（结构残缺/域名不可达/重试后仍0结果）
    val score: Int = 0,
    val coverage: Float = 0f
)

/**
 * 探测参数（公共组件唯一入口参数，AD-09：CheckSource/ImportCheck 仅作预设档映射）
 */
data class ProbeOptions(
    val concurrency: Int = 8,
    val timeout: Long = 10_000L,
    val retry: Boolean = true,
    val depth: CheckDepth = CheckDepth.L2,
    val strictness: CheckStrictness = CheckStrictness.LENIENT,
    val keyword: String = CheckSource.keyword,
    val retryKeyword: String = "一",
    val checkDomain: Boolean = true
)

enum class CheckStrictness { LENIENT, STANDARD, STRICT }
enum class CheckDepth { L1, L2, L3 }

/**
 * 会话级探测基础设施（每轮导入/体检新建一个实例）
 * 并发 Semaphore + host 域名探测缓存 + 同 URL 去重 + 同 host 试采限额
 */
class SourceQualitySession(val options: ProbeOptions) {

    val semaphore = Semaphore(options.concurrency)

    // host 级域名探测缓存（会话内）
    private val domainCache = ConcurrentHashMap<String, Boolean>()

    // 同 host 试采计数（限 8 次/会话，超限按首次结果推断）
    private val hostProbeCount = ConcurrentHashMap<String, Int>()
    private val hostProbeResult = ConcurrentHashMap<String, Boolean>()

    // 同 URL 去重（同批次同 URL 复用结果）
    private val urlReportCache = ConcurrentHashMap<String, SourceQualityReport>()

    fun domainCacheKey(url: String): String {
        return kotlin.runCatching { URI(url.substringBefore("#")).host }.getOrNull() ?: url
    }

    fun getDomainCached(url: String): Boolean? = domainCache[domainCacheKey(url)]

    fun putDomainCached(url: String, reachable: Boolean) {
        domainCache[domainCacheKey(url)] = reachable
    }

    /**
     * 同 host 试采限额：返回 null 表示超限（调用方按该 host 首次结果推断），否则返回是否允许本次真实试采
     */
    fun acquireHostProbe(host: String): Boolean? {
        val count = hostProbeCount.merge(host, 1) { a, b -> a + b } ?: 1
        if (count > HOST_PROBE_LIMIT) return hostProbeResult[host]
        return null
    }

    fun recordHostProbeResult(host: String, success: Boolean) {
        hostProbeResult[host] = success
    }

    fun getUrlReport(url: String): SourceQualityReport? = urlReportCache[url]

    fun putUrlReport(url: String, report: SourceQualityReport) {
        urlReportCache[url] = report
    }

    companion object {
        const val HOST_PROBE_LIMIT = 8
    }
}

/**
 * 源质量校验公共组件（单源探测核心）
 *
 * 设计约束（AD-01/AD-08）：
 * - 零写库副作用：返回 SourceQualityReport，落库/分组/weight 由调用方决定
 * - 禁止 import io.legado.app.service（依赖方向：service→model）
 * - 网络探测核心平移自 CheckSourceService.checkBook/isDomainReachable/checkDomainReachable（Phase 6 纯探测形态）
 */
object SourceQualityChecker {

    // ============ 会话级探测入口 ============

    suspend fun checkBookSource(
        source: BookSource,
        session: SourceQualitySession
    ): SourceQualityReport {
        session.getUrlReport(source.bookSourceUrl)?.let { return it }
        val report = session.semaphore.withPermit {
            withTimeoutOrNull(session.options.timeout * 3) {
                doCheckBookSource(source, session)
            } ?: timeoutReport(source.bookSourceUrl, source.bookSourceType, session.options)
        }
        session.putUrlReport(source.bookSourceUrl, report)
        return report
    }

    suspend fun checkRssSource(
        source: RssSource,
        session: SourceQualitySession
    ): SourceQualityReport {
        session.getUrlReport(source.sourceUrl)?.let { return it }
        val report = session.semaphore.withPermit {
            withTimeoutOrNull(session.options.timeout * 3) {
                doCheckRssSource(source, session)
            } ?: timeoutReport(source.sourceUrl, source.type, session.options)
        }
        session.putUrlReport(source.sourceUrl, report)
        return report
    }

    private fun timeoutReport(url: String, type: Int, options: ProbeOptions): SourceQualityReport {
        // 整源硬超时：全维度标异常失败（宽松档存疑放行）
        val dims = SourceQualityScorer.selectBookTemplate(type, hasSearch = true, hasDiscovery = false)
            .associate { it.dim to DimResult(DimState.FAIL, failedByException = true, evidence = "校验超时") }
        val report = SourceQualityReport(
            sourceUrl = url,
            sourceType = type,
            checkedDepth = options.depth.ordinal + 1,
            dimensions = dims,
            failureKind = FailureKind.TIMEOUT
        )
        return SourceQualityScorer.score(
            report, SourceQualityScorer.selectBookTemplate(type, hasSearch = true, hasDiscovery = false)
        )
    }

    // ============ L1 静态结构检查（0 网络成本，导入解析后即时标记） ============

    /**
     * 书源 L1：bookSourceUrl 空，或（searchUrl 空且非 enabledExplore）→ 结构残缺
     * file/video 型仅要求 bookSourceUrl + 搜索或发现入口存在
     */
    fun l1StaticCheckBookSource(source: BookSource): SourceQualityReport {
        val dims = mutableMapOf<QualityDim, DimResult>()
        var structFail = false
        if (source.bookSourceUrl.isBlank()) {
            structFail = true
            dims[QualityDim.DOMAIN] = DimResult(DimState.FAIL, evidence = "源地址为空")
        } else {
            val hasSearch = !source.searchUrl.isNullOrBlank()
            val hasDiscovery = source.enabledExplore && !source.exploreUrl.isNullOrBlank()
            if (!hasSearch && !hasDiscovery) {
                structFail = true
                dims[QualityDim.SEARCH] = DimResult(DimState.FAIL, evidence = "搜索和发现规则均为空")
            }
        }
        return buildStaticReport(
            sourceUrl = source.bookSourceUrl,
            sourceType = source.bookSourceType,
            dims = dims,
            structFail = structFail,
            suspectReasons = detectStaticSuspects(source.searchUrl, source.jsLib, source.loginUrl, source.loginCheckJs, source.loginUi)
        )
    }

    /**
     * 订阅源 L1：sourceUrl 空 → 结构残缺；列表规则缺失不判残缺（默认 XML/webview 型合法）
     */
    fun l1StaticCheckRssSource(source: RssSource): SourceQualityReport {
        val dims = mutableMapOf<QualityDim, DimResult>()
        var structFail = false
        if (source.sourceUrl.isBlank()) {
            structFail = true
            dims[QualityDim.DOMAIN] = DimResult(DimState.FAIL, evidence = "源地址为空")
        }
        return buildStaticReport(
            sourceUrl = source.sourceUrl,
            sourceType = source.type,
            dims = dims,
            structFail = structFail,
            suspectReasons = detectStaticSuspects(source.sortUrl, source.jsLib, source.loginUrl, source.loginCheckJs, source.loginUi, isRss = source)
        )
    }

    private fun buildStaticReport(
        sourceUrl: String,
        sourceType: Int,
        dims: Map<QualityDim, DimResult>,
        structFail: Boolean,
        suspectReasons: List<SuspectReason>
    ): SourceQualityReport {
        val report = SourceQualityReport(
            sourceUrl = sourceUrl,
            sourceType = sourceType,
            checkedDepth = 1,
            dimensions = dims,
            suspect = suspectReasons.isNotEmpty(),
            suspectReasons = suspectReasons,
            deterministicFail = structFail
        )
        // L1 静态报告无联网维度，score/coverage 保持 0（列表 FILTERED 态展示原因而非分数）
        return report
    }

    /**
     * 静态 suspect 检测（登录/jsLib/源变量/webView 四类；内网地址在探测时判定）
     */
    private fun detectStaticSuspects(
        ruleUrl: String?,
        jsLib: String?,
        loginUrl: String?,
        loginCheckJs: String?,
        loginUi: String?,
        isRss: RssSource? = null
    ): List<SuspectReason> {
        val reasons = mutableListOf<SuspectReason>()
        if (!loginCheckJs.isNullOrBlank() || !loginUrl.isNullOrBlank() || !loginUi.isNullOrBlank()) {
            reasons.add(SuspectReason.LOGIN)
        }
        val rule = ruleUrl.orEmpty()
        // jsLib 依赖近似判定：源声明了 jsLib 且规则内嵌 JS 求值
        if (!jsLib.isNullOrBlank() && (rule.contains("{{") || rule.contains("@js:") || rule.contains("<js"))) {
            reasons.add(SuspectReason.JS_LIB)
        }
        if (rule.contains("source.getVariable") || rule.contains("{{source.")) {
            reasons.add(SuspectReason.SOURCE_VARIABLE)
        }
        if (isRss != null && (!isRss.startHtml.isNullOrBlank() || isRss.loadWithBaseUrl)) {
            reasons.add(SuspectReason.WEB_VIEW)
        }
        if (rule.contains("\"useWebView\":true") || rule.contains("'useWebView':true")) {
            reasons.add(SuspectReason.WEB_VIEW)
        }
        return reasons
    }

    // ============ L2/L3 联网校验（书源） ============

    private suspend fun doCheckBookSource(
        source: BookSource,
        session: SourceQualitySession
    ): SourceQualityReport {
        val options = session.options
        // L1 静态先行
        val l1 = l1StaticCheckBookSource(source)
        if (l1.deterministicFail) return l1

        val suspectReasons = l1.suspectReasons.toMutableList()
        val dims = mutableMapOf<QualityDim, DimResult>()
        var failureKind = FailureKind.NONE

        // 域名探测（host 级缓存 + 内网跳过）
        val domainState = probeDomain(source.bookSourceUrl, source, session, suspectReasons, dims)
        if (domainState != DimState.PASS) {
            // 域名不可达/跳过：后续维度全部 NOT_CHECKED
            fillNotChecked(dims, bookApplicableDims(source))
            return finalize(source, dims, suspectReasons, failureKind, options,
                domainFail = domainState == DimState.FAIL)
        }

        // 搜索 + 发现并发试采（Phase 6 形态平移）
        val searchResult = coroutineScope {
            val searchDeferred = async {
                probeBookSearch(source, session, dims, suspectReasons)
            }
            val discoveryDeferred = async {
                probeBookDiscovery(source, session, dims, suspectReasons)
            }
            Pair(searchDeferred.await(), discoveryDeferred.await())
        }

        // L3 深度：详情/目录/正文（取首个可用来源试读）
        if (options.depth == CheckDepth.L3) {
            val book = searchResult.first ?: searchResult.second
            if (book != null) {
                probeBookDetail(source, book, dims)
            }
        }

        return finalize(source, dims, suspectReasons, failureKind, options, domainFail = false)
    }

    private suspend fun probeDomain(
        url: String,
        source: BookSource,
        session: SourceQualitySession,
        suspectReasons: MutableList<SuspectReason>,
        dims: MutableMap<QualityDim, DimResult>
    ): DimState {
        if (isPrivateAddress(url)) {
            if (!suspectReasons.contains(SuspectReason.PRIVATE_ADDRESS)) {
                suspectReasons.add(SuspectReason.PRIVATE_ADDRESS)
            }
            dims[QualityDim.DOMAIN] = DimResult(DimState.NOT_CHECKED, evidence = "内网地址，跳过探测")
            return DimState.NOT_CHECKED
        }
        // host 级缓存命中
        session.getDomainCached(url)?.let { reachable ->
            val r = if (reachable) DimState.PASS else DimState.FAIL
            dims[QualityDim.DOMAIN] = if (reachable) {
                DimResult(DimState.PASS, evidence = "域名可达(缓存)")
            } else {
                DimResult(DimState.FAIL, evidence = "网址打不开(缓存)")
            }
            return r
        }
        // Socket 快探（平移自 CheckSourceService.isDomainReachable）
        var reachable = isDomainReachable(url)
        if (!reachable) {
            // 真实请求复探（平移自 checkDomainReachable，超时受 options 硬控）
            val (ok, host) = checkDomainReachable(source, session.options.timeout)
            reachable = ok
            if (!host.isNullOrBlank() && source.lastHost.isNullOrBlank()) {
                source.lastHost = host
            }
        }
        session.putDomainCached(url, reachable)
        dims[QualityDim.DOMAIN] = if (reachable) {
            DimResult(DimState.PASS, evidence = "域名可达")
        } else {
            DimResult(DimState.FAIL, failedByException = true, evidence = "网址打不开")
        }
        return if (reachable) DimState.PASS else DimState.FAIL
    }

    /**
     * 搜索试采：返回首条搜索结果（供 L3 详情/目录/正文试读），null=未测或失败
     * 同 host 试采限额：超限按该 host 首次试采结果推断（防大集合同 host 请求风暴）
     */
    private suspend fun probeBookSearch(
        source: BookSource,
        session: SourceQualitySession,
        dims: MutableMap<QualityDim, DimResult>,
        suspectReasons: MutableList<SuspectReason>
    ): io.legado.app.data.entities.Book? {
        if (source.searchUrl.isNullOrBlank()) {
            // 纯发现源合法：搜索维度 NOT_APPLICABLE
            dims[QualityDim.SEARCH] = DimResult(DimState.NOT_APPLICABLE, evidence = "无搜索规则")
            return null
        }
        val host = session.domainCacheKey(source.bookSourceUrl)
        val limitedResult = session.acquireHostProbe(host)
        if (limitedResult != null) {
            // 超限：按该 host 首次试采结果推断（存疑类，不作为确定性失败证据）
            dims[QualityDim.SEARCH] = if (limitedResult) {
                DimResult(DimState.PASS, evidence = "同站已验通过(推断)")
            } else {
                DimResult(DimState.FAIL, failedByException = true, evidence = "同站首测未过(推断)")
            }
            return null
        }
        val start = System.currentTimeMillis()
        val result = kotlin.runCatching {
            var books = WebBook.searchBookAwait(source, session.options.keyword)
            if (books.isEmpty() && session.options.retry) {
                books = WebBook.searchBookAwait(source, session.options.retryKeyword)
            }
            books
        }
        session.recordHostProbeResult(host, result.isSuccess && result.getOrDefault(emptyList()).isNotEmpty())
        dims[QualityDim.SEARCH] = when {
            result.isSuccess -> {
                val books = result.getOrDefault(emptyList())
                if (books.isNotEmpty()) {
                    DimResult(DimState.PASS, resultCount = books.size, durationMs = System.currentTimeMillis() - start)
                } else {
                    DimResult(DimState.FAIL, resultCount = 0, durationMs = System.currentTimeMillis() - start, evidence = "搜索不到结果")
                }
            }
            else -> DimResult(
                DimState.FAIL,
                durationMs = System.currentTimeMillis() - start,
                failedByException = true,
                evidence = evidenceFromException(result.exceptionOrNull())
            )
        }
        return result.getOrNull()?.firstOrNull()?.toBook()
    }

    /**
     * 发现试采：返回首条发现结果（供 L3）
     */
    private suspend fun probeBookDiscovery(
        source: BookSource,
        session: SourceQualitySession,
        dims: MutableMap<QualityDim, DimResult>,
        suspectReasons: MutableList<SuspectReason>
    ): io.legado.app.data.entities.Book? {
        if (source.exploreUrl.isNullOrBlank()) {
            dims[QualityDim.DISCOVERY] = DimResult(DimState.NOT_APPLICABLE, evidence = "无发现规则")
            return null
        }
        val start = System.currentTimeMillis()
        val url = source.exploreKinds().firstOrNull { !it.url.isNullOrBlank() }?.url
        if (url.isNullOrBlank()) {
            dims[QualityDim.DISCOVERY] = DimResult(DimState.NOT_APPLICABLE, evidence = "发现入口为空")
            return null
        }
        val result = kotlin.runCatching {
            var books = WebBook.exploreBookAwait(source, url)
            if (books.isEmpty() && session.options.retry) {
                books = WebBook.exploreBookAwait(source, url)
            }
            books
        }
        dims[QualityDim.DISCOVERY] = when {
            result.isSuccess -> {
                val books = result.getOrDefault(emptyList())
                if (books.isNotEmpty()) {
                    DimResult(DimState.PASS, resultCount = books.size, durationMs = System.currentTimeMillis() - start)
                } else {
                    DimResult(DimState.FAIL, durationMs = System.currentTimeMillis() - start, evidence = "发现页无内容")
                }
            }
            else -> DimResult(
                DimState.FAIL,
                durationMs = System.currentTimeMillis() - start,
                failedByException = true,
                evidence = evidenceFromException(result.exceptionOrNull())
            )
        }
        return result.getOrNull()?.firstOrNull()?.toBook()
    }

    /**
     * L3 深度：详情/目录/正文试读（平移自 CheckSourceService.checkBook，返回结果不写分组）
     */
    private suspend fun probeBookDetail(
        source: BookSource,
        book: io.legado.app.data.entities.Book,
        dims: MutableMap<QualityDim, DimResult>
    ) {
        if (book.tocUrl.isBlank()) {
            val info = kotlin.runCatching { WebBook.getBookInfoAwait(source, book) }
            dims[QualityDim.INFO] = if (info.isSuccess) {
                DimResult(DimState.PASS)
            } else {
                DimResult(DimState.FAIL, failedByException = true, evidence = "详情页打开失败")
            }
            if (info.isFailure) return
        } else {
            dims[QualityDim.INFO] = DimResult(DimState.PASS)
        }
        if (source.bookSourceType == BookSourceType.file) {
            dims[QualityDim.CATEGORY] = DimResult(DimState.NOT_APPLICABLE, evidence = "文件源无目录")
            dims[QualityDim.CONTENT] = DimResult(DimState.NOT_APPLICABLE, evidence = "文件源无正文")
            return
        }
        val tocResult = kotlin.runCatching {
            WebBook.getChapterListAwait(source, book).getOrThrow().asSequence()
                .filter { !(it.isVolume && it.url.startsWith(it.title)) }
                .take(2)
                .toList()
        }
        val toc = tocResult.getOrNull()
        if (toc.isNullOrEmpty()) {
            dims[QualityDim.CATEGORY] = if (tocResult.exceptionOrNull() is TocEmptyException) {
                DimResult(DimState.FAIL, evidence = "目录为空")
            } else {
                DimResult(DimState.FAIL, failedByException = true, evidence = "目录获取失败")
            }
            return
        }
        dims[QualityDim.CATEGORY] = DimResult(DimState.PASS, resultCount = toc.size)
        val nextChapterUrl = toc.getOrNull(1)?.url ?: toc.first().url
        val contentResult = kotlin.runCatching {
            WebBook.getContentAwait(
                bookSource = source,
                book = book,
                bookChapter = toc.first(),
                nextChapterUrl = nextChapterUrl,
                needSave = false
            )
        }
        dims[QualityDim.CONTENT] = if (contentResult.isSuccess) {
            DimResult(DimState.PASS)
        } else {
            when (contentResult.exceptionOrNull()) {
                is ContentEmptyException -> DimResult(DimState.FAIL, evidence = "正文为空")
                else -> DimResult(DimState.FAIL, failedByException = true, evidence = "正文获取失败")
            }
        }
    }

    // ============ L2/L3 联网校验（订阅源） ============

    private suspend fun doCheckRssSource(
        source: RssSource,
        session: SourceQualitySession
    ): SourceQualityReport {
        val options = session.options
        val l1 = l1StaticCheckRssSource(source)
        if (l1.deterministicFail) return l1

        val suspectReasons = l1.suspectReasons.toMutableList()
        val dims = mutableMapOf<QualityDim, DimResult>()

        // webview 型（startHtml/loadWithBaseUrl）：无规则可测，除域名外全 NOT_CHECKED + suspect 放行
        if (suspectReasons.contains(SuspectReason.WEB_VIEW) && source.ruleArticles.isNullOrBlank()) {
            val domainState = probeDomainRss(source.sourceUrl, session, suspectReasons, dims)
            fillNotChecked(dims, listOf(QualityDim.ARTICLES, QualityDim.RSS_SEARCH, QualityDim.RSS_SORT, QualityDim.CONTENT, QualityDim.EPISODES, QualityDim.FIRST_SCREEN))
            return finalize(source, dims, suspectReasons, FailureKind.NONE, options,
                domainFail = domainState == DimState.FAIL)
        }

        val domainState = probeDomainRss(source.sourceUrl, session, suspectReasons, dims)
        if (domainState != DimState.PASS) {
            fillNotChecked(dims, rssApplicableDims(source))
            return finalize(source, dims, suspectReasons, FailureKind.NONE, options,
                domainFail = domainState == DimState.FAIL)
        }

        // 列表试采（平移自 CheckRssSourceService：Rss.getArticlesAwait 首入口）
        val start = System.currentTimeMillis()
        val entryUrl = source.sortUrl?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: source.sourceUrl
        val articlesResult = kotlin.runCatching {
            Rss.getArticlesAwait(sortName = "", sortUrl = entryUrl, rssSource = source, page = 1)
        }
        dims[QualityDim.ARTICLES] = when {
            articlesResult.isSuccess -> {
                val count = articlesResult.getOrThrow().first.size
                if (count > 0) {
                    DimResult(DimState.PASS, resultCount = count, durationMs = System.currentTimeMillis() - start)
                } else {
                    DimResult(DimState.FAIL, durationMs = System.currentTimeMillis() - start, evidence = "列表无内容")
                }
            }
            else -> DimResult(
                DimState.FAIL,
                durationMs = System.currentTimeMillis() - start,
                failedByException = true,
                evidence = evidenceFromException(articlesResult.exceptionOrNull())
            )
        }

        // 分类维度：sortUrl 空合法（单入口型）
        if (source.sortUrl.isNullOrBlank()) {
            dims[QualityDim.RSS_SORT] = DimResult(DimState.NOT_APPLICABLE, evidence = "无分类规则")
        }

        // 搜索维度：searchUrl 空合法（多数 RSS 无搜索）
        if (source.searchUrl.isNullOrBlank()) {
            dims[QualityDim.RSS_SEARCH] = DimResult(DimState.NOT_APPLICABLE, evidence = "无搜索规则")
        }

        // L3 正文试读（列表首篇）
        if (options.depth == CheckDepth.L3) {
            val firstArticle = articlesResult.getOrNull()?.first?.firstOrNull()
            if (firstArticle != null && !source.ruleContent.isNullOrBlank() && source.type != 2) {
                val contentResult = kotlin.runCatching {
                    Rss.getContentAwait(firstArticle, source.ruleContent!!, source)
                }
                dims[QualityDim.CONTENT] = if (contentResult.isSuccess && contentResult.getOrThrow().isNotBlank()) {
                    DimResult(DimState.PASS)
                } else {
                    DimResult(DimState.FAIL, failedByException = contentResult.exceptionOrNull() !is ContentEmptyException, evidence = "正文为空")
                }
            }
        }

        return finalize(source, dims, suspectReasons, FailureKind.NONE, options, domainFail = false)
    }

    private suspend fun probeDomainRss(
        url: String,
        session: SourceQualitySession,
        suspectReasons: MutableList<SuspectReason>,
        dims: MutableMap<QualityDim, DimResult>
    ): DimState {
        if (isPrivateAddress(url)) {
            if (!suspectReasons.contains(SuspectReason.PRIVATE_ADDRESS)) {
                suspectReasons.add(SuspectReason.PRIVATE_ADDRESS)
            }
            dims[QualityDim.DOMAIN] = DimResult(DimState.NOT_CHECKED, evidence = "内网地址，跳过探测")
            return DimState.NOT_CHECKED
        }
        session.getDomainCached(url)?.let { reachable ->
            dims[QualityDim.DOMAIN] = if (reachable) {
                DimResult(DimState.PASS, evidence = "域名可达(缓存)")
            } else {
                DimResult(DimState.FAIL, evidence = "网址打不开(缓存)")
            }
            return if (reachable) DimState.PASS else DimState.FAIL
        }
        val reachable = isDomainReachable(url) || checkDomainReachableSimple(url, session.options.timeout)
        session.putDomainCached(url, reachable)
        dims[QualityDim.DOMAIN] = if (reachable) {
            DimResult(DimState.PASS, evidence = "域名可达")
        } else {
            DimResult(DimState.FAIL, failedByException = true, evidence = "网址打不开")
        }
        return if (reachable) DimState.PASS else DimState.FAIL
    }

    // ============ 域名探测原语（平移自 CheckSourceService L166-199） ============

    suspend fun isDomainReachable(domain: String): Boolean {
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
     * AnalyzeUrl 真实请求复探（支持 jslib/注释/#规避等复杂源URL）
     * 实施说明（AOAdapt 2.1）：S4 原"HEAD 优先+2MB 截断"需改公共网络路径回归风险大，
     * 以 options.timeout 硬控替代（超时即取消，内存受协程取消约束）
     */
    suspend fun checkDomainReachable(source: BookSource, timeoutMs: Long): Pair<Boolean, String?> {
        return kotlin.runCatching {
            withTimeout(timeoutMs) {
                val analyzeUrl = AnalyzeUrl(
                    source.bookSourceUrl,
                    source = source,
                    ruleData = RuleData(),
                    coroutineContext = currentCoroutineContext()
                )
                analyzeUrl.getStrResponseAwait()
                val realDomain = kotlin.runCatching { URI(analyzeUrl.url).host }.getOrNull()
                Pair(true, realDomain)
            }
        }.getOrDefault(Pair(false, null))
    }

    /**
     * 订阅源域名复探（公开：供 CheckRssSourceService 等价重构委托，AD-08）
     * 行为保持：RssService 原用 CheckRssSource.timeout，调用方按原值传入
     */
    suspend fun checkDomainReachableRss(source: RssSource, timeoutMs: Long): Pair<Boolean, String?> {
        return checkDomainReachableRssUrl(source.sourceUrl, timeoutMs, source)
    }

    /**
     * URL 域名复探（无源包装）：Rss 域名兜底复探 + 通用 URL 探测
     */
    suspend fun checkDomainReachableRssUrl(
        url: String,
        timeoutMs: Long,
        source: RssSource? = null
    ): Pair<Boolean, String?> {
        return kotlin.runCatching {
            withTimeout(timeoutMs) {
                val analyzeUrl = if (source != null) {
                    AnalyzeUrl(
                        url,
                        source = source,
                        ruleData = RuleData(),
                        coroutineContext = currentCoroutineContext()
                    )
                } else {
                    AnalyzeUrl(url, coroutineContext = currentCoroutineContext())
                }
                analyzeUrl.getStrResponseAwait()
                val realDomain = kotlin.runCatching { URI(analyzeUrl.url).host }.getOrNull()
                Pair(true, realDomain)
            }
        }.getOrDefault(Pair(false, null))
    }

    private suspend fun checkDomainReachableSimple(url: String, timeoutMs: Long): Boolean {
        return checkDomainReachableRssUrl(url, timeoutMs).first
    }

    // ============ 断网预检（双信源：系统状态 + 实测探针，v4.1 二轮修正） ============

    /**
     * 网络探测短路判定：确认断网（系统无网 或 系统有网但国内外探针均不可达）
     * 返回 true = 断网，判定规则表整体短路（全部源标未测存疑放行）
     */
    suspend fun isNetworkDown(): Boolean {
        val cm = appCtx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val transportOk = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (!transportOk) return true
        // 系统有网仍需实测（captive portal 误报"有网"→域名探测全失败→全量误滤）
        val probeUrls = listOf(PROBE_URL_CN, PROBE_URL_INTL)
        val anyReachable = probeUrls.any { url ->
            kotlin.runCatching {
                withTimeout(5000) {
                    okHttpClient.newCall(
                        okhttp3.Request.Builder().url(url).head().build()
                    ).execute().use { it.isSuccessful || it.code in 300..499 }
                }
            }.getOrDefault(false)
        }
        return !anyReachable
    }

    // ============ 结论缓存（v4.1 二轮：键含档位+深度+schemaVersion） ============

    fun getCachedReport(url: String, lastUpdateTime: Long, options: ProbeOptions): SourceQualityReport? {
        if (url.isBlank() || lastUpdateTime <= 0) return null
        val raw = CacheManager.get("$CACHE_PREFIX${url}:${options.strictness}:${options.depth}:v$SCHEMA_VERSION") ?: return null
        return kotlin.runCatching {
            val wrapper: CachedReport = GSON.fromJson(raw, CachedReport::class.java)
            if (wrapper.lastUpdateTime != lastUpdateTime) return@runCatching null
            val parsed: SourceQualityReport = GSON.fromJson(wrapper.reportJson, SourceQualityReport::class.java)
            parsed
        }.getOrNull()
    }

    fun putCachedReport(url: String, lastUpdateTime: Long, options: ProbeOptions, report: SourceQualityReport) {
        if (url.isBlank() || lastUpdateTime <= 0) return
        kotlin.runCatching {
            val wrapper = CachedReport(lastUpdateTime = lastUpdateTime, reportJson = GSON.toJson(report))
            CacheManager.put(
                "$CACHE_PREFIX${url}:${options.strictness}:${options.depth}:v$SCHEMA_VERSION",
                GSON.toJson(wrapper),
                CACHE_TTL_SECONDS
            )
        }
    }

    data class CachedReport(val lastUpdateTime: Long, val reportJson: String)

    // ============ 工具 ============

    internal fun isPrivateAddress(url: String): Boolean {
        val host = kotlin.runCatching { URI(url.substringBefore("#")).host }.getOrNull() ?: return false
        // 字面 IP 内网判定（域名解析结果不在导入期做 DNS 解析，防探测延迟）
        val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
        val m = ipv4.matchEntire(host) ?: return false
        val octets = m.groupValues.drop(1).map { it.toInt() }
        val (a, b) = octets[0] to octets[1]
        return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
                || (a == 169 && b == 254) || a == 0 || a >= 224
    }

    private fun evidenceFromException(e: Throwable?): String {
        return when (e) {
            null -> "未知异常"
            is TimeoutCancellationException -> "请求超时"
            is ScriptException, is WrappedException -> "脚本执行失败"
            else -> "网络请求失败"
        }
    }

    private fun classifyFailure(e: Throwable?): FailureKind {
        return when (e) {
            null -> FailureKind.NONE
            is TimeoutCancellationException -> FailureKind.TIMEOUT
            is ScriptException, is WrappedException -> FailureKind.SCRIPT
            else -> FailureKind.NETWORK
        }
    }

    private fun fillNotChecked(dims: MutableMap<QualityDim, DimResult>, applicable: List<QualityDim>) {
        applicable.forEach { dim ->
            if (!dims.containsKey(dim)) {
                dims[dim] = DimResult(DimState.NOT_CHECKED)
            }
        }
    }

    private fun bookApplicableDims(source: BookSource): List<QualityDim> {
        return when (source.bookSourceType) {
            BookSourceType.file -> listOf(QualityDim.DOMAIN, QualityDim.SEARCH, QualityDim.DISCOVERY)
            BookSourceType.video -> listOf(QualityDim.DOMAIN, QualityDim.SEARCH, QualityDim.DISCOVERY, QualityDim.CATEGORY, QualityDim.CONTENT)
            else -> listOf(QualityDim.DOMAIN, QualityDim.SEARCH, QualityDim.DISCOVERY, QualityDim.INFO, QualityDim.CATEGORY, QualityDim.CONTENT)
        }
    }

    private fun rssApplicableDims(source: RssSource): List<QualityDim> {
        return if (source.type == 2) {
            listOf(QualityDim.DOMAIN, QualityDim.ARTICLES, QualityDim.EPISODES)
        } else {
            listOf(QualityDim.DOMAIN, QualityDim.ARTICLES, QualityDim.RSS_SEARCH, QualityDim.RSS_SORT, QualityDim.CONTENT)
        }
    }

    /**
     * 宽松档确定性失败判定（suspect 强制放行）：
     * - 结构残缺（L1 FAIL 证据）
     * - 域名不可达（DOMAIN FAIL，无论异常类）
     * - 重试后解析明确 0 结果（FAIL 且非 failedByException）
     * 网络/超时异常类 FAIL 不算确定性失败（宽松档存疑放行）
     */
    private fun computeDeterministicFail(
        dims: Map<QualityDim, DimResult>,
        suspect: Boolean
    ): Boolean {
        if (suspect) return false
        return dims.any { (dim, r) ->
            r.state == DimState.FAIL && (dim == QualityDim.DOMAIN || !r.failedByException)
        }
    }

    private fun finalize(
        source: BookSource,
        dims: Map<QualityDim, DimResult>,
        suspectReasons: List<SuspectReason>,
        failureKind: FailureKind,
        options: ProbeOptions,
        domainFail: Boolean
    ): SourceQualityReport {
        val suspect = suspectReasons.isNotEmpty()
        val report = SourceQualityReport(
            sourceUrl = source.bookSourceUrl,
            sourceType = source.bookSourceType,
            checkedDepth = options.depth.ordinal + 1,
            dimensions = dims,
            suspect = suspect,
            suspectReasons = suspectReasons,
            failureKind = failureKind,
            deterministicFail = computeDeterministicFail(dims, suspect)
        )
        val template = SourceQualityScorer.selectBookTemplate(
            source.bookSourceType,
            hasSearch = !source.searchUrl.isNullOrBlank(),
            hasDiscovery = source.enabledExplore && !source.exploreUrl.isNullOrBlank()
        )
        return SourceQualityScorer.score(report, template)
    }

    private fun finalize(
        source: RssSource,
        dims: Map<QualityDim, DimResult>,
        suspectReasons: List<SuspectReason>,
        failureKind: FailureKind,
        options: ProbeOptions,
        domainFail: Boolean
    ): SourceQualityReport {
        val suspect = suspectReasons.isNotEmpty()
        val report = SourceQualityReport(
            sourceUrl = source.sourceUrl,
            sourceType = source.type,
            checkedDepth = options.depth.ordinal + 1,
            dimensions = dims,
            suspect = suspect,
            suspectReasons = suspectReasons,
            failureKind = failureKind,
            deterministicFail = computeDeterministicFail(dims, suspect)
        )
        val isWebView = suspectReasons.contains(SuspectReason.WEB_VIEW)
        val template = SourceQualityScorer.selectRssTemplate(source.type, isWebView)
        return SourceQualityScorer.score(report, template)
    }

    /**
     * 失败分类（平移自 CheckSourceService.checkSource L153-157，供服务侧映射分组）
     */
    fun classifyCheckFailure(e: Throwable?): FailureKind = classifyFailure(e)

    const val CACHE_PREFIX = "importCheckResult:"
    const val CACHE_TTL_SECONDS = 86400 // 24h
    const val SCHEMA_VERSION = 1
    const val PROBE_URL_CN = "https://www.baidu.com"
    const val PROBE_URL_INTL = "https://www.google.com/generate_204"
}
