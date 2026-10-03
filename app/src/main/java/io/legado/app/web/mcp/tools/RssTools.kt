package io.legado.app.web.mcp.tools

import com.google.gson.JsonElement
import io.legado.app.data.entities.RssSource
import io.legado.app.service.kernel.RssSourceKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_RSS
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_BOOLEAN
import io.legado.app.web.mcp.McpJsonSchema.TYPE_INTEGER
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpParamException
import io.legado.app.web.mcp.McpTool

/**
 * ⑥ 订阅域工具声明（web-mcp-productization 二期 · tasks 2.12）。
 *
 * 红线（AD-10）：`invoke` 只调 [RssSourceKernel]；文章正文/搜索复用 `Rss` 的同一链。
 */
object RssTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "rss_source_get",
            title = "读取订阅源",
            description = "读取订阅源：带 url 返回单条订阅源完整 JSON（含规则字段）；不带 url 返回全部订阅源数组。",
            domain = MCP_DOMAIN_RSS,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("url" to TYPE_STRING),
                descriptions = mapOf("url" to "订阅源地址（sourceUrl）；省略则返回全部")
            ),
        ) { args ->
            val url = args.str("url")?.trim().orEmpty()
            if (url.isEmpty()) RssSourceKernel.sources() else RssSourceKernel.source(url)
        },

        McpTool(
            name = "rss_source_save",
            title = "保存订阅源",
            description = "保存订阅源（入参 source 可为对象或 JSON 串）。返回保存后的源；校验不通过的源会被拒绝。",
            domain = MCP_DOMAIN_RSS,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("source" to McpJsonSchema.TYPE_OBJECT),
                descriptions = mapOf("source" to "订阅源对象或 JSON 串（sourceUrl 必填）"),
            ),
        ) { args ->
            val source = parseSource(args.raw().get("source"))
                ?: throw McpParamException("参数 source 格式不对")
            val reason = RssSourceKernel.skipReason(source)
            if (reason != null) throw McpParamException("源未通过校验：$reason")
            RssSourceKernel.saveSource(source)
            source
        },

        McpTool(
            name = "rss_source_delete",
            title = "删除订阅源",
            description = "删除订阅源（批量，按 sourceUrl 数组）。返回删除条数。",
            domain = MCP_DOMAIN_RSS,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("urls" to McpJsonSchema.TYPE_ARRAY),
                descriptions = mapOf("urls" to "订阅源地址数组"),
            ),
        ) { args ->
            val urls = args.strList("urls")
            val sources = RssSourceKernel.sources().filter { it.sourceUrl in urls }
            if (sources.isEmpty()) throw IllegalArgumentException("没有匹配到的订阅源")
            RssSourceKernel.deleteSources(sources)
            mapOf("deleted" to sources.size)
        },

        McpTool(
            name = "rss_source_import",
            title = "导入订阅源",
            description = "批量导入订阅源（JSON 数组或单对象），导入前按与 App 同口径校验，失败项在 skippedDetail 给出原因。",
            domain = MCP_DOMAIN_RSS,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("json" to TYPE_STRING),
                descriptions = mapOf("json" to "订阅源 JSON（数组或单对象）"),
            ),
        ) { args ->
            RssSourceKernel.importJson(args.requireStr("json"))
        },

        McpTool(
            name = "rss_source_set_enabled",
            title = "启用/停用订阅源",
            description = "批量启用或停用订阅源。返回受影响条数。",
            domain = MCP_DOMAIN_RSS,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("urls" to McpJsonSchema.TYPE_ARRAY, "enabled" to TYPE_BOOLEAN),
                descriptions = mapOf("urls" to "订阅源地址数组", "enabled" to "true 启用 / false 停用"),
            ),
        ) { args ->
            mapOf("affected" to RssSourceKernel.setEnabled(args.strList("urls"), args.bool("enabled")))
        },

        McpTool(
            name = "rss_groups_get",
            title = "读取订阅分组",
            description = "读取订阅源分组名列表（分组是源上的逗号串，DAO 已拆分去重）。",
            domain = MCP_DOMAIN_RSS,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            RssSourceKernel.groups()
        },

        McpTool(
            name = "rss_group_save",
            title = "保存订阅分组",
            description = "重命名订阅分组（newName 传空串 = 删除分组标记）。返回受影响条数。",
            domain = MCP_DOMAIN_RSS,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("oldName" to TYPE_STRING),
                optional = mapOf("newName" to TYPE_STRING),
                descriptions = mapOf("oldName" to "原分组名", "newName" to "新分组名（空串 = 删除）"),
            ),
        ) { args ->
            mapOf("affected" to RssSourceKernel.renameGroup(args.requireStr("oldName"), args.str("newName")))
        },

        McpTool(
            name = "rss_set_sort",
            title = "调整订阅排序",
            description = "调整订阅源排序：给 url+order 精确设置；或给 urls 数组按数组顺序整体重排。",
            domain = MCP_DOMAIN_RSS,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("url" to TYPE_STRING, "order" to TYPE_INTEGER, "urls" to McpJsonSchema.TYPE_ARRAY),
                descriptions = mapOf(
                    "url" to "单条源地址",
                    "order" to "该源的 customOrder",
                    "urls" to "按此顺序整体重排的源地址数组",
                )
            ),
        ) { args ->
            RssSourceKernel.setSort(
                url = args.str("url"),
                order = if (args.raw().has("order")) args.int("order") else null,
                urls = args.strList("urls"),
            )
        },

        McpTool(
            name = "rss_articles_list",
            title = "订阅文章列表",
            description = "读取某订阅源某分组下的文章列表（可按关键词过滤、限制条数）。" +
                "返回 title/link/sort/pubDate/description/image/group/type。",
            domain = MCP_DOMAIN_RSS,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                required = mapOf("origin" to TYPE_STRING, "sort" to TYPE_STRING),
                optional = mapOf("limit" to TYPE_INTEGER, "keyword" to TYPE_STRING),
                descriptions = mapOf(
                    "origin" to "订阅源地址",
                    "sort" to "分组名（源内栏目）",
                    "limit" to "最多返回条数（默认 50）",
                    "keyword" to "标题/摘要关键词过滤（可选）",
                )
            ),
        ) { args ->
            RssSourceKernel.articles(
                origin = args.requireStr("origin"),
                sort = args.requireStr("sort"),
                limit = args.int("limit", 50),
                keyword = args.str("keyword"),
            )
        },

        McpTool(
            name = "rss_article_content",
            title = "订阅文章正文",
            description = "读取订阅文章正文（按源规则抓取并净化）。返回 {title,link,pubDate,content}。",
            domain = MCP_DOMAIN_RSS,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                required = mapOf("origin" to TYPE_STRING, "link" to TYPE_STRING),
                optional = mapOf("sort" to TYPE_STRING),
                descriptions = mapOf(
                    "origin" to "订阅源地址",
                    "link" to "文章链接（唯一标识）",
                    "sort" to "文章所属分组（可选，默认用库里已存记录的 sort）",
                )
            ),
        ) { args ->
            RssSourceKernel.articleContent(
                origin = args.requireStr("origin"),
                link = args.requireStr("link"),
                sort = args.str("sort").orEmpty(),
            )
        },

        McpTool(
            name = "rss_mark_read",
            title = "标记已读",
            description = "标记订阅源文章已读 / 未读。已读按 origin（整源）走 App 既有的已读记录链；" +
                "未读会删除这些源的已读记录。返回受影响条数。",
            domain = MCP_DOMAIN_RSS,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("origins" to McpJsonSchema.TYPE_ARRAY),
                optional = mapOf("read" to TYPE_BOOLEAN),
                descriptions = mapOf("origins" to "订阅源地址数组", "read" to "true 标记已读 / false 标记未读（默认 true）"),
            ),
        ) { args ->
            RssSourceKernel.markRead(args.strList("origins"), args.bool("read", true))
        },

        McpTool(
            name = "rss_opml_import",
            title = "导入 OPML",
            description = "导入 OPML 订阅文本（XML 串）：解析 → 按分组落库。返回 {feeds,inserted,merged,flattenedGroups}。",
            domain = MCP_DOMAIN_RSS,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("text" to TYPE_STRING),
                descriptions = mapOf("text" to "OPML XML 文本"),
            ),
        ) { args ->
            RssSourceKernel.opmlImport(args.requireStr("text"))
        },

        McpTool(
            name = "rss_opml_export",
            title = "导出 OPML",
            description = "导出 OPML 订阅文本（指定 urls，省略 = 全部启用源）。返回 {count,opml}。",
            domain = MCP_DOMAIN_RSS,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("urls" to McpJsonSchema.TYPE_ARRAY),
                descriptions = mapOf("urls" to "订阅源地址数组（省略 = 全部启用源）"),
            ),
        ) { args ->
            RssSourceKernel.opmlExport(args.strList("urls"))
        },

        McpTool(
            name = "rss_favorite_list",
            title = "订阅收藏夹",
            description = "读取订阅收藏夹条目（group 可选过滤）。返回收藏的文章元数据。",
            domain = MCP_DOMAIN_RSS,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("group" to TYPE_STRING),
                descriptions = mapOf("group" to "收藏分组（省略 = 全部）"),
            ),
        ) { args ->
            RssSourceKernel.favorites(args.str("group"))
        },

        McpTool(
            name = "rss_favorite_save",
            title = "收藏文章",
            description = "收藏 / 取消收藏一篇文章；收藏时可指定收藏分组（已收藏则改分组）。",
            domain = MCP_DOMAIN_RSS,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("origin" to TYPE_STRING, "link" to TYPE_STRING),
                optional = mapOf("star" to TYPE_BOOLEAN, "group" to TYPE_STRING),
                descriptions = mapOf(
                    "origin" to "订阅源地址",
                    "link" to "文章链接",
                    "star" to "true 收藏 / false 取消（默认 true）",
                    "group" to "收藏分组（可选）",
                )
            ),
        ) { args ->
            RssSourceKernel.favorite(
                origin = args.requireStr("origin"),
                link = args.requireStr("link"),
                star = args.bool("star", true),
                group = args.str("group"),
            )
        },

        McpTool(
            name = "rss_favorite_delete",
            title = "删除收藏条目",
            description = "删除收藏夹条目：给 group 清空该分组；或给 origin+link 删单条。返回删除条数。",
            domain = MCP_DOMAIN_RSS,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("group" to TYPE_STRING, "origin" to TYPE_STRING, "link" to TYPE_STRING),
                descriptions = mapOf(
                    "group" to "按分组整组删除",
                    "origin" to "订阅源地址（与 link 一起删单条）",
                    "link" to "文章链接",
                )
            ),
        ) { args ->
            mapOf("deleted" to RssSourceKernel.deleteFavorite(args.str("group"), args.str("origin"), args.str("link")))
        },

        McpTool(
            name = "rss_search",
            title = "RSS 跨源搜索",
            description = "在订阅源内搜索文章（逐源调搜索链，单源 30s 超时）。返回跨源命中的文章元数据数组。",
            domain = MCP_DOMAIN_RSS,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                required = mapOf("key" to TYPE_STRING),
                optional = mapOf(
                    "sourceUrls" to McpJsonSchema.TYPE_ARRAY,
                    "maxSources" to TYPE_INTEGER,
                    "perSourceLimit" to TYPE_INTEGER,
                ),
                descriptions = mapOf(
                    "key" to "搜索关键词",
                    "sourceUrls" to "指定源（省略 = 全部启用且有搜索规则的源）",
                    "maxSources" to "最多搜索多少个源（默认 8）",
                    "perSourceLimit" to "每源最多返回多少条（默认 20）",
                )
            ),
        ) { args ->
            RssSourceKernel.search(
                key = args.requireStr("key"),
                sourceUrls = args.strList("sourceUrls"),
                maxSources = args.int("maxSources", 8),
                perSourceLimit = args.int("perSourceLimit", 20),
            )
        },

        McpTool(
            name = "rss_article_info",
            title = "订阅文章详情",
            description = "读取订阅文章详情与状态（已读/已收藏/所属分组/类型）。返回文章元数据。",
            domain = MCP_DOMAIN_RSS,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                required = mapOf("origin" to TYPE_STRING, "link" to TYPE_STRING),
                descriptions = mapOf("origin" to "订阅源地址", "link" to "文章链接"),
            ),
        ) { args ->
            RssSourceKernel.articleInfo(args.requireStr("origin"), args.requireStr("link"))
        },
    )

    /** 与 REST 门面同口径：`source` 既接受对象也接受 JSON 字符串。 */
    private fun parseSource(element: JsonElement?): RssSource? {
        if (element == null || element.isJsonNull) return null
        val json = if (element.isJsonPrimitive) element.asString else GSON.toJson(element)
        return GSON.fromJsonObject<RssSource>(json).getOrNull()
    }
}
