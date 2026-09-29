package io.legado.app.web.mcp.tools

import io.legado.app.service.kernel.DiagReadKernel
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_DIAG
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_INTEGER
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpTool

/**
 * ⑬ 诊断域 **L3 调试观测工具**（web-mcp-productization 二期 · tasks 5.x；**debug sourceSet**）。
 *
 * 红线（REQ-2-403）：L3 工具**只能存在于 debug 变体**（按变体拆 sourceSet，release 包内物理不存在）。
 * 本文件与 [io.legado.app.web.mcp.DebugToolProvider]（debug 侧真实实现）同属 debug 面，
 * 由后者聚合进 `DebugToolProvider.tools`。
 *
 * 红线（AD-10）：`invoke` **只调 `service/kernel`**（[DiagReadKernel]），不内联业务、
 * 不 import `api.controller`、不触 `appDb` / `Dao`。
 *
 * 级别口径：4 个工具一律 `MANAGE` + `readOnlyHint = false`（L3 按"宁严不宽"）。
 * 输出口径：URL 与凭据类敏感参数已在 Kernel 侧打码，回传值不含凭据原文。
 */
object DiagnosticsTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "log_query",
            title = "查询应用日志",
            description = "L3 调试工具，仅 debug 包可用。按级别 / 关键词 / 时间下限过滤应用运行日志" +
                "（内存环形缓冲，最新在前），返回最近 tail 条。返回 total/returned/entries" +
                "（entries 含 time/level/message/tag；tag 当前恒为 null）。日志文本中的 URL 敏感参数已打码。",
            domain = MCP_DOMAIN_DIAG,
            level = TokenManager.Level.MANAGE,
            readOnlyHint = false,
            inputSchema = McpJsonSchema.of(
                optional = mapOf(
                    "level" to TYPE_STRING,
                    "keyword" to TYPE_STRING,
                    "since" to TYPE_INTEGER,
                    "tail" to TYPE_INTEGER,
                ),
                descriptions = mapOf(
                    "level" to "级别过滤：error/warn/info/debug（省略 = 不过滤）",
                    "keyword" to "关键词（对日志文本不区分大小写；省略 = 不过滤）",
                    "since" to "时间下限（毫秒时间戳；省略 = 不过滤）",
                    "tail" to "最多返回条数（默认 100；≤0 = 不限）",
                )
            ),
        ) { args ->
            DiagReadKernel.logQuery(
                level = args.str("level"),
                keyword = args.str("keyword"),
                since = if (args.raw().has("since")) args.long("since") else null,
                tail = args.int("tail", DiagReadKernel.DEFAULT_LOG_TAIL),
            )
        },

        McpTool(
            name = "crash_query",
            title = "查询崩溃日志",
            description = "L3 调试工具，仅 debug 包可用。读取最近一次崩溃日志全文" +
                "（externalCache/crash 下最新一份，只读，不触发崩溃处理器注册）。返回 " +
                "found/length/truncated/log。日志内 URL 与凭据类敏感参数已打码。",
            domain = MCP_DOMAIN_DIAG,
            level = TokenManager.Level.MANAGE,
            readOnlyHint = false,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("limit" to TYPE_INTEGER),
                descriptions = mapOf("limit" to "返回文本最大字符数（默认 4000；≤0 = 不限）"),
            ),
        ) { args ->
            DiagReadKernel.crashQuery(args.int("limit", DiagReadKernel.DEFAULT_CRASH_LIMIT))
        },

        McpTool(
            name = "network_trace_query",
            title = "查询网络痕迹",
            description = "L3 调试工具，仅 debug 包可用。查询网络请求痕迹（优先内存缓冲，" +
                "为空则退回读 externalCache/logs 持久化日志）。返回 enabled/source/total/returned/" +
                "entries。URL 与响应片段 / 错误文本的敏感参数已打码；请求响应头不回传。",
            domain = MCP_DOMAIN_DIAG,
            level = TokenManager.Level.MANAGE,
            readOnlyHint = false,
            inputSchema = McpJsonSchema.of(
                optional = mapOf(
                    "keyword" to TYPE_STRING,
                    "tail" to TYPE_INTEGER,
                    "limit" to TYPE_INTEGER,
                ),
                descriptions = mapOf(
                    "keyword" to "关键词过滤（URL/来源/类型/方法/状态码/响应片段/错误；省略 = 不过滤）",
                    "tail" to "最多返回条数（默认 50；≤0 = 不限）",
                    "limit" to "单条文本最大字符数（默认 20000；≤0 = 不限，另受总预算约束）",
                )
            ),
        ) { args ->
            DiagReadKernel.networkTraceQuery(
                keyword = args.str("keyword"),
                tail = args.int("tail", DiagReadKernel.DEFAULT_TRACE_TAIL),
                limit = args.int("limit", DiagReadKernel.DEFAULT_TRACE_LIMIT),
            )
        },

        McpTool(
            name = "diagnostics_download",
            title = "组装诊断包清单",
            description = "L3 调试工具，仅 debug 包可用。组装诊断包**结构化清单**：应用日志摘要 / " +
                "崩溃日志摘要 / 网络痕迹摘要 / 设备信息 / 性能指标，逐段给出来源与字节数。" +
                "只回清单不落盘、不回传全文（正文请分别调 log_query / crash_query / " +
                "network_trace_query）；敏感参数已打码。",
            domain = MCP_DOMAIN_DIAG,
            level = TokenManager.Level.MANAGE,
            readOnlyHint = false,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            DiagReadKernel.diagnosticsDownload()
        },
    )
}