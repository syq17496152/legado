package io.legado.app.web.api.routes

import fi.iki.elonen.NanoHTTPD.Method
import io.legado.app.service.kernel.DiagReadKernel
import io.legado.app.web.TokenManager.Level
import io.legado.app.web.api.ApiRoute

/**
 * C 组 · 日志与诊断路由声明（web-mcp-productization 三期 · spec 4.2，共 3 端点）。
 *
 * 复用二期 L3 读出层 [DiagReadKernel] 投影为 REST：本文件只做「声明 + 取参 + 委派」，
 * 不内联业务逻辑；`HttpServer.kt` 全程零改动。
 *
 * 级别照 V2.5 施工卡：`/getLogs`、`/getNetworkTrace` 为 MANAGE，诊断包 `/downloadDiagnostics`
 * 为 ADMIN。输出安全（URL / 凭据打码）单源在 kernel，本层不重复处理。
 */
object LogRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        // C1 应用日志查询（按级别 / 关键词 / 时间窗过滤，取最近 tail 条）
        ApiRoute(Method.GET, "/getLogs", Level.MANAGE, mcpToolName = "log_query") { ctx ->
            DiagReadKernel.logQuery(
                level = ctx.param("level"),
                keyword = ctx.param("keyword"),
                since = ctx.param("since")?.toLongOrNull(),
                tail = ctx.intParam("tail", DiagReadKernel.DEFAULT_LOG_TAIL),
            )
        },
        // C2 网络痕迹查询（`sourceUrl` 作关键词过滤，省略即不过滤）
        ApiRoute(Method.GET, "/getNetworkTrace", Level.MANAGE, mcpToolName = "network_trace_query") { ctx ->
            DiagReadKernel.networkTraceQuery(
                keyword = ctx.param("sourceUrl"),
                tail = ctx.intParam("tail", DiagReadKernel.DEFAULT_TRACE_TAIL),
            )
        },
        // C3 诊断包（结构化清单；正文按需由 C1/C2 取）
        ApiRoute(Method.GET, "/downloadDiagnostics", Level.ADMIN, mcpToolName = "diagnostics_download") { _ ->
            DiagReadKernel.diagnosticsDownload()
        },
    )
}