package io.legado.app.web.api

import fi.iki.elonen.NanoHTTPD
import io.legado.app.web.TokenManager

/**
 * 单次请求的上下文（web-mcp-productization 一期 · 5.1）。
 *
 * 只承载"请求侧只读数据 + 统一取参口径"，**不含任何业务逻辑、不感知 Response 封装**
 * （装信封交给 [ApiEnvelope]）；这样同一份 handler 既可供 REST 调用，也可被二期 MCP 投影复用（REQ-1-504）。
 */
class ApiContext(
    val method: NanoHTTPD.Method,
    val uri: String,
    /** 查询参数 / 表单参数（NanoHTTPD 同口径：name → values）；供路由转发给 Controller / Kernel。 */
    val parameters: Map<String, List<String>>,
    /** POST 请求体（原始字符串；GET 为 null） */
    val postData: String? = null,
    /** 上传文件表（NanoHTTPD `parseBody` 的 files map；无上传为空表） */
    val files: Map<String, String> = emptyMap(),
    /**
     * 本次请求用的令牌级别（一期 3.7 审计用；未携带 / 未校验时取 [TokenManager.Level.NONE]）。
     */
    val level: TokenManager.Level = TokenManager.Level.NONE,
) {

    /** 取参数首值；不存在返回 null。 */
    fun param(name: String): String? = parameters[name]?.firstOrNull()

    /**
     * 取必填参数；缺失或为空串时抛 [IllegalArgumentException]。
     * 由 [ApiEnvelope] 统一映射为 HTTP 400（REQ-1-301），故 handler 内不必自行判空后手写错误信封。
     */
    fun requireParam(name: String, displayName: String = name): String =
        param(name)?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("参数${displayName}不能为空")

    /** 取必填请求体；缺失或为空串时抛 [IllegalArgumentException]（→ 400）。 */
    fun requirePostData(displayName: String = "请求体"): String =
        postData?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("${displayName}不能为空")

    /** 取整数参数；缺省用 [default]，非法格式抛 [IllegalArgumentException]（→ 400）。 */
    fun intParam(name: String, default: Int? = null): Int {
        val raw = param(name)
        if (raw == null) {
            return default ?: throw IllegalArgumentException("参数${name}不能为空")
        }
        return raw.toIntOrNull()
            ?: throw IllegalArgumentException("参数${name}须为整数：$raw")
    }

    /** 取长整数参数；缺省用 [default]，非法格式抛 [IllegalArgumentException]（→ 400）。 */
    fun longParam(name: String, default: Long? = null): Long {
        val raw = param(name)
        if (raw == null) {
            return default ?: throw IllegalArgumentException("参数${name}不能为空")
        }
        return raw.toLongOrNull()
            ?: throw IllegalArgumentException("参数${name}须为整数：$raw")
    }

    /**
     * 取布尔参数；缺省用 [default]，非法值抛 [IllegalArgumentException]（→ 400）。
     *
     * 与 [intParam] 同口径：**未携带 ⇒ default**；显式 `true/false`（大小写不敏感）⇒ 该值。
     * 这样可区分「未指定（沿用服务现值）」与「显式指定」两种语义。
     */
    fun boolParam(name: String, default: Boolean? = null): Boolean {
        val raw = param(name)?.trim()
        if (raw.isNullOrEmpty()) {
            return default ?: throw IllegalArgumentException("参数${name}不能为空")
        }
        return when (raw.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> throw IllegalArgumentException("参数${name}须为布尔值：$raw")
        }
    }

    /** 取上传文件；不存在抛 [IllegalArgumentException]（→ 400）。 */
    fun requireFile(name: String): String =
        files[name] ?: throw IllegalArgumentException("$name 不能为空")
}
