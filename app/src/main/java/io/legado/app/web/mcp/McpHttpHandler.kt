package io.legado.app.web.mcp

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import io.legado.app.web.api.ApiContext

/**
 * MCP 的 NanoHTTPD 适配层（web-mcp-productization 二期 · §4.2）。
 *
 * 职责仅两件：① 把 [ApiContext] 折成传输无关的 [McpHttpContext]（**级别原样透传**，
 * 它是 `HttpServer.serve()` 里 `WebAuth.verify` 的结果，协议核不重解析令牌）；
 * ② 把 [McpHttpResponse] 转成 [Response]。
 *
 * **为什么必须是 `Response` 逃生舱**：`/mcp` 的出参是 JSON-RPC 对象（`{jsonrpc,id,result|error}`），
 * 而非一期 REST 的 `ReturnData` 信封 ⇒ 借 `ApiEnvelope.dispatch` 的 `Response` 分支直出，
 * 从而 `HttpServer.kt` **零改动**（REQ-2-107 / SC-2-16）。
 */
object McpHttpHandler {

    suspend fun handle(ctx: ApiContext): Response {
        val mcpResponse = McpServer.handle(
            McpHttpContext(
                method = ctx.method.name,
                level = ctx.level,
                body = ctx.postData
            )
        )
        val status = Response.Status.lookup(mcpResponse.status) ?: object : Response.IStatus {
            override fun getRequestStatus(): Int = mcpResponse.status
            override fun getDescription(): String = mcpResponse.status.toString()
        }
        val response = NanoHTTPD.newFixedLengthResponse(
            status,
            mcpResponse.contentType,
            // 204 必须是空体（NanoHTTPD 会据实写 Content-Length: 0）
            if (mcpResponse.status == 204) "" else mcpResponse.body
        )
        mcpResponse.headers.forEach { (name, value) -> response.addHeader(name, value) }
        return response
    }
}
