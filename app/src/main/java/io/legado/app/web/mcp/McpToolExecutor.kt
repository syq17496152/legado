package io.legado.app.web.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * MCP 工具执行器（web-mcp-productization 二期 · tasks 2.25 / REQ-2-104 ~ REQ-2-106）。
 *
 * 三件事（都在这里，协议核不重复实现）：
 * 1. **超时**：默认 15s，超时产出**结构化**超时信封（不是裸异常）；
 * 2. **出参信封** `{ok, data, error, elapsedMs, requestId}`（+ MCP 标准 `content` / `isError`）；
 * 3. **1MB 截断**（对齐 `AiMcpClient.MAX_MCP_RESPONSE_BYTES`，带 `truncated:true`）。
 *
 * 参数错误（[McpParamException]）**向上抛**：它在语义上属 JSON-RPC 层（映射 `-32602`），
 * 不是"工具执行结果"，故不在此处吞成信封。
 *
 * 审计落库与活动通知见 tasks §2.25 / §7.6 —— 在 [execute] 的单点挂接，避免散落到各域工具。
 */
object McpToolExecutor {

    /** 工具执行默认超时（REQ-2-106）。 */
    const val DEFAULT_TOOL_TIMEOUT_MS = 15_000L

    /** 响应体积上限（对齐 `AiMcpClient.MAX_MCP_RESPONSE_BYTES`）。 */
    const val MAX_RESPONSE_BYTES = 1_048_576

    /**
     * 执行工具并返回出参信封。
     *
     * @param timeoutMs 执行超时（默认 [DEFAULT_TOOL_TIMEOUT_MS]；参数化是为了让单测用毫秒级超时
     *        覆盖超时分支，无需真等 15 秒，也为后续"个别长任务工具放宽超时"留出接口）
     * @throws McpParamException 入参非法（由协议核映射为 `-32602`）
     */
    suspend fun execute(
        tool: McpTool,
        args: McpArgs,
        requestId: String,
        timeoutMs: Long = DEFAULT_TOOL_TIMEOUT_MS,
    ): JsonObject {
        val startedAt = System.currentTimeMillis()
        val elapsed = { System.currentTimeMillis() - startedAt }
        return try {
            val data = withTimeout(timeoutMs) { tool.invoke(args) }
            envelope(ok = true, data = McpJson.toTree(data), errorMsg = null, elapsedMs = elapsed(), requestId = requestId)
        } catch (e: TimeoutCancellationException) {
            val limit = if (timeoutMs % 1000L == 0L) "${timeoutMs / 1000}s" else "${timeoutMs}ms"
            envelope(
                ok = false,
                data = null,
                errorMsg = "工具执行超时（>$limit）：${tool.name}",
                elapsedMs = elapsed(),
                requestId = requestId
            )
        } catch (e: McpParamException) {
            throw e
        } catch (e: Throwable) {
            envelope(
                ok = false,
                data = null,
                errorMsg = e.localizedMessage ?: e.message ?: "工具执行失败",
                elapsedMs = elapsed(),
                requestId = requestId
            )
        }
    }

    /**
     * 组信封（形状与口径见 [McpServer] 类注释的**决策 #23**）：
     * 顶层信封字段（自家 `AiMcpClient` 直接 stringify 整个 `result` ⇒ AI 可读）
     * + `content[0].text` 承载载荷 JSON 文本（外部客户端按 MCP 规范渲染）。
     *
     * 截断口径：按整个信封的 **UTF-8 字节数**判定；超限时载荷截断、`data` 退化为字符串并置 `truncated:true`。
     * **已知上限**：载荷实际可用上限约为上限的一半（`content` 与 `data` 同源承载）；
     * 升级路径：大载荷改为只放 `content`，并让自家客户端改读 `content`。
     */
    fun envelope(
        ok: Boolean,
        data: JsonElement?,
        errorMsg: String?,
        elapsedMs: Long,
        requestId: String,
    ): JsonObject {
        val dataText = data?.let { McpJson.gson.toJson(it) } ?: ""
        var truncated = false

        fun assemble(embed: JsonElement, text: String): JsonObject = JsonObject().apply {
            addProperty("ok", ok)
            add("data", embed)
            add("error", errorMsg?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
            addProperty("elapsedMs", elapsedMs)
            addProperty("requestId", requestId)
            if (truncated) addProperty("truncated", true)
            add(
                "content",
                JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", text)
                    })
                }
            )
            addProperty("isError", !ok)
        }

        val full = assemble(data ?: JsonNull.INSTANCE, dataText)
        if (McpJson.utf8Size(McpJson.gson.toJson(full)) <= MAX_RESPONSE_BYTES) return full

        truncated = true
        // 收缩时必须按**最终形状**度量（`data` 与 `content.text` 各承载一份载荷）；
        // 若按"data 为 null"的形状度量，收缩结果会因 data 那一份而再次超限（首版即踩此坑）。
        var keep = dataText.length
        fun truncatedForm(limit: Int): JsonObject {
            val kept = dataText.take(limit)
            return assemble(JsonPrimitive(kept), kept)
        }
        var shrunk = truncatedForm(keep)
        while (keep > 0 && McpJson.utf8Size(McpJson.gson.toJson(shrunk)) > MAX_RESPONSE_BYTES) {
            keep = keep * 3 / 4
            shrunk = truncatedForm(keep)
        }
        return shrunk
    }
}
