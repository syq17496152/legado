package io.legado.app.web.mcp.tools

import io.legado.app.service.kernel.DiagKernel
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_DIAG
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_ARRAY
import io.legado.app.web.mcp.McpJsonSchema.TYPE_INTEGER
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpParamException
import io.legado.app.web.mcp.McpTool

/**
 * ⑬ 诊断域（release 面）工具声明（web-mcp-productization 二期 · tasks 2.19 / 2.28）。
 *
 * **只声明 release 面 5 个工具**：`cache_clear` / `url_record_query` / `url_record_clear` /
 * `log_dump` / `log_delete`。L3 调试观测工具（`log_query` / `crash_query` /
 * `network_trace_query` / `diagnostics_download` / `perf_metrics_get`）属 debug 面，
 * 由 `DebugToolProvider` 按变体拆 sourceSet 提供，**不在本文件**。
 *
 * 红线（AD-10）：`invoke` **只调 `service/kernel`**（[DiagKernel]），不内联业务、
 * 不 import `api.controller`（REQ-2-305）。
 */
object DiagTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "cache_clear",
            title = "清理缓存",
            description = "清理**可重建缓存**（白名单：books 书籍文本 / video 视频 / audio 音频 / " +
                "webview WebView 数据 / all 全部）；不触碰用户数据（书架/书源/记录均不在其列）。" +
                "视频播放中、朗读运行中命中对应目录会跳过并在 skipped 里如实回告。返回 " +
                "cleared/skipped/freedBytes/needRestart。",
            domain = MCP_DOMAIN_DIAG,
            level = TokenManager.Level.ADMIN,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("kind" to TYPE_STRING),
                descriptions = mapOf("kind" to "缓存类别：books/video/audio/webview/all（默认 all）"),
            ),
        ) { args ->
            DiagKernel.cacheClear(args.str("kind") ?: DiagKernel.KIND_ALL)
        },

        McpTool(
            name = "url_record_query",
            title = "查询访问记录",
            description = "查询 URL 访问记录（可按 keyword / domain 过滤）。URL 与错误文本里的敏感 query 参数" +
                "（token/key/secret/…）一律打码为 `***`。返回 total/returned/records。",
            domain = MCP_DOMAIN_DIAG,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf(
                    "keyword" to TYPE_STRING,
                    "domain" to TYPE_STRING,
                    "limit" to TYPE_INTEGER,
                ),
                descriptions = mapOf(
                    "keyword" to "按关键词过滤（可选）",
                    "domain" to "按域名过滤（可选）",
                    "limit" to "最多返回条数（默认 200，≤0 = 不限）",
                )
            ),
        ) { args ->
            DiagKernel.urlRecordQuery(
                keyword = args.str("keyword"),
                domain = args.str("domain"),
                limit = args.int("limit", 200),
            )
        },

        McpTool(
            name = "url_record_clear",
            title = "清理访问记录",
            description = "按天数清理历史 URL 访问记录（days ≤ 0 表示清空全部）。返回 before/removed/days。",
            domain = MCP_DOMAIN_DIAG,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("days" to TYPE_INTEGER),
                descriptions = mapOf("days" to "保留最近多少天（默认 7；≤0 = 清空全部）"),
            ),
        ) { args ->
            DiagKernel.urlRecordClear(args.int("days", 7))
        },

        McpTool(
            name = "log_dump",
            title = "触发堆转储",
            description = "建立堆转储（写入 externalCache/heapDump/*.hprof，异步写盘）。返回 " +
                "triggered/dir/before/note；落盘后可用 `log_delete(heap)` 清理。",
            domain = MCP_DOMAIN_DIAG,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            DiagKernel.logDump()
        },

        McpTool(
            name = "log_delete",
            title = "删除日志",
            description = "删除日志文件：targets 取值 app（应用运行日志）/ crash（崩溃日志文件）/ " +
                "heap（堆转储文件），可多项串联。返回 deleted（逐项删除条数）。",
            domain = MCP_DOMAIN_DIAG,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("targets" to TYPE_ARRAY),
                descriptions = mapOf("targets" to "日志目标数组：app/crash/heap"),
            ),
        ) { args ->
            val targets = args.strList("targets")
            if (targets.isEmpty()) throw McpParamException("缺少必填参数：targets（须为非空字符串数组）")
            DiagKernel.logDelete(targets)
        },
    )
}