package io.legado.app.web.api

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import io.legado.app.api.ReturnData
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.TimeoutCancellationException
import okio.Pipe
import okio.buffer
import java.util.concurrent.TimeoutException

/**
 * 信封组装 + 异常→状态码映射 + 异步审计挂点（web-mcp-productization 一期 · 5.3）。
 *
 * 红线（REQ-1-302）：`isSuccess` / `errorMsg` / `data` **三字段语义完全不变**（老 vue 页只看这三个）；
 * `code` 为**新增字段**，与 HTTP 状态码一致（REQ-1-301）。
 */
object ApiEnvelope {

    /** 异常 → (HTTP 状态码, 信封 code) 映射表（REQ-1-301）。 */
    private fun statusCodeOf(e: Throwable): Int = when (e) {
        is IllegalArgumentException -> 400
        is NoSuchElementException -> 404
        is TimeoutCancellationException, is TimeoutException -> 504
        else -> 500
    }

    /**
     * 执行 handler 并装信封。
     *
     * - handler 返回 [Response] ⇒ 直接透传（**逃生舱**，如 `/backup` 的 ZIP 流）；
     * - handler 返回 [ReturnData] ⇒ 按 `code` 组 JSON 响应；
     * - 抛异常 ⇒ 按 [statusCodeOf] 组错误信封（**不再恒 200**）。
     */
    suspend fun dispatch(route: ApiRoute, ctx: ApiContext): Response {
        return try {
            when (val result = route.handler.handle(ctx)) {
                is Response -> result
                is ReturnData -> jsonResponse(result)
                else -> error("handler 返回类型非法：${result::class.java.name}（只允许 ReturnData 或 Response）")
            }
        } catch (e: Throwable) {
            LogUtils.d(TAG) {
                "${ctx.method.name} - ${ctx.uri} - handler 异常\n$e\n${e.stackTraceStr}"
            }
            errorResponseOf(e)
        } finally {
            // 3.7 异步审计挂点：写端点落库（REST 与 MCP 共用）。审计表在 3.6 建，故此处先留挂点。
            // 注意：审计必须"不阻塞响应"，实现时用独立 Coroutine 投递（见 design §1.4.1）。
        }
    }

    /**
     * 异常 → 错误信封响应（**真实 HTTP 状态码**，REQ-1-301）。
     *
     * `HttpServer.serve()` 的顶层 catch 复用本方法 —— 改造前该分支 `newFixedLengthResponse(e.message)`
     * **恒 200**，前端只能靠 `isSuccess` 猜对错（本期要清偿的质量债之一，任务 3.2）。
     */
    fun errorResponseOf(e: Throwable): Response =
        jsonResponse(
            ReturnData()
                .setErrorMsg(e.localizedMessage ?: e.message ?: "服务器内部错误")
                .setCode(statusCodeOf(e))
        )

    /** 构造 401 / 403 拒绝响应（供 WebAuth 复用，保证与常规信封同构）。 */
    fun deny(code: Int, message: String): Response =
        jsonResponse(ReturnData().setErrorMsg(message).setCode(code))

    /** [ReturnData] → JSON 响应（状态码取 `code`，MIME 固定 `application/json`）。 */
    fun jsonResponse(data: ReturnData): Response {
        val status = statusOf(data.code)
        val payload = data.data
        // 大列表保护（原 `HttpServer.serve()` 的性能优化，迁移到信封层以免丢失）：
        // 列表元素 > 3000 时走 Pipe 分块流式序列化，避免在内存里拼出超大 JSON 字符串。
        return if (payload is List<*> && payload.size > CHUNK_THRESHOLD) {
            val pipe = Pipe(16 * 1024)
            Coroutine.async {
                pipe.sink.buffer().outputStream().bufferedWriter(Charsets.UTF_8).use {
                    GSON.toJson(data, it)
                }
            }
            NanoHTTPD.newChunkedResponse(
                status,
                "application/json",
                pipe.source.buffer().inputStream()
            )
        } else {
            NanoHTTPD.newFixedLengthResponse(status, "application/json", GSON.toJson(data))
        }
    }

    /**
     * NanoHTTPD 的 `Status` 枚举**不含 504** 等码 ⇒ 未知码用匿名 `IStatus` 兜底，
     * 保证 `code` 与 HTTP 状态码严格一致。
     */
    private fun statusOf(code: Int): Response.IStatus =
        Response.Status.lookup(code) ?: object : Response.IStatus {
            override fun getRequestStatus(): Int = code
            override fun getDescription(): String = code.toString()
        }

    private const val TAG = "ApiEnvelope"

    /** 列表响应超过该元素数 ⇒ 走分块流式（沿用改造前阈值 3000，保证行为一致）。 */
    private const val CHUNK_THRESHOLD = 3000
}
