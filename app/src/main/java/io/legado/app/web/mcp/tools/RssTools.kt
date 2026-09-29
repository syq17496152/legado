package io.legado.app.web.mcp.tools

import io.legado.app.service.kernel.RssSourceKernel
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_RSS
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpTool

/**
 * ⑥ 订阅域工具声明（web-mcp-productization 二期 · tasks 2.12）。
 *
 * **本文件按 tasks 2.12 增量补齐**：当前落地"执行体只需一期 `RssSourceKernel`"的读工具；
 * 其余（保存 / 删除 / 导入 / 启停 / 分组 / 排序 / 文章列表 / 文章正文 / 已读 / OPML / 收藏夹 /
 * 跨源搜索 / 文章详情）随 §2.28 的新 Kernel 方法在后续批次追加。
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
    )
}
