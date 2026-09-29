package io.legado.app.web.api.routes

import fi.iki.elonen.NanoHTTPD.Method
import io.legado.app.web.TokenManager.Level
import io.legado.app.web.api.ApiRoute
import io.legado.app.web.mcp.McpHttpHandler

/**
 * MCP 路由声明（web-mcp-productization 二期 · tasks 4.1 / REQ-2-107）。
 *
 * `/mcp` 是**一条注册路由**（不是新的 HTTP 服务）⇒ `HttpServer.serve()` **零改动**（SC-2-16）。
 *
 * **级别口径 = READONLY**：路由级鉴权只做"最低门槛"（过渡期 READONLY 端点免令牌，与老页零破坏口径一致），
 * 真正的**双闸**在协议核内 —— 第一闸由 `McpHttpHandler` 收到的 `ctx.level` 判定（NONE → 401），
 * 第二闸按 `McpTool.level` 裁剪与比较。若把本路由声明为更高（如 MANAGE），
 * 会让"只读令牌连 `tools/list` 都进不去"，与 AD-2-02 的分级裁剪设计冲突。
 */
object McpRoutes {

    const val PATH = "/mcp"

    val routes: Array<ApiRoute> = arrayOf(
        ApiRoute(Method.POST, PATH, Level.READONLY) { ctx -> McpHttpHandler.handle(ctx) },
        ApiRoute(Method.GET, PATH, Level.READONLY) { ctx -> McpHttpHandler.handle(ctx) },
        ApiRoute(Method.DELETE, PATH, Level.READONLY) { ctx -> McpHttpHandler.handle(ctx) },
    )
}
