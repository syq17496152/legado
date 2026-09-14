package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.exception.NoStackTraceException

/**
 * quality-check-unify：体检报告 → 旧"校验所选"结果落库桥接层
 *
 * 职责：把 SourceQualityReport（只读探测产物）映射回旧校验的写库形态——
 * 失效分组 / weight 权重 / 错误注释，供管理页分组筛选与 weight 排序消费。
 *
 * 设计约束：
 * - 判定核心单一权威源（AD-08 延续）：weight 走 SourceWeightCalculator.calculate*FromResult，
 *   分组名与 CheckSourceService / CheckRssSourceService 逐字一致（单测锁定）
 * - 纯映射零网络；落库由本层负责（调用方=体检结果页"应用结果"动作）
 * - suspect（宽松档存疑放行）源不写失效分组，防误伤
 */
object QualityReportApplier {

    // ==================== 旧分组名常量（与 CheckSourceService 逐字一致） ====================

    private const val G_DOMAIN_FAIL = "域名失效"
    private const val G_SEARCH_URL_EMPTY = "搜索链接规则为空"
    private const val G_SEARCH_FAIL = "搜索失效"
    private const val G_SEARCH_CATEGORY_FAIL = "搜索目录失效"
    private const val G_SEARCH_CONTENT_FAIL = "搜索正文失效"
    private const val G_DISCOVERY_RULE_EMPTY = "发现规则为空"
    private const val G_DISCOVERY_FAIL = "发现失效"
    private const val G_CHECK_TIMEOUT = "校验超时"
    private const val G_SCRIPT_FAIL = "js失效"
    private const val G_SITE_FAIL = "网站失效"
    private const val G_ARTICLES_FAIL = "列表失效"
    private const val G_SORT_FAIL = "分类失效"
    private const val G_CONTENT_FAIL = "正文失效"

    // ==================== 书源 ====================

    /**
     * 应用书源体检结果：先清历史失效组 → 按维度加组 → weight 回填 → 注释 → 落库
     * @param checkDomain 域名校验开关（来自体检 ProbeOptions，影响"域名失效"分组与 weight 前置判定）
     * @return 实际落库条数
     */
    fun applyBookReports(
        items: List<Pair<BookSource, SourceQualityReport>>,
        checkDomain: Boolean
    ): Int {
        var applied = 0
        for ((source, report) in items) {
            // 行为对齐 CheckSourceService：先清历史失效分组再按本轮结果加组
            source.removeInvalidGroups()
            if (CheckSource.wSourceComment) {
                source.removeErrorComment()
            }
            val result = toBookCheckResult(report)
            applyBookGroups(source, report, checkDomain)
            source.weight = SourceWeightCalculator.calculateBookWeightFromResult(
                result, checkDomain
            )
            if (CheckSource.wSourceComment) {
                appendBookErrorComment(source, report)
            }
            appDb.bookSourceDao.update(source)
            applied++
        }
        AppLog.putDebugWithTag(
            QualityCheckSession.LOG_TAG,
            "应用体检结果(书源): count=$applied",
            level = AppLog.Level.INFO
        )
        return applied
    }

    /**
     * report → BookCheckResult 映射（纯函数，单测锁定）
     * NOT_APPLICABLE=规则缺失（合法，不计失败）；NOT_CHECKED=未校验（weight 满分路径）
     */
    internal fun toBookCheckResult(report: SourceQualityReport): BookCheckResult {
        val dims = report.dimensions
        fun pass(dim: QualityDim) = dims[dim]?.state == DimState.PASS
        fun fail(dim: QualityDim) = dims[dim]?.state == DimState.FAIL
        // 已检语义对齐旧服务：PASS/FAIL/NOT_APPLICABLE（规则空分支）均算已检，仅 NOT_CHECKED=未检
        fun checked(dim: QualityDim) = dims[dim]?.let { it.state != DimState.NOT_CHECKED } ?: false
        val searchDim = dims[QualityDim.SEARCH]
        val discoveryDim = dims[QualityDim.DISCOVERY]
        return BookCheckResult(
            domainReachable = pass(QualityDim.DOMAIN),
            searchChecked = checked(QualityDim.SEARCH),
            searchUrlEmpty = searchDim?.state == DimState.NOT_APPLICABLE,
            searchSuccess = pass(QualityDim.SEARCH),
            searchResultCount = searchDim?.resultCount ?: 0,
            discoveryChecked = checked(QualityDim.DISCOVERY),
            discoveryRuleEmpty = discoveryDim?.state == DimState.NOT_APPLICABLE,
            discoverySuccess = pass(QualityDim.DISCOVERY),
            discoveryResultCount = discoveryDim?.resultCount ?: 0,
            searchInfoSuccess = pass(QualityDim.INFO),
            searchCategorySuccess = pass(QualityDim.CATEGORY),
            searchContentSuccess = pass(QualityDim.CONTENT)
        )
    }

    /**
     * 分组回填（先清后加由调用方保证）：按维度 FAIL 加旧分组名
     * failureKind 细分优先（超时/js/网站），与旧服务 catch 分类一致
     */
    private fun applyBookGroups(source: BookSource, report: SourceQualityReport, checkDomain: Boolean) {
        if (report.suspect) return // 存疑放行：宽松档不误伤
        when (report.failureKind) {
            FailureKind.TIMEOUT -> {
                source.addGroup(G_CHECK_TIMEOUT)
                return
            }
            FailureKind.SCRIPT -> {
                source.addGroup(G_SCRIPT_FAIL)
                return
            }
            FailureKind.NETWORK -> {
                source.addGroup(G_SITE_FAIL)
                return
            }
            else -> Unit
        }
        val dims = report.dimensions
        if (checkDomain && dims[QualityDim.DOMAIN]?.state == DimState.FAIL) {
            source.addGroup(G_DOMAIN_FAIL)
        }
        when (dims[QualityDim.SEARCH]?.state) {
            DimState.NOT_APPLICABLE -> source.addGroup(G_SEARCH_URL_EMPTY)
            DimState.FAIL -> source.addGroup(G_SEARCH_FAIL)
            else -> Unit
        }
        if (dims[QualityDim.DISCOVERY]?.state == DimState.NOT_APPLICABLE) {
            source.addGroup(G_DISCOVERY_RULE_EMPTY)
        } else if (dims[QualityDim.DISCOVERY]?.state == DimState.FAIL) {
            source.addGroup(G_DISCOVERY_FAIL)
        }
        // 目录/正文维度来自搜索来源首本书（体检探测形态），对应旧服务"搜索目录/正文失效"
        if (dims[QualityDim.CATEGORY]?.state == DimState.FAIL) {
            source.addGroup(G_SEARCH_CATEGORY_FAIL)
        }
        if (dims[QualityDim.CONTENT]?.state == DimState.FAIL) {
            source.addGroup(G_SEARCH_CONTENT_FAIL)
        }
    }

    /** 失败注释：复用旧服务注释格式（// Error: 前缀），证据取失败维度 evidence */
    private fun appendBookErrorComment(source: BookSource, report: SourceQualityReport) {
        if (report.suspect) return
        val evidence = report.dimensions
            .filterValues { it.state == DimState.FAIL }
            .values.firstOrNull { it.evidence.isNotBlank() }?.evidence
            ?: failureKindText(report.failureKind)
            ?: return
        source.addErrorComment(NoStackTraceException(evidence))
    }

    private fun failureKindText(kind: FailureKind): String? = when (kind) {
        FailureKind.TIMEOUT -> "校验超时"
        FailureKind.SCRIPT -> "js失效"
        FailureKind.NETWORK -> "网站失效"
        else -> null
    }

    // ==================== 订阅源 ====================

    /**
     * 应用订阅源体检结果（分组名与 CheckRssSourceService 逐字一致）
     * @param checkDomain 域名校验开关
     * @return 实际落库条数
     */
    fun applyRssReports(
        items: List<Pair<RssSource, SourceQualityReport>>,
        checkDomain: Boolean
    ): Int {
        var applied = 0
        for ((source, report) in items) {
            source.removeInvalidGroups()
            val result = toRssCheckResult(report)
            applyRssGroups(source, report, checkDomain)
            source.weight = SourceWeightCalculator.calculateRssWeightFromResult(
                result, checkDomain
            )
            appDb.rssSourceDao.update(source)
            applied++
        }
        AppLog.putDebugWithTag(
            QualityCheckSession.LOG_TAG,
            "应用体检结果(订阅源): count=$applied",
            level = AppLog.Level.INFO
        )
        return applied
    }

    internal fun toRssCheckResult(report: SourceQualityReport): RssCheckResult {
        val dims = report.dimensions
        fun pass(dim: QualityDim) = dims[dim]?.state == DimState.PASS
        // 已检语义对齐书源侧：PASS/FAIL/NOT_APPLICABLE 均算已检，仅 NOT_CHECKED=未检
        fun checked(dim: QualityDim) = dims[dim]?.let { it.state != DimState.NOT_CHECKED } ?: false
        return RssCheckResult(
            domainReachable = pass(QualityDim.DOMAIN),
            articlesChecked = checked(QualityDim.ARTICLES),
            articlesSuccess = pass(QualityDim.ARTICLES),
            articlesCount = dims[QualityDim.ARTICLES]?.resultCount ?: 0,
            searchChecked = checked(QualityDim.RSS_SEARCH),
            searchSuccess = pass(QualityDim.RSS_SEARCH),
            searchResultCount = dims[QualityDim.RSS_SEARCH]?.resultCount ?: 0,
            sortChecked = checked(QualityDim.RSS_SORT),
            sortSuccess = pass(QualityDim.RSS_SORT),
            sortCount = dims[QualityDim.RSS_SORT]?.resultCount ?: 0,
            contentChecked = checked(QualityDim.CONTENT),
            contentSuccess = pass(QualityDim.CONTENT)
        )
    }

    private fun applyRssGroups(source: RssSource, report: SourceQualityReport, checkDomain: Boolean) {
        if (report.suspect) return
        when (report.failureKind) {
            FailureKind.TIMEOUT -> {
                source.addGroup(G_CHECK_TIMEOUT)
                return
            }
            FailureKind.SCRIPT -> {
                source.addGroup(G_SCRIPT_FAIL)
                return
            }
            FailureKind.NETWORK -> {
                source.addGroup(G_SITE_FAIL)
                return
            }
            else -> Unit
        }
        val dims = report.dimensions
        if (checkDomain && dims[QualityDim.DOMAIN]?.state == DimState.FAIL) {
            source.addGroup(G_DOMAIN_FAIL)
        }
        if (dims[QualityDim.ARTICLES]?.state == DimState.FAIL) {
            source.addGroup(G_ARTICLES_FAIL)
        }
        if (dims[QualityDim.RSS_SEARCH]?.state == DimState.FAIL) {
            source.addGroup(G_SEARCH_FAIL)
        }
        if (dims[QualityDim.RSS_SORT]?.state == DimState.FAIL) {
            source.addGroup(G_SORT_FAIL)
        }
        if (dims[QualityDim.CONTENT]?.state == DimState.FAIL) {
            source.addGroup(G_CONTENT_FAIL)
        }
    }
}
