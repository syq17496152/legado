package io.legado.app.web.utils

/**
 * CORS 策略（web-mcp-productization 一期 · 3.3 / REQ-1-303）。
 *
 * 现状问题：改造前对**任意** `Origin` 原样回显 `Access-Control-Allow-Origin` ⇒ 同机浏览器上
 * 任何被访问的网页都能跨域读取本服务（默认 `strict=false` 时只读端点免令牌 ⇒ 可直接把书源/书架读走）。
 *
 * 收敛口径（**同源 + 白名单**）：
 * 1. 无 `Origin`（同源 XHR / 非浏览器客户端）⇒ 不加该头（本来就不需要）；
 * 2. `Origin` 与请求 `Host` **同源** ⇒ 放行（App 内置 web 控制台/老 vue 页都由本服务自身托管，天然同源）；
 * 3. `Origin` 命中 [EXTRA_WHITELIST] ⇒ 放行（预留：外部可信前端，当前为空）；
 * 4. 其余 ⇒ **不回** `Access-Control-Allow-Origin` ⇒ 浏览器按 CORS 拦截。
 *
 * **过渡兼容（`strict=false`，默认）**：仍按旧行为回显任意 Origin —— 与 `webAuthStrict` 共用同一开关，
 * 用户打开"严格模式"即**同时**收紧鉴权与 CORS（单一开关、语义一致，避免两处配置漂移）。
 *
 * ⚠️ **配套修复**：预检响应原只声明 `Access-Control-Allow-Headers: content-type`，
 * 而鉴权后浏览器会先问 `authorization`（Bearer 头）⇒ 预检必失败。故允许头**必须**含 `authorization`。
 */
object CorsPolicy {

    /** 额外跨域白名单（Origin 全串，如 `https://console.example.com`）。默认空 = 只放行同源。 */
    internal val EXTRA_WHITELIST: Set<String> = emptySet()

    /** 预检与实际响应统一声明的允许方法。 */
    const val ALLOW_METHODS = "GET, POST, OPTIONS"

    /** 预检声明的允许请求头（**必须含 authorization**，否则带令牌的跨域请求预检失败）。 */
    const val ALLOW_HEADERS = "content-type, authorization"

    /**
     * 计算应回显的 `Access-Control-Allow-Origin` 值。
     *
     * @param origin 请求头 `Origin`（可能为 null）
     * @param host   请求头 `Host`（用于同源判定；含端口）
     * @param strict `webAuthStrict`（false = 过渡期，保留旧的全放行行为）
     * @return 需要写入响应的 Origin 值；null = 不写该头（浏览器将按 CORS 规则拦截）
     */
    fun allowOrigin(origin: String?, host: String?, strict: Boolean): String? {
        if (origin.isNullOrBlank()) return null
        if (isSameOrigin(origin, host)) return origin
        if (!strict) return origin // 过渡期兼容：与改造前行为一致，避免"把老页打死"
        return origin.takeIf { it in EXTRA_WHITELIST }
    }

    /** `Origin` 与 `Host` 是否同源（仅比较 host:port，忽略 scheme —— 本服务为 http 明文）。 */
    internal fun isSameOrigin(origin: String, host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val originAuthority = authorityOf(origin) ?: return false
        return originAuthority.equals(host.trim(), ignoreCase = true)
    }

    /** 取 URL 的 authority（`scheme://host:port` → `host:port`）；解析失败返回 null。 */
    private fun authorityOf(url: String): String? {
        val idx = url.indexOf("://")
        if (idx <= 0) return null
        val rest = url.substring(idx + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (end >= 0) rest.substring(0, end) else rest
        return authority.takeIf { it.isNotEmpty() }
    }
}
