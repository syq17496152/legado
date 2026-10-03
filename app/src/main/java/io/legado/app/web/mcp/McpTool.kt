package io.legado.app.web.mcp

import com.google.gson.JsonObject
import io.legado.app.exception.NoStackTraceException
import io.legado.app.web.TokenManager

// ── 20 个 `domain` 取值（REQ-2-203 / tasks 2.29；源校验随 source、规则补全/订阅规则随 rule、
//    令牌随 meta、应用设置取 app、多形态覆盖漫画/图片/视频/有声书、cache 覆盖缓存/下载/云同步）──
const val MCP_DOMAIN_BOOKSHELF = "bookshelf"
const val MCP_DOMAIN_READING = "reading"
const val MCP_DOMAIN_BOOKMARK = "bookmark"
const val MCP_DOMAIN_SOURCE = "source"
const val MCP_DOMAIN_RSS = "rss"
const val MCP_DOMAIN_TTS = "tts"
const val MCP_DOMAIN_RULE = "rule"
const val MCP_DOMAIN_STATS = "stats"
const val MCP_DOMAIN_BACKUP = "backup"
const val MCP_DOMAIN_AUTOTASK = "autotask"
const val MCP_DOMAIN_DIAG = "diag"
const val MCP_DOMAIN_STORAGE = "storage"
const val MCP_DOMAIN_META = "meta"
const val MCP_DOMAIN_MULTIFORM = "multiform"
const val MCP_DOMAIN_EXPLORE = "explore"
const val MCP_DOMAIN_CHARACTER = "character"
const val MCP_DOMAIN_AI = "ai"
const val MCP_DOMAIN_APPEARANCE = "appearance"
const val MCP_DOMAIN_CACHE = "cache"
const val MCP_DOMAIN_APP = "app"

/**
 * MCP 工具声明（web-mcp-productization 二期 · §1.3.1 / REQ-2-203）。
 *
 * **单源声明**：元数据（`name`/`domain`/`level`/`readOnlyHint`/`dangerous`）与执行体 `invoke`
 * 同处一个域文件（`web/mcp/tools/` 下按域拆分）；[McpToolCatalog] 只做聚合/裁剪/查找（REQ-2-206）。
 *
 * `invoke` **只允许调 `service/kernel`**（AD-10 业务逻辑零重复），不得内联业务、不得 import
 * `api.controller`（门禁 `audit_mcp_no_controller_import.py`，REQ-2-305）。
 *
 * @param readOnlyHint 默认由 [level] 推导（**保守口径**：只有 READONLY 级工具才向 AI 声明"只读安全"）。
 *        个别"只读但级别更高"的语义变体（如 `*_test`）保持 `false`，宁严不宽。
 */
data class McpTool(
    /** 对外工具名（唯一，域前缀 + 动作，如 `bookshelf_list`）。 */
    val name: String,
    /** 人类可读标题（客户端展示用）。 */
    val title: String = name,
    /** 给 AI 看的说明：写清"做什么 / 返回什么"（REQ-2-207）。 */
    val description: String = "",
    /** 所属域（上下文压力控制第②层：按前缀分组筛选）。 */
    val domain: String = MCP_DOMAIN_META,
    /** 调用所需最低令牌级别（列表裁剪 + 调用侧第二闸的唯一判据）。 */
    val level: TokenManager.Level,
    val readOnlyHint: Boolean = level == TokenManager.Level.READONLY,
    /** 危险操作（触发端侧确认闸门，§1.5）。 */
    val dangerous: Boolean = false,
    /** 入参 JSON Schema（由 [McpJsonSchema] 生成，REQ-2-204）。 */
    val inputSchema: JsonObject = McpJsonSchema.empty(),
    /** 执行体：**只调 Kernel**。 */
    val invoke: suspend (McpArgs) -> Any?,
)

/**
 * 工具入参校验异常 ⇒ JSON-RPC `-32602`（REQ-2-204：缺失参数返回**结构化**参数错误）。
 *
 * 承项目约定（AGENTS 代码约束）：业务异常继承 [NoStackTraceException]，不打印无意义调用栈。
 */
class McpParamException(message: String) : NoStackTraceException(message)

/**
 * MCP 调用参数（从 `params.arguments` 解出的 [JsonObject]）。
 *
 * 取值口径保守：**类型不匹配一律按"缺失"处理**（不抛类型转换异常），由调用方用
 * [requireStr] / [requireInt] 显式声明必填，从而把"缺参"与"类型错"收敛到同一条 400/-32602 路径。
 */
class McpArgs(private val obj: JsonObject) {

    /** 原始参数对象（需要透传整段 JSON 时使用）。 */
    fun raw(): JsonObject = obj

    /** 取字符串参数；不存在 / JSON null / 非基本类型 → `null`。 */
    fun str(key: String): String? {
        val element = obj.get(key) ?: return null
        if (element.isJsonNull || !element.isJsonPrimitive) return null
        return element.asString
    }

    /** 取必填字符串参数；缺失或空串 → [McpParamException]。 */
    fun requireStr(key: String): String =
        str(key)?.takeIf { it.isNotEmpty() }
            ?: throw McpParamException("缺少必填参数：$key")

    /** 取整数参数；缺失或非法格式 → [def]。 */
    fun int(key: String, def: Int = 0): Int = str(key)?.toIntOrNull() ?: def

    /** 取必填整数参数；缺失或非法格式 → [McpParamException]。 */
    fun requireInt(key: String): Int =
        str(key)?.toIntOrNull()
            ?: throw McpParamException("缺少必填参数或格式非法：$key（须为整数）")

    /** 取长整数参数；缺失或非法格式 → [def]。 */
    fun long(key: String, def: Long = 0L): Long = str(key)?.toLongOrNull() ?: def

    /** 取布尔参数；`true/1/yes` 为真，`false/0/no` 为假，其余 → [def]。 */
    fun bool(key: String, def: Boolean = false): Boolean {
        val element = obj.get(key) ?: return def
        if (element.isJsonNull || !element.isJsonPrimitive) return def
        val primitive = element.asJsonPrimitive
        if (primitive.isBoolean) return primitive.asBoolean
        return when (primitive.asString.lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> def
        }
    }

    /** 取字符串列表（数组元素按字符串读取）；缺失 / 非数组 → 空列表。 */
    fun strList(key: String): List<String> {
        val element = obj.get(key) ?: return emptyList()
        if (!element.isJsonArray) return emptyList()
        return element.asJsonArray.mapNotNull { item ->
            if (item.isJsonPrimitive) item.asString else null
        }
    }
}
