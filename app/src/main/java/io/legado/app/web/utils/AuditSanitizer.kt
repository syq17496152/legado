package io.legado.app.web.utils

/**
 * 审计目标脱敏（web-mcp-productization 一期 · §3.8 / REQ-1-308）。
 *
 * 审计表要回答"谁对**什么**做了什么"，但请求里常带凭证（登录令牌 / API Key / 密码 / 签名）。
 * 审计记录会被持久化并在三期控制台展示 ⇒ **必须先打码再落库**。
 *
 * 全部为纯函数 ⇒ 可 JVM 单测（`AuditSanitizerTest`）。
 */
object AuditSanitizer {

    /** 打码占位符（不泄露原值长度，避免"长度也是信息"）。 */
    const val MASK = "***"

    /** 需要打码的参数名（大小写不敏感；含常见命名变体）。 */
    private val SECRET_KEYS = setOf(
        "token", "accesstoken", "access_token", "refreshtoken", "refresh_token",
        "key", "apikey", "api_key", "appkey", "app_key",
        "secret", "client_secret", "clientsecret",
        "password", "passwd", "pwd",
        "auth", "authorization", "credential", "credentials",
        "cookie", "session", "sessionid", "session_id", "sign", "signature"
    )

    /**
     * `key=value`（query / form 形态）。
     *
     * ⚠️ `(?!//)` 负向前瞻不可省：否则 URL 的 scheme（`http:`）会被当成分隔符，
     * 一次匹配就吞掉整段 `http://h/p?token=xxx` ⇒ **query 里的 token 反而漏打码**（实测踩中）。
     */
    private val KV_PAIR = Regex("""(?i)([A-Za-z0-9_\-]+)\s*[=:]\s*(?!//)([^&\s,;"'}\]]+)""")

    /** `"key":"value"`（JSON 形态）。 */
    private val JSON_PAIR = Regex("""(?i)"([A-Za-z0-9_\-]+)"\s*:\s*"([^"]*)"""")

    /** `Bearer xxx`（头部原样粘进日志时会带出）。 */
    private val BEARER = Regex("""(?i)\b(bearer)\s+[A-Za-z0-9\-._~+/=]+""")

    /**
     * 对任意文本做凭证打码：识别 `k=v` / `"k":"v"` / `Bearer x` 三类形态，
     * 只要参数名在 [SECRET_KEYS] 内（或形态为 Bearer）即把**值**替换为 [MASK]。
     */
    fun maskSecrets(text: String): String {
        if (text.isEmpty()) return text
        var out = BEARER.replace(text) { m -> "${m.groupValues[1]} $MASK" }
        out = JSON_PAIR.replace(out) { m ->
            val key = m.groupValues[1]
            if (isSecretKey(key)) """"$key":"$MASK"""" else m.value
        }
        out = KV_PAIR.replace(out) { m ->
            val key = m.groupValues[1]
            if (isSecretKey(key)) "${m.groupValues[1]}=$MASK" else m.value
        }
        return out
    }

    /**
     * 从请求体里抽取"操作对象"标识（**廉价正则，不做完整 JSON 解析**——审计不能给请求加负担）。
     *
     * 取值优先级：书源 URL → 订阅源 URL → 书籍 URL → 通用 url → 名称。
     * 未命中返回空串（此时审计仍保留 `channel` 与 `method`，不虚构 target）。
     */
    fun extractTarget(postData: String?): String {
        if (postData.isNullOrBlank()) return ""
        val field = TARGET_FIELDS.firstNotNullOfOrNull { name ->
            Regex(""""$name"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(postData)
                ?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() }
        } ?: return ""
        return maskSecrets(unescapeJson(field)).take(MAX_TARGET_LENGTH)
    }

    private fun isSecretKey(key: String): Boolean = key.lowercase() in SECRET_KEYS

    /** 仅处理审计展示需要的少数转义，避免引入完整 JSON 解析。 */
    private fun unescapeJson(raw: String): String = raw
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", " ")
        .replace("\\r", " ")

    /** target 落库上限（防超大字段撑爆审计表）。 */
    private const val MAX_TARGET_LENGTH = 200

    /** 抽取优先级（顺序即优先级，勿随意调整）。 */
    private val TARGET_FIELDS = listOf(
        "bookSourceUrl", "rssSourceUrl", "bookUrl", "url", "name"
    )
}
