package io.legado.app.service.kernel

import io.legado.app.constant.AppPattern
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.main.explore.DiscoveryCachePolicy
import io.legado.app.ui.main.explore.DiscoverySuiteConfig
import io.legado.app.ui.main.explore.DiscoverySuiteStore
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx

/**
 * ⑱ 发现域业务内核（web-mcp-productization 二期 · tasks 2.22 / 2.28）。
 *
 * 契约同 [BookKernel]：**只返回领域对象 / 结构化 Map**、**全链挂起**、**零 `runBlocking`**、失败抛异常。
 *
 * 数据源（全部既有能力，不新增存储）：
 * - 发现首页书源：`BookSourceDao.flowExplore()`（`enabledExplore = 1 and hasExploreUrl = 1`，
 *   与发现页同口径；报告 §⑱ 未点名此查询，已读源码核实 `BookSourceDao.kt:85-89`）；
 * - 探索分类：`BookSourcePart.exploreKinds()`（报告 §⑱，带 md5 键缓存，含 JS/`<js>` 规则）；
 * - 探索结果：`WebBook.exploreBookAwait(...)`（报告 §⑱）；
 * - 套件配置：`DiscoverySuiteStore.load()/save()`（报告 §⑱；键 `PreferKey.discoverySuiteConfig`）；
 * - 缓存策略：`DiscoveryCachePolicy`（报告 §⑱；**纯函数 + 常量**，见下方降级说明）。
 *
 * 已知上限 / 降级（如实声明）：
 * 1. `books()` **结构化失败**：慢源用 `withTimeout` 包裹，超时**不抛出**而是返回
 *    `success = false, errorType = "timeout"`（便于 MCP/控制台区分「源慢」与「源错」）；
 *    非超时异常仍**向上抛**（保持内核「失败抛异常」契约）；
 * 2. `sources()` 的**分组**口径：按 `bookSourceGroup` 以 `AppPattern.splitGroupRegex` 拆分
 *    （与 `BookSourcePart.addGroup` 同规则），多分组源会出现在**每个**分组下；空分组归入 [UNGROUPED]；
 * 3. `cacheConfigSave()` 只能写**真实存在的偏好**（`mergeDiscoveryRss` / `mergedDiscoveryRssTarget`）；
 *    `DiscoveryCachePolicy.MAX_SQLITE_VALUE_BYTES` 等是**代码常量**（控制 Room 单行体积），
 *    无持久化入口 ⇒ 入参中的 `maxSqliteValueBytes` **只回告不可写**（见返回值 `policyWritable`）；
 * 4. `sources()` 返回源名/源 URL（产品功能所需）；URL 经 [DiagKernel.maskUrl] 打码敏感 query。
 */
object ExploreKernel {

    /** 未分组桶名（与 `BookSourceDao` 的 `'%未分组%'` 口径一致）。 */
    const val UNGROUPED = "未分组"

    /** 探索请求默认墙钟上限（慢源结构化失败，见类注释「已知上限 1」）。 */
    const val EXPLORE_TIMEOUT_MS = 60_000L

    // ============================================================ 发现首页 / 分类 / 结果

    /** `explore_sources`：发现首页书源分组（启用发现且配置了发现 URL 的源）。 */
    suspend fun sources(): Map<String, Any?> = withContext(IO) {
        val parts = appDb.bookSourceDao.flowExplore().first()
        val grouped = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        parts.forEach { part ->
            val groups = part.bookSourceGroup
                ?.splitNotBlank(AppPattern.splitGroupRegex)
                ?.filter { it.isNotBlank() }
                .orEmpty()
            val keys = groups.ifEmpty { listOf(UNGROUPED) }
            keys.forEach { key ->
                grouped.getOrPut(key) { mutableListOf() }.add(
                    mapOf(
                        "url" to DiagKernel.maskUrl(part.bookSourceUrl),
                        "name" to part.bookSourceName,
                        "type" to part.bookSourceType,
                        "lastHost" to part.lastHost,
                    )
                )
            }
        }
        mapOf(
            "total" to parts.size,
            "groupCount" to grouped.size,
            "groups" to grouped.map { (name, sources) ->
                mapOf("name" to name, "count" to sources.size, "sources" to sources)
            },
        )
    }

    /**
     * `explore_kinds`：探索分类（`BookSourcePart.exploreKinds()`，带缓存与 JS 规则支持）。
     *
     * @throws NoSuchElementException 书源不存在
     */
    suspend fun kinds(sourceUrl: String): Map<String, Any?> {
        require(sourceUrl.isNotBlank()) { "sourceUrl 不能为空" }
        val part = withContext(IO) { appDb.bookSourceDao.getBookSourcePart(sourceUrl) }
            ?: throw NoSuchElementException("书源不存在：${DiagKernel.maskUrl(sourceUrl)}")
        val kinds = part.exploreKinds()
        return mapOf(
            "sourceUrl" to DiagKernel.maskUrl(sourceUrl),
            "total" to kinds.size,
            "kinds" to kinds.map {
                mapOf(
                    "title" to it.title,
                    "url" to DiagKernel.maskUrl(it.url),
                    "type" to it.type,
                    "action" to it.action,
                    "chars" to it.chars?.toList(),
                    "default" to it.default,
                    "viewName" to it.viewName,
                )
            },
        )
    }

    /**
     * `explore_books`：探索结果（`WebBook.exploreBookAwait`）。
     *
     * 慢源结构化失败：超时返回 `success = false` + `errorType = "timeout"`（不抛出）；
     * 其余异常向上抛（书源缺失 → `NoSuchElementException`，规则/网络错误 → 原样上抛）。
     *
     * @throws NoSuchElementException 书源不存在
     */
    suspend fun books(
        sourceUrl: String,
        url: String,
        page: Int = 1,
        timeoutMs: Long = EXPLORE_TIMEOUT_MS,
    ): Map<String, Any?> = withContext(IO) {
        require(sourceUrl.isNotBlank()) { "sourceUrl 不能为空" }
        require(url.isNotBlank()) { "url 不能为空" }
        val source: BookSource = appDb.bookSourceDao.getBookSource(sourceUrl)
            ?: throw NoSuchElementException("书源不存在：${DiagKernel.maskUrl(sourceUrl)}")
        try {
            val books = withTimeout(timeoutMs) { WebBook.exploreBookAwait(source, url, page) }
            mapOf(
                "success" to true,
                "page" to page,
                "total" to books.size,
                "books" to books.map {
                    mapOf(
                        "name" to it.name,
                        "author" to it.author,
                        "bookUrl" to DiagKernel.maskUrl(it.bookUrl),
                        "coverUrl" to DiagKernel.maskUrl(it.coverUrl),
                        "intro" to it.intro,
                        "kind" to it.kind,
                        "origin" to DiagKernel.maskUrl(it.origin),
                        "originName" to it.originName,
                        "latestChapterTitle" to it.latestChapterTitle,
                        "wordCount" to it.wordCount,
                    )
                },
            )
        } catch (_: TimeoutCancellationException) {
            mapOf(
                "success" to false,
                "errorType" to "timeout",
                "page" to page,
                "timeoutMs" to timeoutMs,
                "total" to 0,
                "books" to emptyList<Any>(),
                "error" to "探索超时（${timeoutMs}ms）：源响应过慢或规则挂起",
            )
        }
    }

    // ============================================================ 发现套件

    /** `discovery_suite_get`：发现套件配置（`DiscoverySuiteStore.load()` + 当前选中套件 id）。 */
    suspend fun suiteGet(): Map<String, Any?> = withContext(IO) {
        mapOf(
            "selectedSuiteId" to DiscoverySuiteStore.selectedSuiteId(),
            "config" to DiscoverySuiteStore.load(),
        )
    }

    /**
     * `discovery_suite_save`：保存发现套件配置（`DiscoverySuiteStore.save` 内含 sanitize：
     * 条数/长度上限、`DiscoverySuiteWidgetType.sanitize` 归一化、水位组件置底）。
     */
    suspend fun suiteSave(config: DiscoverySuiteConfig): Map<String, Any?> = withContext(IO) {
        DiscoverySuiteStore.save(config)
        suiteGet()
    }

    // ============================================================ 发现缓存策略

    /**
     * `explore_cache_config_get`：发现缓存策略。
     *
     * `policy` 为 `DiscoveryCachePolicy` 的**代码常量/判据**（只读）：Room 单行体积上限与
     * 「可读 / 可存」判据；`prefs` 为可写偏好（合并订阅到发现页相关）。
     */
    suspend fun cacheConfigGet(): Map<String, Any?> = withContext(IO) {
        mapOf(
            "policy" to mapOf(
                "maxSqliteValueBytes" to DiscoveryCachePolicy.MAX_SQLITE_VALUE_BYTES,
                "sampleStorable" to DiscoveryCachePolicy.canStore(""),
            ),
            "prefs" to mapOf(
                "showDiscovery" to AppConfig.showDiscovery,
                "mergeDiscoveryRss" to AppConfig.mergeDiscoveryRss,
                "mergedDiscoveryRssTarget" to AppConfig.mergedDiscoveryRssTarget,
            ),
        )
    }

    /**
     * `explore_cache_config_save`：保存发现缓存配置。
     *
     * 只写**真实存在的偏好**（`mergeDiscoveryRss` / `mergedDiscoveryRssTarget`）；
     * `maxSqliteValueBytes` 为代码常量 ⇒ **不可写**，返回值里 `policyWritable = false` 如实回告。
     * `mergedDiscoveryRssTarget` 仅接受 `"rss"` / `"explore"`（其余值回落为不改动）。
     */
    suspend fun cacheConfigSave(
        mergeDiscoveryRss: Boolean? = null,
        mergedDiscoveryRssTarget: String? = null,
    ): Map<String, Any?> = withContext(IO) {
        mergeDiscoveryRss?.let {
            appCtx.putPrefBoolean(PreferKey.mergeDiscoveryRss, it)
        }
        mergedDiscoveryRssTarget
            ?.trim()
            ?.lowercase()
            ?.takeIf { it == "rss" || it == "explore" }
            ?.let { AppConfig.mergedDiscoveryRssTarget = it }
        mapOf(
            "policyWritable" to false,
            "policy" to "DiscoveryCachePolicy 的体积上限为代码常量（无持久化入口），不可通过本接口修改",
            "config" to cacheConfigGet(),
        )
    }
}