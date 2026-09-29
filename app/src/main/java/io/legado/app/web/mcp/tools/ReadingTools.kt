package io.legado.app.web.mcp.tools

import io.legado.app.service.kernel.BookKernel
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_READING
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_INTEGER
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpTool

/**
 * ② 阅读域工具声明（web-mcp-productization 二期 · tasks 2.9）。
 *
 * **本文件按 tasks 2.9 增量补齐**：当前落地"执行体只需一期已有 `BookKernel` 方法"的 4 个工具；
 * 其余（阅读配置 / 菜单按钮 / 自动阅读 / 换源 / 全文搜索 / 三档刷新 / 云端进度 / EPUB / 净化 /
 * 编辑内容 / 删除书籍）随 §2.28 的新 Kernel 方法在后续批次追加。
 */
object ReadingTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "book_get_chapters",
            title = "书籍目录",
            description = "读取指定书籍的已落库目录（章节列表）。返回章节数组：" +
                "index/title/url/start/end…；空数组表示尚未拉取过目录，可先调 book_refresh_toc。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookUrl" to TYPE_STRING),
                descriptions = mapOf("bookUrl" to "书籍唯一地址（书架列表里的 bookUrl）")
            ),
        ) { args ->
            BookKernel.chapterList(args.requireStr("bookUrl"))
        },
        McpTool(
            name = "book_refresh_toc",
            title = "刷新目录",
            description = "从书源重新拉取目录并落库，返回刷新后的章节列表（写操作）。" +
                "书籍不存在或书源缺失时返回结构化错误。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookUrl" to TYPE_STRING),
                descriptions = mapOf("bookUrl" to "书籍唯一地址")
            ),
        ) { args ->
            BookKernel.refreshToc(args.requireStr("bookUrl"))
        },
        McpTool(
            name = "book_get_content",
            title = "章节正文",
            description = "读取指定章节正文（已过替换规则 / 简繁 / 重新分段的内容处理链）。" +
                "可用 offset+length 分页取长章节；返回 null 表示书籍或章节不存在。",
            domain = MCP_DOMAIN_READING,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookUrl" to TYPE_STRING, "chapterIndex" to TYPE_INTEGER),
                optional = mapOf("offset" to TYPE_INTEGER, "length" to TYPE_INTEGER),
                descriptions = mapOf(
                    "bookUrl" to "书籍唯一地址",
                    "chapterIndex" to "章节序号（从 0 开始）",
                    "offset" to "起始字符偏移（默认 0，即整章）",
                    "length" to "取多少字符（默认 -1 = 不限）"
                )
            ),
        ) { args ->
            BookKernel.bookContent(
                bookUrl = args.requireStr("bookUrl"),
                index = args.requireInt("chapterIndex"),
                offset = args.int("offset", 0),
                length = args.int("length", BookKernel.CONTENT_NO_LIMIT)
            )
        },
    )
}
