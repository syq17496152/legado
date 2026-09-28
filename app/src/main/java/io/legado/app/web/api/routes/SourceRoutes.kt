package io.legado.app.web.api.routes

import fi.iki.elonen.NanoHTTPD.Method
import io.legado.app.api.controller.BookSourceController
import io.legado.app.api.controller.RssSourceController
import io.legado.app.web.TokenManager.Level
import io.legado.app.web.api.ApiRoute

/**
 * 源域路由声明（web-mcp-productization 一期 · 5.4）。
 *
 * 覆盖原 `HttpServer.serve()` 中源相关的 10 个端点（书源 5 + 订阅源 5）。
 */
object SourceRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        // ---- 书源 ----
        ApiRoute(Method.GET, "/getBookSource", Level.READONLY, mcpToolName = "source_get") { ctx ->
            BookSourceController.getSource(ctx.parameters)
        },
        ApiRoute(Method.GET, "/getBookSources", Level.READONLY, mcpToolName = "source_get_all") { _ ->
            BookSourceController.sources
        },
        ApiRoute(Method.POST, "/saveBookSource", Level.MANAGE, mcpToolName = "source_save") { ctx ->
            BookSourceController.saveSource(ctx.postData)
        },
        ApiRoute(Method.POST, "/saveBookSources", Level.MANAGE, mcpToolName = "source_save_multi") { ctx ->
            BookSourceController.saveSources(ctx.postData)
        },
        ApiRoute(Method.POST, "/deleteBookSources", Level.MANAGE, mcpToolName = "source_delete") { ctx ->
            BookSourceController.deleteSources(ctx.postData)
        },

        // ---- 订阅源 ----
        ApiRoute(Method.GET, "/getRssSource", Level.READONLY, mcpToolName = "rss_source_get") { ctx ->
            RssSourceController.getSource(ctx.parameters)
        },
        ApiRoute(Method.GET, "/getRssSources", Level.READONLY, mcpToolName = "rss_source_get_all") { _ ->
            RssSourceController.sources
        },
        ApiRoute(Method.POST, "/saveRssSource", Level.MANAGE, mcpToolName = "rss_source_save") { ctx ->
            RssSourceController.saveSource(ctx.postData)
        },
        ApiRoute(Method.POST, "/saveRssSources", Level.MANAGE, mcpToolName = "rss_source_save_multi") { ctx ->
            RssSourceController.saveSources(ctx.postData)
        },
        ApiRoute(Method.POST, "/deleteRssSources", Level.MANAGE, mcpToolName = "rss_source_delete") { ctx ->
            RssSourceController.deleteSources(ctx.postData)
        },
    )
}
