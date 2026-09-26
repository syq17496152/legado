package io.legado.app.help.ai

import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.ui.main.ai.AiChatException
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.utils.GSON

/**
 * Provider 余额查询（配置式）。
 *
 * 与上游「内置 7 家供应商端点检测 + 配置式」不同，本仓 Provider 完全由用户自建（无内置表），
 * 因此只保留**配置式**通道：用户在 Provider 编辑页填 `balanceUrl`（相对 baseUrl 的路径或绝对地址）
 * 与 `balanceJsonPath`（取值路径），查询时用同一 OkHttp 客户端族发一次 GET。
 *
 * 路径语义（与上游逐字对齐，见 `AiBalanceProvider.kt` 上游实现）：
 * - `a.b[0].c`：逐段下钻；支持同一段内多个下标（`a[0][1]`）；任一段缺失/类型不符 ⇒ 整体 null；
 * - 减号表达式 `a.b - c[0]`：**减号两侧必须有空白**，按「首项减后续各项」逐项相减；
 *   任一参与项取不到数值 ⇒ 整体 null（不做 0 兜底）；无空白连写（`a-b`）视为单个键名，不做减法；
 * - JSON 对象以 `Map`、数组以 `List` 承接（由 [GSON] 解析），故本类全部解析逻辑为**纯函数**、可纯 JVM 单测。
 */
object AiBalanceProvider {

    /** 单条余额（本仓配置式通道恒为 1 条，保留列表结构以便将来扩展） */
    data class AiBalanceInfo(
        val name: String,
        val remaining: Double?
    )

    data class AiBalanceResult(
        val providerName: String,
        val items: List<AiBalanceInfo>
    )

    /** 减号表达式分隔符：要求减号两侧至少一个空白字符 */
    private val minusPattern = Regex("""\s+-\s+""")

    /** 段内下标提取：支持 `key[0][1]` */
    private val indexPattern = Regex("""\[(\d+)]""")

    /**
     * 余额接口地址归一化：
     * - 绝对地址（`http(s)://…`）原样使用；
     * - 相对路径拼接到 baseUrl 的**根地址**（剥掉 `/chat/completions`、`/responses` 后缀）。
     */
    internal fun resolveBalanceUrl(baseUrl: String, balanceUrl: String): String {
        val raw = balanceUrl.trim()
        if (raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true)
        ) {
            return raw
        }
        val base = baseUrl.trim().trimEnd('/')
        val root = when {
            base.endsWith("/chat/completions") -> base.removeSuffix("/chat/completions")
            base.endsWith("/responses") -> base.removeSuffix("/responses")
            else -> base
        }
        return if (raw.isEmpty()) root else "$root/${raw.trimStart('/')}"
    }

    /** 按路径取值；空路径 ⇒ 返回根对象本身 */
    internal fun valueByPath(root: Any?, path: String): Any? {
        val expression = path.trim()
        if (expression.isEmpty()) return root
        var current: Any? = root
        for (part in expression.split('.')) {
            val segment = part.trim()
            if (segment.isEmpty()) continue
            current = childByPathPart(current, segment)
            if (current == null) return null
        }
        return current
    }

    private fun childByPathPart(current: Any?, part: String): Any? {
        val key = part.substringBefore('[').trim()
        var node: Any? = current
        if (key.isNotEmpty()) {
            node = (node as? Map<*, *>)?.get(key) ?: return null
        }
        indexPattern.findAll(part).forEach { match ->
            val index = match.groupValues[1].toIntOrNull() ?: return null
            val list = node as? List<*> ?: return null
            if (index < 0 || index >= list.size) return null
            node = list[index]
        }
        return node
    }

    /** 求值：单路径或「减号表达式」；任一项解析失败 ⇒ null */
    internal fun balanceAmountByPath(root: Any?, path: String): Double? {
        val expression = path.trim()
        if (expression.isEmpty()) return null
        val parts = minusPattern.split(expression)
        val first = doubleOrNull(valueByPath(root, parts.first()))
        if (parts.size == 1) return first
        var amount = first ?: return null
        for (index in 1 until parts.size) {
            val operand = doubleOrNull(valueByPath(root, parts[index])) ?: return null
            amount -= operand
        }
        return amount
    }

    /** 数值兼容：数字原语直取；字符串尝试解析；其余（布尔/对象/数组/null）⇒ null */
    internal fun doubleOrNull(value: Any?): Double? = when (value) {
        null -> null
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }

    /**
     * 查询余额。失败一律抛 [AiChatException]（消息为技术描述，由调用方包成用户文案）。
     */
    suspend fun query(provider: AiProviderConfig): AiBalanceResult {
        val balanceUrl = provider.balanceUrl.trim()
        if (balanceUrl.isEmpty()) {
            throw AiChatException("Balance URL is empty", "")
        }
        val balanceEndpoint = resolveBalanceUrl(provider.baseUrl, balanceUrl)
        val response = okHttpClient.newCallResponse {
            url(balanceEndpoint)
            addHeader("Accept", "application/json")
            provider.apiKey.trim().takeIf { it.isNotBlank() }?.let {
                addHeader("Authorization", "Bearer $it")
            }
            addHeaders(AiChatService.parseCustomHeaders(provider.headers.orEmpty()))
        }
        response.use { rawResponse ->
            val payload = rawResponse.body?.string().orEmpty()
            when {
                rawResponse.code == 401 || rawResponse.code == 403 ->
                    throw AiChatException("Authentication failed (${rawResponse.code})", "")

                !rawResponse.isSuccessful ->
                    throw AiChatException("HTTP ${rawResponse.code}: ${payload.take(500)}", "")
            }
            val root = runCatching {
                GSON.fromJson<Any?>(payload, Any::class.java)
            }.getOrNull() ?: throw AiChatException("Balance response is not valid JSON", "")
            val amount = balanceAmountByPath(root, provider.balanceJsonPath)
                ?: throw AiChatException(
                    "Balance JSON path is empty, not found, or not numeric",
                    ""
                )
            return AiBalanceResult(
                providerName = provider.name,
                items = listOf(AiBalanceInfo(name = provider.name, remaining = amount))
            )
        }
    }
}