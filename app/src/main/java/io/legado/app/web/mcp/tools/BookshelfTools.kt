package io.legado.app.web.mcp.tools

import io.legado.app.service.kernel.BookKernel
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_BOOKSHELF
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_INTEGER
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpTool

/**
 * ① 书架与书籍域工具声明（web-mcp-productization 二期 · tasks 2.8）。
 *
 * **本文件按 tasks 2.8 增量补齐**：当前已落地"执行体只需一期既有 Kernel"的工具；
 * 其余（分组 / 标签 / 排序 / 跨源搜书 / 批量管理 / 分享）随 §2.28 的新 Kernel 方法在后续批次追加。
 *
 * 红线（AD-10）：`invoke` **只调 `service/kernel`**，不内联业务、不 import `api.controller`。
 */
object BookshelfTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "bookshelf_list",
            title = "书架列表",
            description = "读取书架全部书籍（按 App 当前书架排序模式排序）。返回书籍数组：" +
                "bookUrl/name/author/origin/group/latestChapterTitle/durChapterIndex 等字段。",
            domain = MCP_DOMAIN_BOOKSHELF,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("limit" to TYPE_INTEGER, "keyword" to TYPE_STRING),
                descriptions = mapOf(
                    "limit" to "最多返回条数（默认全部，0 或省略 = 不限）",
                    "keyword" to "按书名/作者模糊过滤（可选）"
                )
            ),
        ) { args ->
            val books = BookKernel.bookshelf()
            val keyword = args.str("keyword")?.trim().orEmpty()
            val filtered = if (keyword.isEmpty()) {
                books
            } else {
                books.filter { it.name.contains(keyword, true) || it.author.contains(keyword, true) }
            }
            val limit = args.int("limit", 0)
            if (limit > 0 && filtered.size > limit) {
                mapOf("total" to filtered.size, "truncated" to true, "books" to filtered.take(limit))
            } else {
                filtered
            }
        },
    )
}
