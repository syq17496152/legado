package io.legado.app.model

/**
 * 源校验/体检的**失效分组名唯一权威**（写库口径；`CheckSource` 校验与 `SourceQualityChecker` 体检共用）。
 *
 * 背景（缺口6·扩大测试基准发现）：同一份分组名词表曾散落三处 —— `CheckSourceService` 内联字面量、
 * `QualityReportApplier` 的私有 `G_*` 常量、`checkFailureGroupName` 内联字面量；后两处靠"与 service
 * 逐字一致（单测锁定）"维持 ⇒ 属**靠测试锁住的重复**而非单源。本对象收口为唯一来源：
 * **改分组字面量只需改这里**，其余引用点全部经常量。
 *
 * 隐式消费者（口径保留，勿轻改）：
 *  · [io.legado.app.data.entities.BookSource.getInvalidGroupNames] 以 `"失效" in it || it == "校验超时"`
 *    判定"待清除的失效组"⇒ 本表**新增分组名时须确认它是否应被下一轮校验清除**；
 *  · `BookSourceActivity.FAILED_SOURCE_GROUP_KEY = "失效"`（校验后自动筛选失效源的搜索键）。
 */
object SourceCheckGroups {

    // ---- 书源·维度类 ----
    /** 域名不可达（前置条件，命中即权重归零） */
    const val DOMAIN_FAIL = "域名失效"
    const val SEARCH_URL_EMPTY = "搜索链接规则为空"
    const val SEARCH_FAIL = "搜索失效"
    const val SEARCH_CATEGORY_FAIL = "搜索目录失效"
    const val SEARCH_CONTENT_FAIL = "搜索正文失效"
    const val DISCOVERY_RULE_EMPTY = "发现规则为空"
    const val DISCOVERY_FAIL = "发现失效"
    const val DISCOVERY_CATEGORY_FAIL = "发现目录失效"
    const val DISCOVERY_CONTENT_FAIL = "发现正文失效"

    // ---- 失败归因类（`checkFailureGroupName` 的返回值） ----
    const val CHECK_TIMEOUT = "校验超时"
    const val SCRIPT_FAIL = "js失效"
    const val SITE_FAIL = "网站失效"

    // ---- 订阅源 ----
    const val RSS_ARTICLES_FAIL = "列表失效"
    const val RSS_SORT_FAIL = "分类失效"
    const val RSS_CONTENT_FAIL = "正文失效"
}
