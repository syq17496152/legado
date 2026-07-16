package io.legado.app.model

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource

/**
 * 书源校验各维度关键元素获取结果（Phase 6 重构：维度并发收集结果）
 *
 * 各维度独立收集执行结果,维度完成后串行更新分组(避免竞态),
 * 权重计算基于此结果集(非 hasGroup 二元判断)。
 */
data class BookCheckResult(
    val domainReachable: Boolean = false,
    val realHost: String? = null,
    // 搜索维度
    val searchChecked: Boolean = false,
    val searchUrlEmpty: Boolean = false,
    val searchSuccess: Boolean = false,
    val searchResultCount: Int = 0,
    // 发现维度
    val discoveryChecked: Boolean = false,
    val discoveryRuleEmpty: Boolean = false,
    val discoverySuccess: Boolean = false,
    val discoveryResultCount: Int = 0,
    // 详情维度（checkBook 结果,分搜索/发现两个来源）
    val searchInfoSuccess: Boolean = false,
    val searchCategorySuccess: Boolean = false,
    val searchContentSuccess: Boolean = false,
    val discoveryInfoSuccess: Boolean = false,
    val discoveryCategorySuccess: Boolean = false,
    val discoveryContentSuccess: Boolean = false
)

/**
 * 订阅源校验各维度关键元素获取结果
 */
data class RssCheckResult(
    val domainReachable: Boolean = false,
    val realHost: String? = null,
    // 列表维度
    val articlesChecked: Boolean = false,
    val articlesSuccess: Boolean = false,
    val articlesCount: Int = 0,
    // 搜索维度
    val searchChecked: Boolean = false,
    val searchSuccess: Boolean = false,
    val searchResultCount: Int = 0,
    // 分类维度
    val sortChecked: Boolean = false,
    val sortSuccess: Boolean = false,
    val sortCount: Int = 0,
    // 正文维度
    val contentChecked: Boolean = false,
    val contentSuccess: Boolean = false
)

/**
 * 源权重计算器（满分100分）
 *
 * 基于"关键元素获取结果"计算权重值并回填到 source.weight，
 * 使 BookSourceSort.Weight / RssSourceSort.Weight 排序功能生效。
 *
 * 设计原则：
 * - 域名不可达为前置条件，直接返回0分
 * - 校验关闭的维度按满分计入（不扣分）
 * - 递增加权：每维度通过=满分，失败=0分不加
 *
 * Phase 6 重构：新增基于 BookCheckResult/RssCheckResult 的权重计算（替代 hasGroup 二元判断）
 */
object SourceWeightCalculator {

    // 书源维度分值（满分100）
    private const val BOOK_DOMAIN_SCORE = 20      // 前置条件
    private const val BOOK_SEARCH_SCORE = 20     // 核心搜索
    private const val BOOK_DISCOVERY_SCORE = 15   // 辅助发现
    private const val BOOK_INFO_SCORE = 15        // 详情页
    private const val BOOK_CATEGORY_SCORE = 15    // 目录
    private const val BOOK_CONTENT_SCORE = 15     // 正文

    // 订阅源维度分值（满分100）
    private const val RSS_DOMAIN_SCORE = 20       // 前置条件
    private const val RSS_ARTICLES_SCORE = 25     // 核心列表
    private const val RSS_SEARCH_SCORE = 20       // 辅助搜索
    private const val RSS_SORT_SCORE = 15         // 辅助分类
    private const val RSS_CONTENT_SCORE = 20      // 正文

    /**
     * 基于 BookSource 分组状态计算权重（满分100）
     *
     * 分组名对照（来自 CheckSourceService.kt）:
     * - 域名失效 (L189/L216)
     * - 搜索失效 (L200) / 搜索链接规则为空 (L206)
     * - 发现失效 (L220) / 发现规则为空 (L215)
     * - 搜索目录失效 / 发现目录失效 (L298, bookType=搜索/发现)
     * - 搜索正文失效 / 发现正文失效 (L297, bookType=搜索/发现)
     * - 详情维度: checkBook 成功完成 = 通过（失败 throw 中断不会执行到 weight 计算）
     *
     * @param source 校验后的 BookSource（已包含 addGroup/removeGroup 状态）
     * @param domainCheckEnabled 是否启用域名校验
     */
    fun calculateBookWeightFromGroups(source: BookSource, domainCheckEnabled: Boolean): Int {
        // 域名前置条件: 校验开启且有"域名失效"分组 → 0分
        if (domainCheckEnabled && source.hasGroup("域名失效")) return 0

        var weight = 0
        // 域名: 校验关闭或无"域名失效"分组 → 满分
        if (!domainCheckEnabled || !source.hasGroup("域名失效")) weight += BOOK_DOMAIN_SCORE
        // 搜索: 无"搜索失效"且无"搜索链接规则为空" → 满分
        if (!source.hasGroup("搜索失效") && !source.hasGroup("搜索链接规则为空")) {
            weight += BOOK_SEARCH_SCORE
        }
        // 发现: 无"发现失效"且无"发现规则为空" → 满分
        if (!source.hasGroup("发现失效") && !source.hasGroup("发现规则为空")) {
            weight += BOOK_DISCOVERY_SCORE
        }
        // 详情: checkBook 成功完成（失败 throw 中断）= 通过 → 满分
        weight += BOOK_INFO_SCORE
        // 目录: 无"搜索目录失效"且无"发现目录失效" → 满分
        if (!source.hasGroup("搜索目录失效") && !source.hasGroup("发现目录失效")) {
            weight += BOOK_CATEGORY_SCORE
        }
        // 正文: 无"搜索正文失效"且无"发现正文失效" → 满分
        if (!source.hasGroup("搜索正文失效") && !source.hasGroup("发现正文失效")) {
            weight += BOOK_CONTENT_SCORE
        }
        return weight
    }

    /**
     * 基于 RssSource 分组状态计算权重（满分100）
     *
     * 分组名对照（来自 CheckRssSourceService.kt doCheckRssSource）:
     * - 域名失效 / 列表失效 / 搜索失效 / 分类失效 / 正文失效
     *
     * @param source 校验后的 RssSource（已包含 addGroup/removeGroup 状态）
     * @param domainCheckEnabled 是否启用域名校验
     */
    fun calculateRssWeightFromGroups(source: RssSource, domainCheckEnabled: Boolean): Int {
        // 域名前置条件: 校验开启且有"域名失效"分组 → 0分
        if (domainCheckEnabled && source.hasGroup("域名失效")) return 0

        var weight = 0
        if (!domainCheckEnabled || !source.hasGroup("域名失效")) weight += RSS_DOMAIN_SCORE
        if (!source.hasGroup("列表失效")) weight += RSS_ARTICLES_SCORE
        if (!source.hasGroup("搜索失效")) weight += RSS_SEARCH_SCORE
        if (!source.hasGroup("分类失效")) weight += RSS_SORT_SCORE
        if (!source.hasGroup("正文失效")) weight += RSS_CONTENT_SCORE
        return weight
    }

    /**
     * 基于 BookCheckResult 关键元素获取结果计算权重（Phase 6 新增）
     *
     * 递增加权逻辑（与用户期望一致）：
     * - 域名不可达 → 0分返回（一票否决）
     * - 搜索失败 → 搜索维度0分，继续校验后续维度
     * - 每维度通过=满分，失败=0分不加
     * - 校验关闭的维度按满分计入（不扣分）
     *
     * @param result 各维度关键元素获取结果
     * @param domainCheckEnabled 是否启用域名校验
     */
    fun calculateBookWeightFromResult(result: BookCheckResult, domainCheckEnabled: Boolean): Int {
        // 域名前置条件: 校验开启且域名不可达 → 0分
        if (domainCheckEnabled && !result.domainReachable) return 0

        var weight = 0
        // 域名: 校验关闭或域名可达 → 满分
        if (!domainCheckEnabled || result.domainReachable) weight += BOOK_DOMAIN_SCORE
        // 搜索: 未校验(校验关闭)=满分; URL空或搜索失败=0分; 搜索成功=满分
        if (!result.searchChecked) {
            weight += BOOK_SEARCH_SCORE
        } else if (!result.searchUrlEmpty && result.searchSuccess) {
            weight += BOOK_SEARCH_SCORE
        }
        // 发现: 未校验(校验关闭或规则空跳过)=满分; 发现有结果=满分
        if (!result.discoveryChecked) {
            weight += BOOK_DISCOVERY_SCORE
        } else if (!result.discoveryRuleEmpty && result.discoverySuccess) {
            weight += BOOK_DISCOVERY_SCORE
        }
        // 详情: 搜索或发现任一来源的详情成功=满分（checkBook成功完成即通过）
        if (result.searchInfoSuccess || result.discoveryInfoSuccess) {
            weight += BOOK_INFO_SCORE
        } else if (!result.searchChecked && !result.discoveryChecked) {
            // 搜索和发现都未校验,详情也算通过
            weight += BOOK_INFO_SCORE
        }
        // 目录: 搜索或发现任一来源的目录成功=满分
        if (result.searchCategorySuccess || result.discoveryCategorySuccess) {
            weight += BOOK_CATEGORY_SCORE
        } else if (!result.searchChecked && !result.discoveryChecked) {
            weight += BOOK_CATEGORY_SCORE
        }
        // 正文: 搜索或发现任一来源的正文成功=满分
        if (result.searchContentSuccess || result.discoveryContentSuccess) {
            weight += BOOK_CONTENT_SCORE
        } else if (!result.searchChecked && !result.discoveryChecked) {
            weight += BOOK_CONTENT_SCORE
        }
        return weight
    }

    /**
     * 基于 RssCheckResult 关键元素获取结果计算权重（Phase 6 新增）
     *
     * 递增加权逻辑（与书源一致）：
     * - 域名不可达 → 0分返回
     * - 每维度通过=满分，失败=0分不加
     * - 校验关闭的维度按满分计入
     *
     * @param result 各维度关键元素获取结果
     * @param domainCheckEnabled 是否启用域名校验
     */
    fun calculateRssWeightFromResult(result: RssCheckResult, domainCheckEnabled: Boolean): Int {
        // 域名前置条件: 校验开启且域名不可达 → 0分
        if (domainCheckEnabled && !result.domainReachable) return 0

        var weight = 0
        // 域名: 校验关闭或域名可达 → 满分
        if (!domainCheckEnabled || result.domainReachable) weight += RSS_DOMAIN_SCORE
        // 列表: 未校验=满分; 列表成功=满分
        if (!result.articlesChecked || result.articlesSuccess) weight += RSS_ARTICLES_SCORE
        // 搜索: 未校验=满分; 搜索成功=满分
        if (!result.searchChecked || result.searchSuccess) weight += RSS_SEARCH_SCORE
        // 分类: 未校验=满分; 分类成功=满分
        if (!result.sortChecked || result.sortSuccess) weight += RSS_SORT_SCORE
        // 正文: 未校验=满分; 正文成功=满分
        if (!result.contentChecked || result.contentSuccess) weight += RSS_CONTENT_SCORE
        return weight
    }
}
