package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.help.source.SourceQueryCache
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.TimeoutCancellationException
import com.script.ScriptException
import org.mozilla.javascript.WrappedException

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

    // 分组名不再本地声明：单源＝[SourceCheckGroups]（缺口6：此处原有 13 条"与 CheckSourceService
    // 逐字一致（单测锁定）"的私有副本 ⇒ 属靠测试锁住的重复，已收口）

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
        // R18（B4）：写源入口 ⑥质量修复 —— 使查询缓存整体失效（批量写后一次失效即可）
        if (applied > 0) SourceQueryCache.invalidate()
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
     * 分组回填（**唯一编排**；先清后加由调用方保证）：由**维度态 + 失败归因**驱动。
     *
     * 服务侧（`CheckSourceService`）与体检侧（本层 report 路径）共用同一实现 —— 缺口2 收敛：
     * 旧服务曾自带一份等价 but 独立维护的分组回填逻辑，两侧靠"逐字一致"维系。
     *
     * @param dims 各维度结论态（缺项/`NOT_CHECKED` 一律不加组）
     * @param failureKind 例外路径归因（`TIMEOUT/SCRIPT/NETWORK` 短路为单一分组；`NONE` 走维度判定）
     * @param suspect 存疑放行（宽松档）：**不写任何失效分组**，防误伤
     */
    internal fun applyBookGroupsFromDims(
        source: BookSource,
        dims: Map<QualityDim, DimState?>,
        failureKind: FailureKind,
        suspect: Boolean,
        checkDomain: Boolean,
    ) {
        failureGroupNames(dims, failureKind, suspect, checkDomain).forEach { source.addGroup(it) }
    }

    /**
     * 维度/归因 → 失败分组名清单（**纯函数，无副作用**）—— 编排的可测内核。
     *
     * `applyBookGroupsFromDims` 仅负责把本函数结果逐个 `addGroup`；把"算名字"与"写库"拆开，
     * 使判定可在 JVM 直接断言（旧写法把名字计算埋在 `source.addGroup` 副作用里，测试构型下
     * 分组字段的多组累积语义不可靠 ⇒ 断言不稳）。
     *
     * 顺序即写入顺序（调用方按序 addGroup），suspect/无失败一律空清单。
     */
    internal fun failureGroupNames(
        dims: Map<QualityDim, DimState?>,
        failureKind: FailureKind,
        suspect: Boolean,
        checkDomain: Boolean,
    ): List<String> {
        if (suspect) return emptyList()
        when (failureKind) {
            FailureKind.TIMEOUT -> return listOf(SourceCheckGroups.CHECK_TIMEOUT)
            FailureKind.SCRIPT -> return listOf(SourceCheckGroups.SCRIPT_FAIL)
            FailureKind.NETWORK -> return listOf(SourceCheckGroups.SITE_FAIL)
            else -> Unit
        }
        val names = ArrayList<String>(4)
        if (checkDomain && dims[QualityDim.DOMAIN] == DimState.FAIL) {
            names.add(SourceCheckGroups.DOMAIN_FAIL)
        }
        when (dims[QualityDim.SEARCH]) {
            DimState.NOT_APPLICABLE -> names.add(SourceCheckGroups.SEARCH_URL_EMPTY)
            DimState.FAIL -> names.add(SourceCheckGroups.SEARCH_FAIL)
            else -> Unit
        }
        when (dims[QualityDim.DISCOVERY]) {
            DimState.NOT_APPLICABLE -> names.add(SourceCheckGroups.DISCOVERY_RULE_EMPTY)
            DimState.FAIL -> names.add(SourceCheckGroups.DISCOVERY_FAIL)
            else -> Unit
        }
        // 目录/正文维度来自搜索来源首本书（体检探测形态），对应旧服务"搜索目录/正文失效"
        if (dims[QualityDim.CATEGORY] == DimState.FAIL) {
            names.add(SourceCheckGroups.SEARCH_CATEGORY_FAIL)
        }
        if (dims[QualityDim.CONTENT] == DimState.FAIL) {
            names.add(SourceCheckGroups.SEARCH_CONTENT_FAIL)
        }
        return names
    }

    private fun applyBookGroups(source: BookSource, report: SourceQualityReport, checkDomain: Boolean) =
        applyBookGroupsFromDims(
            source = source,
            dims = report.dimensions.mapValues { it.value.state },
            failureKind = report.failureKind,
            suspect = report.suspect,
            checkDomain = checkDomain,
        )

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

    /** 归因 → 证据文案：**与分组名同源**（三态词表唯一），避免两处各写一份 */
    private fun failureKindText(kind: FailureKind): String? = failureKindGroup(kind)

    /**
     * 失败归因**唯一分类器**（纯函数，JVM 可测；服务侧与体检侧共用 —— 缺口2 收敛）。
     *
     * 口径：超时 → `TIMEOUT`；脚本异常（`ScriptException`/Rhino `WrappedException`）→ `SCRIPT`；
     * **业务异常（`NoStackTraceException` 家族）→ `NONE`**（引擎侧已给明确结论，不能再打"网站失效"）；
     * 其余（网络/解析）→ `NETWORK`。
     */
    internal fun failureKindOf(e: Throwable): FailureKind = when (e) {
        is TimeoutCancellationException -> FailureKind.TIMEOUT
        is ScriptException, is WrappedException -> FailureKind.SCRIPT
        !is NoStackTraceException -> FailureKind.NETWORK
        else -> FailureKind.NONE
    }

    /** 归因 → 失效分组名（`null` = 不加分组）：分组名取自单源 [SourceCheckGroups] */
    internal fun failureKindGroup(kind: FailureKind): String? = when (kind) {
        FailureKind.TIMEOUT -> SourceCheckGroups.CHECK_TIMEOUT
        FailureKind.SCRIPT -> SourceCheckGroups.SCRIPT_FAIL
        FailureKind.NETWORK -> SourceCheckGroups.SITE_FAIL
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
        // R18（B4）：写源入口 ⑥质量修复（订阅源）
        if (applied > 0) SourceQueryCache.invalidate()
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
                source.addGroup(SourceCheckGroups.CHECK_TIMEOUT)
                return
            }
            FailureKind.SCRIPT -> {
                source.addGroup(SourceCheckGroups.SCRIPT_FAIL)
                return
            }
            FailureKind.NETWORK -> {
                source.addGroup(SourceCheckGroups.SITE_FAIL)
                return
            }
            else -> Unit
        }
        val dims = report.dimensions
        if (checkDomain && dims[QualityDim.DOMAIN]?.state == DimState.FAIL) {
            source.addGroup(SourceCheckGroups.DOMAIN_FAIL)
        }
        if (dims[QualityDim.ARTICLES]?.state == DimState.FAIL) {
            source.addGroup(SourceCheckGroups.RSS_ARTICLES_FAIL)
        }
        if (dims[QualityDim.RSS_SEARCH]?.state == DimState.FAIL) {
            source.addGroup(SourceCheckGroups.SEARCH_FAIL)
        }
        if (dims[QualityDim.RSS_SORT]?.state == DimState.FAIL) {
            source.addGroup(SourceCheckGroups.RSS_SORT_FAIL)
        }
        if (dims[QualityDim.CONTENT]?.state == DimState.FAIL) {
            source.addGroup(SourceCheckGroups.RSS_CONTENT_FAIL)
        }
    }
}
