package io.legado.app.web.api.routes

import fi.iki.elonen.NanoHTTPD.Method
import io.legado.app.api.controller.BookController
import io.legado.app.web.TokenManager.Level
import io.legado.app.web.api.ApiRoute

/**
 * 书籍域路由声明（web-mcp-productization 一期 · 5.4 / SC-1-03）。
 *
 * 覆盖原 `HttpServer.serve()` 中书相关的 12 个端点（POST 6 + GET 6）。
 * **级别只在此声明**（唯一真源，REQ-1-106）。
 */
object BookRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        // ---- 读（readonly）----
        ApiRoute(Method.GET, "/getBookshelf", Level.READONLY, mcpToolName = "bookshelf_list") { _ ->
            BookController.bookshelf
        },
        ApiRoute(Method.GET, "/getChapterList", Level.READONLY, mcpToolName = "book_get_chapters") { ctx ->
            BookController.getChapterList(ctx.parameters)
        },
        ApiRoute(Method.GET, "/refreshToc", Level.READONLY, mcpToolName = "book_refresh_toc") { ctx ->
            BookController.refreshToc(ctx.parameters)
        },
        ApiRoute(Method.GET, "/getBookContent", Level.READONLY, mcpToolName = "book_get_content") { ctx ->
            BookController.getBookContent(ctx.parameters)
        },
        ApiRoute(Method.GET, "/cover", Level.READONLY) { ctx ->
            BookController.getCover(ctx.parameters)
        },
        ApiRoute(Method.GET, "/image", Level.READONLY) { ctx ->
            BookController.getImg(ctx.parameters)
        },
        ApiRoute(Method.GET, "/getReadConfig", Level.READONLY, mcpToolName = "read_config_get") { _ ->
            BookController.getWebReadConfig()
        },

        // ---- 写（manage）----
        ApiRoute(Method.POST, "/saveBook", Level.MANAGE, mcpToolName = "book_save_info") { ctx ->
            BookController.saveBook(ctx.postData)
        },
        ApiRoute(Method.POST, "/deleteBook", Level.MANAGE, mcpToolName = "book_delete") { ctx ->
            BookController.deleteBook(ctx.postData)
        },
        ApiRoute(Method.POST, "/saveBookProgress", Level.MANAGE, mcpToolName = "book_save_progress") { ctx ->
            BookController.saveBookProgress(ctx.postData)
        },
        // ⚠️ **陷阱端点（SC-1-03）**：`addLocalBook` 的调用页在 `/uploadBook/` 目录下，
        // 但它是**写端点** ⇒ 级别必须为 [Level.MANAGE]，**不得按 URL 前缀判为白名单放行**。
        ApiRoute(Method.POST, "/addLocalBook", Level.MANAGE, mcpToolName = "book_add_local") { ctx ->
            BookController.addLocalBook(ctx.parameters, ctx.files)
        },
        ApiRoute(Method.POST, "/saveReadConfig", Level.MANAGE, mcpToolName = "read_config_save") { ctx ->
            BookController.saveWebReadConfig(ctx.postData)
        },
    )
}
