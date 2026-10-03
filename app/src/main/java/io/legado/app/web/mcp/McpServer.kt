package io.legado.app.web.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.legado.app.BuildConfig
import io.legado.app.web.TokenManager
import io.legado.app.web.WebAuth
import java.util.UUID

/**
 * MCP 协议核（web-mcp-productization 二期 · §1 / REQ-2-101 ~ REQ-2-108）。
 *
 * **设计要点**
 * 1. **无状态**：每请求从 `Authorization` 头重建级别（[McpHttpContext.level]），服务端不存会话表；
 * 2. **双闸**：第一闸 = 令牌级别（本类的 `handle` 首步）；第二闸 = 工具级别（`tools/list` 裁剪 +
 *    `tools/call` 比较），级别唯一真源是 `McpTool.level`（承一期「不另建路由表」的口径）；
 * 3. **出参信封** `{ok,data,error,elapsedMs,requestId}`（REQ-2-104）；
 * 4. **1MB 截断**：与 `AiMcpClient.MAX_MCP_RESPONSE_BYTES` 对齐，超限带 `truncated:true`（REQ-2-105）；
 * 5. **15s 超时**：返回**结构化**超时信封而非裸异常（REQ-2-106）。
 *
 * **协议版本协商口径（实施修订，2026-09-29 · 决策 #22）**：
 * 设计 §1.2.2 要求"`Mcp-Protocol-Version` 头可协商"，但 REQ-2-107 同时要求
 * **`HttpServer.kt` 零改动** —— 而 `ApiContext` 不携带请求头（加字段就必须改 `HttpServer.serve()` 的构造点），
 * 两条要求互斥。落地口径：**服务端恒以自身支持的 `2025-06-18` 应答（服务端版本优先）**，
 * 忽略客户端请求的版本号 —— 这正是 MCP 规范对"不支持客户端版本"的规定动作
 * （"服务端 MUST 用自己支持的版本应答"），因此**不构成能力缺失**，且无需动 `HttpServer`。
 *
 * **MCP 标准兼容（实施修订，2026-09-29 · 决策 #23）**：
 * 设计 §1.2.2 把 `tools/call` 的 `result` 定为纯信封，但 App 自带 `AiMcpClient` 只做
 * `result.toString()`（不作形状校验），而**外部客户端（Claude Desktop 等）按 MCP 规范要求 `content` 数组**
 * ⇒ 二者必须同时满足。落地口径：`result` = **信封字段（顶层）+ MCP 标准 `content`/`isError`**，
 * 其中 `content[0].text` 承载**载荷 JSON 文本**（外部客户端可见），顶层 `data` 承载同名载荷（自家客户端可见）。
 */
object McpServer {

    /** 协议版本（对齐已有客户端 `AiMcpClient.PROTOCOL_VERSION`，REQ-2-103）。 */
    const val PROTOCOL_VERSION = "2025-06-18"

    /**
     * `tools/list` 单页条数。
     *
     * 取值 500（**大于当前工具总数上限 232**）⇒ 正常情况一页发完，`nextCursor` 不出现；
     * 但分页机制本身完整实现（REQ-2-101 要求 cursor 分页），以便客户端协议一致、未来扩量时不改协议。
     */
    private const val PAGE_SIZE = 500

    private const val SERVER_NAME = "legadoM"

    /** 第一闸拒绝话术：**统一为 `unauthorized`**（对齐 SC-2-02 的断言），附加字段另给可用性提示。 */
    private const val UNAUTHORIZED_MESSAGE = "unauthorized"
    private const val UNAUTHORIZED_HINT =
        "请在请求头携带 Authorization: Bearer <令牌>（可在 App 的「Web 服务与 AI 接入」设置中生成）"

    /**
     * HTTP 入口分发（REQ-2-102）。
     *
     * `POST` 主路径 / `GET` SSE（**本期降级 405**，见 [handleGet]）/ `DELETE` 会话关闭（204）。
     */
    suspend fun handle(ctx: McpHttpContext): McpHttpResponse = when (ctx.method.uppercase()) {
        "POST" -> handlePost(ctx)
        "GET" -> handleGet()
        "DELETE" -> McpHttpResponse(204, body = "")
        else -> McpHttpResponse(
            status = 405,
            body = plainEnvelope(405, "不支持的 HTTP 方法：${ctx.method}"),
            headers = mapOf("Allow" to ALLOW_METHODS)
        )
    }

    /**
     * `GET /mcp`（SSE 流）。
     *
     * **本期口径 = 降级 405**：REQ-2-102 的判据明列"GET 不支持时返 405"，且 design §1.2.3
     * 明确"SSE 不是主路径，实现失败不阻断交付"；SC-2-18 要验证的"客户端不支持 SSE 时 POST 仍工作"
     * 正是本口径。降级而非半成品流：返回 405 + `Allow` 头，客户端可据此回退 POST，不会静默卡死。
     */
    private fun handleGet(): McpHttpResponse = McpHttpResponse(
        status = 405,
        body = plainEnvelope(405, "本服务仅支持 POST 传输（StreamableHTTP），GET/SSE 未启用"),
        headers = mapOf("Allow" to ALLOW_METHODS)
    )

    private suspend fun handlePost(ctx: McpHttpContext): McpHttpResponse {
        // ── 第一闸（REQ-2-301）：令牌级别由 HTTP 层解析后传入；不足 READONLY ⇒ 401，协议层不进入 ──
        if (!WebAuth.allow(ctx.level, TokenManager.Level.READONLY)) {
            return McpHttpResponse(
                status = 401,
                body = plainEnvelope(401, UNAUTHORIZED_MESSAGE, UNAUTHORIZED_HINT)
            )
        }
        val raw = ctx.body
        if (raw.isNullOrBlank()) {
            return rpcError(null, MCP_RPC_PARSE_ERROR, "请求体为空", 400)
        }
        val request = runCatching { McpJson.gson.fromJson(raw, JsonRpcRequest::class.java) }.getOrNull()
            ?: return rpcError(null, MCP_RPC_PARSE_ERROR, "请求体不是合法 JSON-RPC 对象", 400)
        if (request.method.isBlank()) {
            return rpcError(request.id, MCP_RPC_INVALID_REQUEST, "缺少 method 字段", 200)
        }
        // JSON-RPC 通知（无 id）：不需要响应体 —— REQ-2-101 要求 notifications/initialized 返 202
        val isNotification = request.id == null
        if (isNotification && request.method != "notifications/initialized") {
            return McpHttpResponse(202, body = "")
        }
        val session = McpSession(level = ctx.level)
        return when (request.method) {
            "initialize" -> rpcResult(request.id, onInitialize(), 200)
            "notifications/initialized" -> McpHttpResponse(202, body = "")
            "tools/list" -> rpcResult(request.id, onToolsList(request.params, session), 200)
            "tools/call" -> onToolsCall(request.id, request.params, session)
            else -> rpcError(request.id, MCP_RPC_METHOD_NOT_FOUND, "未知方法：${request.method}", 200)
        }
    }

    /** `initialize`：返回协议版本 + 能力声明 + 服务端信息（REQ-2-101 / REQ-2-103）。 */
    private fun onInitialize(): JsonObject {
        val capabilities = JsonObject().apply {
            add("tools", JsonObject().apply { addProperty("listChanged", false) })
        }
        val serverInfo = JsonObject().apply {
            addProperty("name", SERVER_NAME)
            addProperty("version", BuildConfig.VERSION_NAME)
        }
        return JsonObject().apply {
            addProperty("protocolVersion", PROTOCOL_VERSION)
            add("capabilities", capabilities)
            add("serverInfo", serverInfo)
        }
    }

    /** `tools/list`：**按令牌级别裁剪**（第二闸·列表侧，REQ-2-303 / SC-2-03）。 */
    private fun onToolsList(params: JsonObject?, session: McpSession): JsonObject {
        val visible = McpToolCatalog.visibleFor(session.level)
        val cursor = params?.get("cursor")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull() ?: 0
        val start = cursor.coerceIn(0, visible.size)
        val page = visible.drop(start).take(PAGE_SIZE)
        val array = JsonArray()
        page.forEach { array.add(describe(it)) }
        return JsonObject().apply {
            add("tools", array)
            if (start + page.size < visible.size) {
                addProperty("nextCursor", (start + page.size).toString())
            }
        }
    }

    /** 单个工具的对外描述（MCP 标准字段 + `domain` 扩展字段）。 */
    private fun describe(tool: McpTool): JsonObject = JsonObject().apply {
        addProperty("name", tool.name)
        addProperty("title", tool.title)
        addProperty("description", tool.description)
        // 非标准扩展字段：上下文压力控制第②层（客户端可按域折叠/筛选），MCP 客户端会忽略未知字段
        addProperty("domain", tool.domain)
        add("inputSchema", tool.inputSchema)
        add(
            "annotations",
            JsonObject().apply {
                addProperty("readOnlyHint", tool.readOnlyHint)
                addProperty("destructiveHint", tool.dangerous)
                addProperty("idempotentHint", tool.readOnlyHint)
                addProperty("openWorldHint", false)
            }
        )
    }

    /**
     * `tools/call`：第二闸·调用侧（REQ-2-302 / SC-2-04）+ 执行 + 信封。
     *
     * 级别不足返 **HTTP 403**（SC-2-04 明确断言状态码），响应体仍是 JSON-RPC 错误对象
     * （外部 MCP 客户端按 JSON-RPC 解析错误）。
     */
    private suspend fun onToolsCall(
        id: JsonElement?,
        params: JsonObject?,
        session: McpSession,
    ): McpHttpResponse {
        val name = params?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
        if (name.isNullOrBlank()) {
            return rpcError(id, MCP_RPC_INVALID_PARAMS, "缺少必填参数：name", 200)
        }
        val tool = McpToolCatalog.find(name)
            ?: return rpcError(id, MCP_RPC_INVALID_PARAMS, "工具不存在：$name", 200)
        if (!WebAuth.allow(session.level, tool.level)) {
            return rpcError(
                id = id,
                code = MCP_RPC_INSUFFICIENT_LEVEL,
                message = "权限不足：需要 ${tool.level} 级别，当前为 ${session.level} 级别",
                status = 403
            )
        }
        val arguments = params.get("arguments")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val requestId = id?.let { if (it.isJsonPrimitive) it.asString else it.toString() }
            ?: UUID.randomUUID().toString()
        val envelope = try {
            McpToolExecutor.execute(tool, McpArgs(arguments), requestId)
        } catch (e: McpParamException) {
            // 参数非法属 JSON-RPC 层语义（REQ-2-204：缺失参数须返**结构化**参数错误）
            return rpcError(id, MCP_RPC_INVALID_PARAMS, e.message ?: "参数非法", 200)
        }
        return rpcResult(id, envelope, 200)
    }

    private fun rpcResult(id: JsonElement?, result: JsonObject, status: Int): McpHttpResponse =
        McpHttpResponse(
            status = status,
            body = McpJson.gson.toJson(JsonRpcResponse(id = id, result = result))
        )

    private fun rpcError(id: JsonElement?, code: Int, message: String, status: Int): McpHttpResponse =
        McpHttpResponse(
            status = status,
            body = McpJson.gson.toJson(JsonRpcResponse(id = id, error = JsonRpcError(code, message)))
        )

    /**
     * REST 风格错误信封（仅用于**协议层之前/之外**的三类响应：401 未授权、405 方法不支持）。
     *
     * 为什么这里不用 JSON-RPC 错误体：这三类发生在"还没有（或不存在）JSON-RPC 请求"时，
     * 且 SC-2-02 明确要求 401 的响应体是 `{"errorMsg":"unauthorized", …}`（与一期 REST 信封同构）。
     */
    private fun plainEnvelope(code: Int, message: String, hint: String? = null): String =
        JsonObject().apply {
            addProperty("isSuccess", false)
            addProperty("errorMsg", message)
            addProperty("code", code)
            hint?.let { addProperty("hint", it) }
        }.let { McpJson.gson.toJson(it) }

    private const val ALLOW_METHODS = "POST, DELETE"
}
