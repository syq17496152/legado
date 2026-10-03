package io.legado.app.web.mcp

import androidx.annotation.Keep
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.legado.app.web.TokenManager

/** JSON-RPC 2.0 版本串（协议核常量）。 */
const val MCP_JSONRPC_VERSION = "2.0"

// ── JSON-RPC 标准错误码（REQ-2-104 的 1.4 任务逐码验证）──
/** 请求体不是合法 JSON。 */
const val MCP_RPC_PARSE_ERROR = -32700
/** 合法 JSON 但不是合法 JSON-RPC 请求（缺 method 等）。 */
const val MCP_RPC_INVALID_REQUEST = -32600
/** 方法不存在。 */
const val MCP_RPC_METHOD_NOT_FOUND = -32601
/** 参数缺失 / 类型非法。 */
const val MCP_RPC_INVALID_PARAMS = -32602
/** 工具执行内部错误。 */
const val MCP_RPC_INTERNAL_ERROR = -32603

// ── MCP 扩展错误码（JSON-RPC 保留的 -32000 ~ -32099 服务端自定义区间）──
/** 令牌级别不足以调用该工具（REQ-2-302 / SC-2-04，HTTP 侧同步返 403）。 */
const val MCP_RPC_INSUFFICIENT_LEVEL = -32002
/** 工具执行超时（REQ-2-106：必须返回**结构化** timeout，而不是裸异常）。 */
const val MCP_RPC_TOOL_TIMEOUT = -32003

/**
 * JSON-RPC 请求（web-mcp-productization 二期 · §1.2.1）。
 *
 * 标 [Keep] 的理由（承 AGENTS 规则 7）：该模型由 Gson 反射填充，R8 会剥离未 pin 成员的
 * 泛型签名与字段名 ⇒ 反序列化退化成空对象。虽然本模型当前**无集合字段**，仍按"Gson 反序列化模型
 * 一律 `@Keep`"的统一口径处理，避免后续加集合字段时漏 pin。
 */
@Keep
data class JsonRpcRequest(
    val jsonrpc: String = MCP_JSONRPC_VERSION,
    /** 通知（notification）无 `id`；有 `id` 才是请求/响应配对。 */
    val id: JsonElement? = null,
    val method: String = "",
    val params: JsonObject? = null,
)

/** JSON-RPC 响应（`result` 与 `error` 互斥）。 */
@Keep
data class JsonRpcResponse(
    val jsonrpc: String = MCP_JSONRPC_VERSION,
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

/** JSON-RPC 错误对象。 */
@Keep
data class JsonRpcError(
    val code: Int = 0,
    val message: String = "",
    val data: JsonElement? = null,
)

/**
 * MCP 会话（**无状态实现**：每请求从 `Authorization` 头重建，不做服务端会话表）。
 *
 * 无状态的理由见 design §1.2.1：MCP 与 REST 同生共死（同属 Web 服务），引入会话表会带来
 * 过期回收 / 并发竞争两类额外复杂度，而本产品单用户场景没有会话语义需求。
 */
data class McpSession(
    /** 本次请求的令牌级别（**第一闸** `WebAuth.verify` 的结果，由 HTTP 层原样传入）。 */
    val level: TokenManager.Level,
    val sessionId: String = "",
    val protocolVersion: String = McpServer.PROTOCOL_VERSION,
    val clientInfo: String = "",
)

/**
 * 协议核的**传输无关**入参（web-mcp-productization 二期 · §1.2.2）。
 *
 * 存在的意义：让 [McpServer] 完全不依赖 NanoHTTPD ⇒ 单测可直接构造上下文跑四方法主路径，
 * 无需起真实 HTTP 服务（对齐一期 `ApiContext` "请求侧只读数据"的同一思路）。
 */
data class McpHttpContext(
    /** HTTP 方法（`POST` / `GET` / `DELETE`）。 */
    val method: String,
    /** 令牌级别 —— 由 HTTP 层 [io.legado.app.web.WebAuth] 解析 `Authorization` 后传入，协议核不重解析。 */
    val level: TokenManager.Level = TokenManager.Level.NONE,
    /** 请求体（POST 才有；由 NanoHTTPD `parseBody` 解析出的 `postData`）。 */
    val body: String? = null,
)

/** 协议核的**传输无关**出参（由 `McpHttpHandler` 转成 `NanoHTTPD.Response`）。 */
data class McpHttpResponse(
    val status: Int = 200,
    val contentType: String = "application/json",
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
)

