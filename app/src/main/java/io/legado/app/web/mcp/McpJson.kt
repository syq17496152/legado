package io.legado.app.web.mcp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement

/**
 * MCP 线格式序列化（web-mcp-productization 二期 · §1）。
 *
 * **为什么不复用项目全局 `GSON`**：
 * ① 全局 `GSON` 开了 `setPrettyPrinting()` —— 输出体积近乎翻倍，与 REQ-2-105 的 **1MB 上限**
 *    （对齐 `AiMcpClient.MAX_MCP_RESPONSE_BYTES`）直接冲突，且体积预算按字节算 ⇒ 美化输出会把
 *    合法响应挤成"超限截断"；
 * ② 全局 `GSON` 注册了多处**业务定制** `TypeAdapter`（Int/String/Boolean 宽松解析、书源规则子反序列化器），
 *    JSON-RPC 线格式只认严格 JSON，不应受业务宽松策略影响。
 *
 * 与全局 `GSON` 一致的语义只保留两条：**不输出 null 字段**（Gson 默认行为）、**不转义 HTML**。
 */
internal object McpJson {

    /** 紧凑输出（无缩进）、无 HTML 转义、null 字段省略。 */
    val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()

    /** 任意业务对象 → [JsonElement]；已是 [JsonElement] 则原样返回；`null` 返回 `null`。 */
    fun toTree(value: Any?): JsonElement? = when (value) {
        null -> null
        is JsonElement -> value
        else -> gson.toJsonTree(value)
    }

    /** UTF-8 字节数 —— 体积门禁必须按**字节**而非字符（中文 1 字符 = 3 字节）。 */
    fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size
}
