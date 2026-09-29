package io.legado.app.web.mcp.tools

import io.legado.app.service.kernel.BookSourceKernel
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_SOURCE
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpTool

/**
 * ④ 书源域工具声明（web-mcp-productization 二期 · tasks 2.11）。
 *
 * **本文件按 tasks 2.11 增量补齐**：当前落地"执行体只需一期 `BookSourceKernel`"的读工具；
 * 写类（`source_save` 的 **temp 沙箱语义** / `source_delete` 的**端侧确认** / 导入 / 启停 /
 * 分组 / 回收站 / 导出 / 二维码 / 源变量 / 质量报告）随 §2.28 与 §5.10 在后续批次追加——
 * 这些工具依赖尚不存在的能力（temp 标记区、确认闸门），**先落地会让语义不成立**，故不提前声明。
 */
object SourceTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "source_get",
            title = "读取书源",
            description = "读取书源：带 url 返回单条书源完整 JSON（含规则字段）；不带 url 返回全部书源数组。" +
                "AI 修改规则前应先读一次拿到当前规则原文。",
            domain = MCP_DOMAIN_SOURCE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("url" to TYPE_STRING),
                descriptions = mapOf("url" to "书源地址（bookSourceUrl）；省略则返回全部")
            ),
        ) { args ->
            val url = args.str("url")?.trim().orEmpty()
            if (url.isEmpty()) BookSourceKernel.sources() else BookSourceKernel.source(url)
        },
    )
}
