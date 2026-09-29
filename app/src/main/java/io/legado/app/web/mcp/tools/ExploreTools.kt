package io.legado.app.web.mcp.tools

import com.google.gson.JsonElement
import io.legado.app.service.kernel.ExploreKernel
import io.legado.app.ui.main.explore.DiscoverySuiteConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_EXPLORE
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_BOOLEAN
import io.legado.app.web.mcp.McpJsonSchema.TYPE_INTEGER
import io.legado.app.web.mcp.McpJsonSchema.TYPE_OBJECT
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpParamException
import io.legado.app.web.mcp.McpTool

/**
 * ⑱ 发现域工具声明（发现栏目 / 探索 / 发现套件 / 差异化缓存；web-mcp-productization 二期 · tasks 2.22 / 2.28）。
 *
 * 红线（AD-10）：`invoke` **只调 `service/kernel`**，不内联业务、不 import `api.controller`（REQ-2-305）。
 * 元数据按域单源声明；执行体一律调 [ExploreKernel]。
 *
 * 能力边界与降级（与 Kernel 一致的如实口径）：
 * - `explore_books` 慢源**结构化超时**（`success = false` + `errorType = "timeout"` + 失败原因），不静默返回空；
 * - `explore_cache_config_save` 只能写真实偏好（`mergeDiscoveryRss` / `mergedDiscoveryRssTarget`），
 *   体积上限是代码常量 ⇒ 返回值 `policyWritable = false`。
 */
object ExploreTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "explore_sources",
            title = "发现首页书源分组",
            description = "读取发现栏目首页（启用发现且配置了发现 URL 的书源，按分组归集；空分组归入「未分组」）。" +
                "返回 total/groupCount/groups（单组含 name/count/sources：url/name/type/lastHost）。",
            domain = MCP_DOMAIN_EXPLORE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            ExploreKernel.sources()
        },

        McpTool(
            name = "explore_kinds",
            title = "探索分类导航",
            description = "读取某书源的探索分类导航（分类按钮元数据，支持 JS / `js` 规则，带缓存）。" +
                "返回 sourceUrl/total/kinds（单条含 title/url/type/action/chars/default/viewName）。",
            domain = MCP_DOMAIN_EXPLORE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.singleRequiredString("sourceUrl", "书源地址（取 explore_sources 返回值）"),
        ) { args ->
            ExploreKernel.kinds(args.requireStr("sourceUrl"))
        },

        McpTool(
            name = "explore_books",
            title = "探索结果列表",
            description = "读取探索结果列表（按分类 URL 分页取书）。返回 success/page/total/books" +
                "（单条含 name/author/bookUrl/coverUrl/intro/kind/origin/originName/latestChapterTitle/wordCount）。" +
                "**慢源可能结构化超时**：返回 success = false + errorType = timeout + 失败原因（不抛出、不静默返回空）。",
            domain = MCP_DOMAIN_EXPLORE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                required = mapOf("sourceUrl" to TYPE_STRING, "url" to TYPE_STRING),
                optional = mapOf("page" to TYPE_INTEGER),
                descriptions = mapOf(
                    "sourceUrl" to "书源地址（取 explore_sources 返回值）",
                    "url" to "探索分类 URL（取 explore_kinds 返回的 kinds[].url）",
                    "page" to "页码（默认 1）",
                )
            ),
        ) { args ->
            ExploreKernel.books(
                sourceUrl = args.requireStr("sourceUrl"),
                url = args.requireStr("url"),
                page = args.int("page", 1),
            )
        },

        McpTool(
            name = "discovery_suite_get",
            title = "发现套件配置",
            description = "读取发现套件配置（随机 / 标签 / 榜单 / 书单 / 横滑 / 排行 / 瀑布 组件的配置与数据）。" +
                "返回 selectedSuiteId/config（config.suites[].widgets[]…）。",
            domain = MCP_DOMAIN_EXPLORE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            ExploreKernel.suiteGet()
        },

        McpTool(
            name = "discovery_suite_save",
            title = "保存发现套件配置",
            description = "保存发现套件配置（**整份覆盖**，落库前由端侧 sanitize：条数 / 长度上限、组件类型归一化、" +
                "水位组件置底）。入参 suite 为 DiscoverySuiteConfig 结构（`{suites:[…]}`）对象或 JSON 字符串；" +
                "返回保存后的配置。",
            domain = MCP_DOMAIN_EXPLORE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("suite" to TYPE_OBJECT),
                descriptions = mapOf("suite" to "发现套件配置对象（DiscoverySuiteConfig；suites 数组）"),
            ),
        ) { args ->
            val config = parseSuite(args.raw().get("suite"))
                ?: throw McpParamException("参数 suite 格式不对（须为 DiscoverySuiteConfig 对象或 JSON 字符串）")
            ExploreKernel.suiteSave(config)
        },

        McpTool(
            name = "explore_cache_config_get",
            title = "发现缓存策略",
            description = "读取发现缓存策略：policy（Room 单行体积上限等**代码常量**判据，只读）" +
                "与 prefs（showDiscovery / mergeDiscoveryRss / mergedDiscoveryRssTarget）。",
            domain = MCP_DOMAIN_EXPLORE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            ExploreKernel.cacheConfigGet()
        },

        McpTool(
            name = "explore_cache_config_save",
            title = "保存发现缓存配置",
            description = "保存发现缓存配置：只覆盖传入字段（mergeDiscoveryRss / mergedDiscoveryRssTarget，" +
                "后者仅接受 `rss` / `explore`）。体积上限为**代码常量**，不可写 ⇒ 返回 policyWritable = false 如实回告。",
            domain = MCP_DOMAIN_EXPLORE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                optional = mapOf(
                    "mergeDiscoveryRss" to TYPE_BOOLEAN,
                    "mergedDiscoveryRssTarget" to TYPE_STRING,
                ),
                descriptions = mapOf(
                    "mergeDiscoveryRss" to "是否把订阅合并到发现页（省略 = 不改）",
                    "mergedDiscoveryRssTarget" to "合并落点：rss / explore（省略 = 不改）",
                )
            ),
        ) { args ->
            ExploreKernel.cacheConfigSave(
                mergeDiscoveryRss = if (args.raw().has("mergeDiscoveryRss")) args.bool("mergeDiscoveryRss") else null,
                mergedDiscoveryRssTarget = if (args.raw().has("mergedDiscoveryRssTarget")) {
                    args.str("mergedDiscoveryRssTarget")
                } else {
                    null
                },
            )
        },
    )

    /** 与 REST 门面同口径：`suite` 既接受对象也接受 JSON 字符串。 */
    private fun parseSuite(element: JsonElement?): DiscoverySuiteConfig? {
        if (element == null || element.isJsonNull) return null
        val json = if (element.isJsonPrimitive) element.asString else GSON.toJson(element)
        return GSON.fromJsonObject<DiscoverySuiteConfig>(json).getOrNull()
    }
}