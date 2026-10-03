package io.legado.app.web.mcp.tools

import io.legado.app.service.kernel.SourceDebugKernel
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_RSS
import io.legado.app.web.mcp.MCP_DOMAIN_SOURCE
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_INTEGER
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpTool

/**
 * 源调试域 L3 工具声明（web-mcp-productization 二期 · tasks 5.12 / §5.6–5.13）。
 *
 * **红线（REQ-2-403）**：本文件位于 `src/debug/` sourceSet ⇒ **只编入 debug 变体**，release 包内物理不存在。
 * 业务能力全部在 main 变体的 [SourceDebugKernel]，本文件只做「元数据 + 入参 Schema + 委派」。
 *
 * **口径（宁严不宽）**：9 个工具**级别均为 [TokenManager.Level.MANAGE]**，且 `readOnlyHint` **一律 false**
 * —— 即使语义上多为"只读探测"，L3 测试工具按 spec 保守口径**不向 AI 声明只读安全**
 * （它们会发起任意网络请求 / 触发源规则解析，非纯读取）。
 */
object SourceDebugTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "source_search_test",
            title = "书源搜索测试",
            description = "L3 调试工具，仅 debug 包可用。用指定书源搜索关键词，返回结构化调试结果" +
                "（success/step/elapsedMs/detail/error）：命中数 + 结果预览（name/author/bookUrl…）。",
            domain = MCP_DOMAIN_SOURCE,
            level = TokenManager.Level.MANAGE,
            readOnlyHint = false,
            inputSchema = McpJsonSchema.of(
                required = mapOf("sourceUrl" to TYPE_STRING, "key" to TYPE_STRING),
                optional = mapOf("page" to TYPE_INTEGER),
                descriptions = mapOf(
                    "sourceUrl" to "书源地址（bookSourceUrl）",
                    "key" to "搜索关键词",
                    "page" to "页码（默认 1）",
                )
            ),
        ) { args ->
            SourceDebugKernel.searchTest(
                sourceUrl = args.requireStr("sourceUrl"),
                key = args.requireStr("key"),
                page = args.int("page", 1),
            )
        },

        McpTool(
            name = "source_info_test",
            title = "书源信息体检",
            description = "L3 调试工具，仅 debug 包可用。书源基本信息体检（不联网）：名称/分组/类型/是否启用，" +
                "以及关键规则字段是否齐备。返回结构化调试结果（success/elapsedMs/detail/error）。",
            domain = MCP_DOMAIN_SOURCE,
            level = TokenManager.Level.MANAGE,
            readOnlyHint = false,
            inputSchema = McpJsonSchema.singleRequiredString("sourceUrl", "书源地址（bookSourceUrl）"),
        ) { args ->
            SourceDebugKernel.infoTest(args.requireStr("sourceUrl"))
        },

        McpTool(
            name = "source_toc_test",
            title = "书源目录测试",
            description = "L3 调试工具，仅 debug 包可用。拉目录测试：返回章节数 + 前若干章标题。" +
                "失败时 step 指出是详情页（info）还是目录（toc）环节。结构化返回（success/step/elapsedMs/detail/error）。",
            domain = MCP_DOMAIN_SOURCE,
            level = TokenManager.Level.MANAGE,
            readOnlyHint = false,
            inputSchema = McpJsonSchema.of(
                required = mapOf("sourceUrl" to TYPE_STRING, "bookUrl" to TYPE_STRING),
                descriptions = mapOf(
                    "sourceUrl" to "书源地址（bookSourceUrl）",
                    "bookUrl" to "书籍详情页地址",
                )
            ),
        ) { args ->
            SourceDebugKernel.tocTest(
                sourceUrl = args.requireStr("sourceUrl"),
                bookUrl = args.requireStr("bookUrl"),
            )
        },

        McpTool(
            name = "source_content_test",
            title = "书源正文测试",
            description = "L3 调试工具，仅 debug 包可用。拉正文测试：返回正文字符数 + 前 200 字片段。" +
                "失败时 step 指出失败环节（source/info/toc/content）；正文环节失败会声明" +
                "「未定位到具体规则行」（既有 API 能力边界）。结构化返回（success/step/elapsedMs/detail/error）。",
            domain = MCP_DOMAIN_SOURCE,
            level = TokenManager.Level.MANAGE,
            readOnlyHint = false,
            inputSchema = McpJsonSchema.of(
                required = mapOf("sourceUrl" to TYPE_STRING, "bookUrl" to TYPE_STRING),
                optional = mapOf("chapterIndex" to TYPE_INTEGER),
                descriptions = mapOf(
                    "sourceUrl" to "书源地址（bookSourceUrl）",
                    "bookUrl" to "书籍详情页地址",
                    "chapterIndex" to "章节序号（默认 0，即首章）",
                )
            ),
        ) { args ->
            SourceDebugKernel.contentTest(
                sourceUrl = args.requireStr("sourceUrl"),
                bookUrl = args.requireStr("bookUrl"),
                chapterIndex = args.int("chapterIndex", 0),
            )
        },

        McpTool(
            name = "source_explore_test",
            title = "书源发现测试",
            description = "L3 调试工具，仅 debug 包可用。发现/探索测试：用指定发现入口 url 探测，" +
                "返回命中数 + 结果预览。结构化返回（success/step/elapsedMs/detail/error）。",
            domain = MCP_DOMAIN_SOURCE,
            level = TokenManager.Level.MANAGE,
            readOnlyHint = false,
            inputSchema = McpJsonSchema.of(
                required = mapOf("sourceUrl" to TYPE_STRING, "url" to TYPE_STRING),
                optional = mapOf("page" to TYPE_INTEGER),
                descriptions = mapOf(
                    "sourceUrl" to "书源地址（bookSourceUrl）",
                    "url" to "发现页地址（书源 exploreUrl 中的某个入口）",
                    "page" to "页码（默认 1）",
                )
            ),
        ) { args ->
            SourceDebugKernel.exploreTest(
                sourceUrl = args.requireStr("sourceUrl"),
                url = args.requireStr("url"),
                page = args.int("page", 1),
            )
        },

        McpTool(
            name = "rss_search_test",
            title = "订阅源搜索测试",
            description = "L3 调试工具，仅 debug 包可用。订阅源搜索测试：返回命中数 + 文章预览" +
                "（title/link/sort/pubDate）。无搜索规则时结构化返回失败。",
            domain = MCP_DOMAIN_RSS,
            level = TokenManager.Level.MANAGE,
            readOnlyHint = false,
            inputSchema = McpJsonSchema.of(
                required = mapOf("rssSourceUrl" to TYPE_STRING, "key" to TYPE_STRING),
                descriptions = mapOf(
                    "rssSourceUrl" to "订阅源地址（sourceUrl）",
                    "key" to "搜索关键词",
                )
            ),
        ) { args ->
            SourceDebugKernel.rssSearchTest(
                rssSourceUrl = args.requireStr("rssSourceUrl"),
                key = args.requireStr("key"),
            )
        },

        McpTool(
            name = "rss_article_test",
            title = "订阅文章正文测试",
            description = "L3 调试工具，仅 debug 包可用。订阅文章正文测试：返回正文字符数 + 前 200 字片段。" +
                "失败时 step 指出失败环节；正文环节失败会声明「未定位到具体规则行」。",
            domain = MCP_DOMAIN_RSS,
            level = TokenManager.Level.MANAGE,
            readOnlyHint = false,
            inputSchema = McpJsonSchema.of(
                required = mapOf("rssSourceUrl" to TYPE_STRING, "link" to TYPE_STRING),
                optional = mapOf("sort" to TYPE_STRING),
                descriptions = mapOf(
                    "rssSourceUrl" to "订阅源地址（sourceUrl）",
                    "link" to "文章链接",
                    "sort" to "文章分类（可选，省略则按 link 定位）",
                )
            ),
        ) { args ->
            SourceDebugKernel.rssArticleTest(
                rssSourceUrl = args.requireStr("rssSourceUrl"),
                link = args.requireStr("link"),
                sort = args.str("sort").orEmpty(),
            )
        },

        McpTool(
            name = "validate_source",
            title = "书源校验",
            description = "L3 调试工具，仅 debug 包可用。单书源联网校验（L3 深度体检，复用质量校验件）：" +
                "返回 score/coverage/各维度状态/存疑原因/确定性失败判定。",
            domain = MCP_DOMAIN_SOURCE,
            level = TokenManager.Level.MANAGE,
            readOnlyHint = false,
            inputSchema = McpJsonSchema.singleRequiredString("sourceUrl", "书源地址（bookSourceUrl）"),
        ) { args ->
            SourceDebugKernel.validateSource(args.requireStr("sourceUrl"))
        },

        McpTool(
            name = "validate_rss_source",
            title = "订阅源校验",
            description = "L3 调试工具，仅 debug 包可用。单订阅源联网校验（L3 深度体检，复用质量校验件）：" +
                "返回 score/coverage/各维度状态/存疑原因/确定性失败判定。",
            domain = MCP_DOMAIN_RSS,
            level = TokenManager.Level.MANAGE,
            readOnlyHint = false,
            inputSchema = McpJsonSchema.singleRequiredString("rssSourceUrl", "订阅源地址（sourceUrl）"),
        ) { args ->
            SourceDebugKernel.validateRssSource(args.requireStr("rssSourceUrl"))
        },
    )
}