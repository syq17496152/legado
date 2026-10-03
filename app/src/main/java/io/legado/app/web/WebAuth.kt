package io.legado.app.web

import fi.iki.elonen.NanoHTTPD
import io.legado.app.web.api.ApiEnvelope

/**
 * Web 服务鉴权中间件（web-mcp-productization 一期 · 1.2 / REQ-1-105 ~ REQ-1-111）。
 *
 * 契约：
 * - **级别唯一真源是 `ApiRoute.level`** —— 本类**不维护**任何"URL 前缀 → 级别"的独立路由表，
 *   `allow(actual, required)` 只做纯级别比较，`required` 由 `HttpServer.serve()` 从 `route.level` 传入
 *   （REQ-1-106 / AD-1-01）；
 * - **过渡期判据是「级别」不是「读写」**：仅 `READONLY` 端点在 `strict=false` 时放行，
 *   其余一律要令牌（判据由 `ApiRoute.requiresAuthWhenNonStrict` 声明，见 design §1.2.3）；
 * - 401 **不泄露"令牌是否存在"**（防枚举）；403 明示所需级别（给合法用户的可用性提示）。
 */
object WebAuth {

    private const val HEADER_AUTHORIZATION = "authorization"
    private const val BEARER_PREFIX = "bearer "

    /** 静态资源白名单前缀（不鉴权）。 */
    private val WHITELIST_PREFIXES = arrayOf("/vue/", "/uploadBook/", "/help/", "/console/")

    /** 静态资源白名单精确路径（不鉴权）。 */
    private val WHITELIST_EXACT = setOf("/", "/index.html", "/favicon.ico")

    /** 过渡开关（读 `PreferKey.webAuthStrict`，默认 `false`）。 */
    val strict: Boolean get() = TokenManager.strict

    /**
     * 从请求头取 Bearer 明文令牌；缺失/格式非法返回 null。
     *
     * 单独暴露的意义：`serve()` 需要它既做校验、又做**限流键**，避免同一请求解析两遍。
     */
    fun bearerToken(session: NanoHTTPD.IHTTPSession): String? =
        extractBearer(session.headers[HEADER_AUTHORIZATION])

    /** 解析 `Authorization: Bearer <token>` 并校验级别；无令牌/错令牌返回 [TokenManager.Level.NONE]。 */
    fun verify(session: NanoHTTPD.IHTTPSession): TokenManager.Level =
        TokenManager.verify(bearerToken(session))

    /** WS 握手校验（令牌由握手 query 参数 `token` 传入）。 */
    fun verifyWs(token: String?): TokenManager.Level = TokenManager.verify(token)

    /**
     * 级别是否足够（**纯比较**，不查任何路由表）。
     *
     * 级别按 `ordinal` 升序（NONE < READONLY < MANAGE < ADMIN）⇒ 高阶令牌可访问低阶端点。
     */
    fun allow(actual: TokenManager.Level, required: TokenManager.Level): Boolean =
        actual.ordinal >= required.ordinal

    /**
     * 是否静态资源白名单（不鉴权）。
     *
     * 前缀按**边界**匹配：`/uploadBook/` 前缀命中 `/uploadBook` 与 `/uploadBook/a`，
     * 而 `/uploadBooks` **不命中**（避免把同前缀的其它路径误放行）。
     *
     * 注意：此处不得出现斜杠加星号的写法（Kotlin 块注释**可嵌套**，会被当成嵌套注释起始 ⇒ 编译报 Unclosed comment）。
     */
    fun isWhitelisted(uri: String): Boolean {
        if (uri in WHITELIST_EXACT) return true
        return WHITELIST_PREFIXES.any { uri == it.trimEnd('/') || uri.startsWith(it) }
    }

    /**
     * 构造拒绝响应（信封与常规响应同构，老页可直接读 `errorMsg`）。
     *
     * 401 = 未携带 / 令牌无效（**统一话术，不区分"没带"与"带错了"**，防令牌枚举）；
     * 403 = 令牌有效但级别不足（明示所需级别，这不是泄露而是可用性提示）。
     */
    fun denyResponse(need: TokenManager.Level, got: TokenManager.Level): NanoHTTPD.Response =
        if (got == TokenManager.Level.NONE) {
            ApiEnvelope.deny(401, UNAUTHORIZED_MESSAGE)
        } else {
            ApiEnvelope.deny(403, "权限不足：需要 $need 级别，当前为 $got 级别")
        }

    /** 解析 Bearer 头（大小写不敏感）；空令牌视为未携带。 */
    internal fun extractBearer(header: String?): String? {
        val raw = header?.trim().orEmpty()
        if (raw.length <= BEARER_PREFIX.length) return null
        if (!raw.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true)) return null
        return raw.substring(BEARER_PREFIX.length).trim().takeIf { it.isNotEmpty() }
    }

    private const val UNAUTHORIZED_MESSAGE =
        "未授权：请在请求头携带 Authorization: Bearer <令牌>（可在 App 的「Web 服务与 AI 接入」设置中生成）"
}
