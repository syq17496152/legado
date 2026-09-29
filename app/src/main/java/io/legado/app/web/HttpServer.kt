package io.legado.app.web

import fi.iki.elonen.NanoHTTPD
import io.legado.app.service.WebService
import io.legado.app.utils.LogUtils
import io.legado.app.utils.stackTraceStr
import io.legado.app.web.api.ApiContext
import io.legado.app.web.api.ApiEnvelope
import io.legado.app.web.api.ApiRegistry
import io.legado.app.web.api.ApiRouteBootstrap
import io.legado.app.web.utils.AssetsWeb
import io.legado.app.web.utils.CorsPolicy
import kotlinx.coroutines.runBlocking

/**
 * Web 服务 HTTP 入口（web-mcp-productization 一期 · 5.5 / REQ-1-502）。
 *
 * **分发主流程已退化为六步**，不再有端点级 `when (uri)` 分支（SC-1-15，门禁
 * `ai_tests/scripts/audit_api_registry.py` 断言）：
 * ① OPTIONS 预检放行 → ② 请求体解析 → ③ 查表 `ApiRegistry.find` → ④ 鉴权 + 限流（`WebAuth` / `WebRateLimiter`，
 * 级别取自 `route.level`）→ ⑤ 分发 + 装信封 `ApiEnvelope.dispatch` → ⑥ 未注册落静态资源。
 */
class HttpServer(port: Int) : NanoHTTPD(port) {

    private val assetsWeb = AssetsWeb("web")

    init {
        // 路由安装的唯一入口（幂等）。新增端点只改 ApiRouteBootstrap 与 routes 目录，本文件零改动。
        ApiRouteBootstrap.install()
    }

    override fun serve(session: IHTTPSession): Response {
        WebService.serve()
        val startAt = System.currentTimeMillis()
        val ct = ContentType(session.headers["content-type"]).tryUTF8()
        session.headers["content-type"] = ct.contentTypeHeader
        var uri = session.uri
        // CORS 判定入参（Origin 与 Host 均取自请求头；NanoHTTPD 已统一小写键名）
        val origin = session.headers["origin"]

        LogUtils.d(TAG) {
            "${session.method.name} - $uri - ${session.queryParameterString} - Start($startAt)"
        }

        try {
            // ① OPTIONS：浏览器 CORS 预检**不带 Authorization** ⇒ 必须在鉴权前放行（design §1.2.3）
            if (session.method == Method.OPTIONS) {
                val response = newFixedLengthResponse("")
                response.addHeader("Access-Control-Allow-Methods", CorsPolicy.ALLOW_METHODS)
                // 允许头**必须含 authorization**：鉴权后浏览器会为 Bearer 头先发预检
                response.addHeader("Access-Control-Allow-Headers", CorsPolicy.ALLOW_HEADERS)
                CorsPolicy.allowOrigin(origin, session.headers["host"], WebAuth.strict)
                    ?.let { response.addHeader("Access-Control-Allow-Origin", it) }
                return response
            }

            // ② 请求体解析（仅 POST；GET 不解析，与改造前一致）
            val files = HashMap<String, String>()
            var postData: String? = null
            if (session.method == Method.POST) {
                session.parseBody(files)
                postData = files["postData"]
            }

            // ③ 查表：注册表是唯一路由真源（未注册 ⇒ null）
            val route = ApiRegistry.find(session.method, uri)

            // ④ 鉴权 + 限流（级别唯一真源 = route.level；未注册 / 白名单路径不经此步）。
            //    过渡期（webAuthStrict=false）按「**级别**」而非「读写」放行：仅 READONLY 端点免令牌，
            //    其余一律强制 —— 否则 GET /backup 会被当"读"放行 ⇒ 未授权整包导出（design §1.2.3）。
            var response: Response? = null
            val token = WebAuth.bearerToken(session)
            val got = if (route != null && !WebAuth.isWhitelisted(uri)) {
                TokenManager.verify(token)
            } else {
                TokenManager.Level.NONE
            }
            if (route != null && !WebAuth.isWhitelisted(uri)) {
                val required = route.level
                if ((WebAuth.strict || route.requiresAuthWhenNonStrict) && !WebAuth.allow(got, required)) {
                    response = WebAuth.denyResponse(required, got)
                    // 3.7 审计：**未授权尝试写面**必须留痕（安全取证的关键一类）
                    McpAuditor.record(
                        route = route,
                        level = got,
                        postData = postData,
                        success = false,
                        errorMsg = if (got == TokenManager.Level.NONE) "unauthorized" else "forbidden",
                        elapsedMs = System.currentTimeMillis() - startAt
                    )
                } else if (token != null && !WebRateLimiter.tryAcquire(WebRateLimiter.keyOf(got, token))) {
                    response = ApiEnvelope.deny(429, "请求过于频繁，请稍后再试")
                    McpAuditor.record(
                        route = route,
                        level = got,
                        postData = postData,
                        success = false,
                        errorMsg = "rate limited",
                        elapsedMs = System.currentTimeMillis() - startAt
                    )
                }
            }

            // ⑤ 分发 + 装信封。runBlocking 是 **HTTP 同步边界**（NanoHTTPD serve 为同步 API），
            //    按设计口径不计入"Kernel 层零 runBlocking"（SC-1-10）。
            if (response == null && route != null) {
                response = runBlocking {
                    ApiEnvelope.dispatch(
                        route,
                        ApiContext(session.method, uri, session.parameters, postData, files, got)
                    )
                }
            }

            // ⑥ 未注册路径 ⇒ 静态资源（或 404），保证不会误路由到业务 handler（REQ-1-506 / SC-1-19）
            if (response == null) {
                // 四期 §1.2：控制台**按需分发** —— `/console/*` 文件源优先（已安装包），miss 回内置 boot 壳。
                // 这是「静态资源策略」的前缀分支，**不是端点级分发**（不写路径字面量、不进注册表，见 G-21 口径）。
                if (uri.startsWith(FileWeb.CONSOLE_PREFIX)) {
                    return FileWeb.getResponse(uri)
                }
                if (uri.endsWith("/")) uri += "index.html"
                return assetsWeb.getResponse(uri)
            }

            response.addHeader("Access-Control-Allow-Methods", CorsPolicy.ALLOW_METHODS)
            // CORS 收敛（3.3 / REQ-1-303）：同源 + 白名单；过渡期（strict=false）保留旧行为
            CorsPolicy.allowOrigin(origin, session.headers["host"], WebAuth.strict)
                ?.let { response.addHeader("Access-Control-Allow-Origin", it) }
            LogUtils.d(TAG) {
                "${session.method.name} - $uri - ${session.queryParameterString} - End($startAt)"
            }
            return response
        } catch (e: Exception) {
            LogUtils.d(TAG) {
                "${session.method.name} - $uri - ${session.queryParameterString} - Error End($startAt)\n$e\n${e.stackTraceStr}"
            }
            // 真实状态码（3.2 / REQ-1-301）：改造前此处恒 200 + 纯文本，
            // 前端只能靠 isSuccess 猜对错 ⇒ 统一走信封（400/404/504/500 由异常类型映射）。
            return ApiEnvelope.errorResponseOf(e)
        }
    }

    companion object {
        private const val TAG = "HttpServer"
    }

}
