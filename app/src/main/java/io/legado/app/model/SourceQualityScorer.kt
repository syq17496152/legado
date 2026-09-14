package io.legado.app.model

import io.legado.app.constant.BookSourceType

/**
 * 维度模板条目（数据化 List<DimTemplate>，A7：新增源类型只加模板数据）
 */
data class DimTemplate(
    val dim: QualityDim,
    val weight: Int
)

/**
 * 源质量形态模板（评分 v2）
 *
 * 实证依据（对照旧 SourceWeightCalculator 缺陷）：
 * - file 型无详情/目录/正文（CheckSourceService L394 本就跳过）
 * - video 型 MacCMS 零规则合法（WebBook.kt L307-313），详情维度不适用
 * - 纯发现源（searchUrl 空且 enabledExplore）合法，搜索维度 NOT_APPLICABLE
 * - RSS webview 型（startHtml）无规则可测 → 全维度 NOT_CHECKED + suspect 放行
 * - RSS searchUrl 空合法（多数订阅源无搜索）
 */
object SourceQualityScorer {

    // 书源模板：文本/音频/图片 + 搜索入口
    private val BOOK_TEXT_SEARCH = listOf(
        DimTemplate(QualityDim.DOMAIN, 20),
        DimTemplate(QualityDim.SEARCH, 30),
        DimTemplate(QualityDim.INFO, 15),
        DimTemplate(QualityDim.CATEGORY, 15),
        DimTemplate(QualityDim.CONTENT, 20)
    )

    // 书源模板：文本/音频/图片 + 发现入口（纯发现源）
    private val BOOK_TEXT_DISCOVERY = listOf(
        DimTemplate(QualityDim.DOMAIN, 20),
        DimTemplate(QualityDim.DISCOVERY, 30),
        DimTemplate(QualityDim.INFO, 15),
        DimTemplate(QualityDim.CATEGORY, 15),
        DimTemplate(QualityDim.CONTENT, 20)
    )

    // 书源模板：文件型（纯下载站，无详情/目录/正文）
    private val BOOK_FILE = listOf(
        DimTemplate(QualityDim.DOMAIN, 30),
        DimTemplate(QualityDim.SEARCH, 35),
        DimTemplate(QualityDim.DISCOVERY, 35)
    )

    // 书源模板：视频型（MacCMS 零规则合法，详情不适用）
    private val BOOK_VIDEO = listOf(
        DimTemplate(QualityDim.DOMAIN, 20),
        DimTemplate(QualityDim.SEARCH, 20),
        DimTemplate(QualityDim.DISCOVERY, 20),
        DimTemplate(QualityDim.CATEGORY, 20),
        DimTemplate(QualityDim.CONTENT, 20)
    )

    // 订阅源模板：解析型（ruleArticles 或默认 XML）
    private val RSS_PARSE = listOf(
        DimTemplate(QualityDim.DOMAIN, 20),
        DimTemplate(QualityDim.ARTICLES, 40),
        DimTemplate(QualityDim.RSS_SEARCH, 20),
        DimTemplate(QualityDim.RSS_SORT, 10),
        DimTemplate(QualityDim.CONTENT, 10)
    )

    // 订阅源模板：视频型（ruleRoutes/ruleEpisodes，正文不适用）
    private val RSS_VIDEO = listOf(
        DimTemplate(QualityDim.DOMAIN, 20),
        DimTemplate(QualityDim.ARTICLES, 40),
        DimTemplate(QualityDim.EPISODES, 40)
    )

    // 订阅源模板：webview 型（startHtml，无规则可测→suspect 放行为主，域名仍可测）
    private val RSS_WEBVIEW = listOf(
        DimTemplate(QualityDim.DOMAIN, 20),
        DimTemplate(QualityDim.FIRST_SCREEN, 80)
    )

    /**
     * 形态模板选择
     * hasSearch/hasDiscovery：入口形态（书源二者可并存，取并集维度）
     */
    fun selectBookTemplate(sourceType: Int, hasSearch: Boolean, hasDiscovery: Boolean): List<DimTemplate> {
        return when (sourceType) {
            BookSourceType.file -> BOOK_FILE
            BookSourceType.video -> BOOK_VIDEO
            else -> when {
                hasSearch && hasDiscovery -> BOOK_TEXT_SEARCH + BOOK_TEXT_DISCOVERY
                    .filter { it.dim == QualityDim.DISCOVERY }
                hasDiscovery -> BOOK_TEXT_DISCOVERY
                else -> BOOK_TEXT_SEARCH
            }
        }
    }

    fun selectRssTemplate(sourceType: Int, isWebView: Boolean): List<DimTemplate> {
        return when {
            isWebView -> RSS_WEBVIEW
            sourceType == 2 -> RSS_VIDEO
            else -> RSS_PARSE
        }
    }

    /**
     * 归一化计分：score = Σ通过权重 / Σ已校验且适用权重 × 100
     * NOT_APPLICABLE（形态不需要/字段缺失合法）与 NOT_CHECKED（本次未测）均不计分母
     * coverage = 已校验适用维度数 / 适用维度总数（补偿"只测少维度得高分"的语义弱化）
     *
     * @param template 形态模板（由 checker 依据源形态选定后传入）
     */
    fun score(report: SourceQualityReport, template: List<DimTemplate>): SourceQualityReport {
        var checkedWeight = 0
        var passedWeight = 0
        var checkedCount = 0
        template.forEach { t ->
            val r = report.dimensions[t.dim]
            when (r?.state) {
                DimState.PASS -> {
                    checkedCount++
                    checkedWeight += t.weight
                    passedWeight += t.weight
                }
                DimState.FAIL -> {
                    checkedCount++
                    checkedWeight += t.weight
                }
                else -> Unit // NOT_APPLICABLE / NOT_CHECKED / 未出现：不计分母
            }
        }
        val score = if (checkedWeight > 0) (passedWeight * 100 / checkedWeight) else 0
        val coverage = if (template.isNotEmpty()) checkedCount.toFloat() / template.size else 0f
        return report.copy(score = score, coverage = coverage)
    }

    /**
     * 用户层四态白话标签（P5）：失效/存疑/未测/可用
     */
    enum class UserState { USABLE, FAILED, SUSPECT, UNTESTED }

    fun toUserState(report: SourceQualityReport): UserState {
        return when {
            report.suspect -> UserState.SUSPECT
            report.dimensions.values.any { it.state == DimState.FAIL } -> UserState.FAILED
            report.dimensions.values.any { it.state == DimState.NOT_CHECKED } -> UserState.UNTESTED
            else -> UserState.USABLE
        }
    }

    /**
     * 导入过滤判定（AD-04：证据+档位，非分数阈值）
     *
     * @return Pair(是否进过滤集, 白话原因)；suspect 源宽松/标准档强制放行
     */
    fun isFiltered(report: SourceQualityReport, strictness: CheckStrictness): Pair<Boolean, String> {
        // suspect 五类：宽松/标准档强制放行；严格档进复核窗口由用户显式选择
        if (report.suspect && strictness != CheckStrictness.STRICT) {
            return Pair(false, "")
        }

        // 1. 结构残缺：所有档过滤
        report.dimensions[QualityDim.DOMAIN]?.let {
            if (it.state == DimState.FAIL && it.evidence == "源地址为空") {
                return Pair(true, "源地址为空")
            }
        }
        // L1 结构残缺在 report.deterministicFail 且 L1 报告
        if (report.checkedDepth == 1 && report.deterministicFail) {
            val reason = report.dimensions.values.firstOrNull { it.state == DimState.FAIL }?.evidence ?: "规则残缺"
            return Pair(true, reason)
        }

        // 2. 域名不可达：所有档过滤（suspect 已提前放行）
        report.dimensions[QualityDim.DOMAIN]?.let {
            if (it.state == DimState.FAIL) {
                return Pair(true, "网址打不开")
            }
        }

        // 3. 搜索/列表 0 结果：所有档过滤；异常：标准/严格档过滤
        val entryDims = listOf(QualityDim.SEARCH, QualityDim.DISCOVERY, QualityDim.ARTICLES)
        entryDims.forEach { dim ->
            val r = report.dimensions[dim] ?: return@forEach
            if (r.state == DimState.FAIL) {
                if (!r.failedByException) {
                    return Pair(true, r.evidence.ifBlank { "搜索不到结果" })
                } else if (strictness != CheckStrictness.LENIENT) {
                    return Pair(true, r.evidence.ifBlank { "搜索请求失败" })
                }
            }
        }

        // 4. 目录/正文失败：仅严格档过滤（需 L3）
        if (strictness == CheckStrictness.STRICT) {
            listOf(QualityDim.CATEGORY, QualityDim.CONTENT, QualityDim.INFO).forEach { dim ->
                val r = report.dimensions[dim] ?: return@forEach
                if (r.state == DimState.FAIL) {
                    return Pair(true, r.evidence.ifBlank { "内容获取失败" })
                }
            }
        }

        return Pair(false, "")
    }
}
