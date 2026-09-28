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
import kotlinx.coroutines.runBlocking

/**
 * Web 服务 HTTP 入口（web-mcp-productization 一期 · 5.5 / REQ-1-502）。
 *
 * **分发主流程已退化为六步**，不再有端点级 `when (uri)` 分支（SC-1-15，门禁
 * `ai_tests/scripts/audit_api_registry.py` 断言）：
 * ① OPTIONS 预检放行 → ② 请求体解析 → ③ 查表 `ApiRegistry.find` → ④ 鉴权（一期 1.3 挂点）
 * → ⑤ 分发 + 装信封 `ApiEnvelope.dispatch` → ⑥ 未注册落静态资源。
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

        LogUtils.d(TAG) {
            "${session.method.name} - $uri - ${session.queryParameterString} - Start($startAt)"
        }

        try {
            // ① OPTIONS：浏览器 CORS 预检**不带 Authorization** ⇒ 必须在鉴权前放行（design §1.2.3）
            if (session.method == Method.OPTIONS) {
                val response = newFixedLengthResponse("")
                response.addHeader("Access-Control-Allow-Methods", "POST")
                response.addHeader("Access-Control-Allow-Headers", "content-type")
                response.addHeader("Access-Control-Allow-Origin", session.headers["origin"])
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

            // ④ 鉴权：一期 1.3 在此接入 WebAuth.verify / allow（级别取自 route.level）
            //    当前为纯查表分发（等价于改造前的无鉴权行为）。

            // ⑤ 分发 + 装信封。runBlocking 是 **HTTP 同步边界**（NanoHTTPD serve 为同步 API），
            //    按设计口径不计入"Kernel 层零 runBlocking"（SC-1-10）。
            val response: Response? = route?.let {
                runBlocking {
                    ApiEnvelope.dispatch(
                        it,
                        ApiContext(session.method, uri, session.parameters, postData, files)
                    )
                }
            }

            // ⑥ 未注册路径 ⇒ 静态资源（或 404），保证不会误路由到业务 handler（REQ-1-506 / SC-1-19）
            if (response == null) {
                if (uri.endsWith("/")) uri += "index.html"
                return assetsWeb.getResponse(uri)
            }

            response.addHeader("Access-Control-Allow-Methods", "GET, POST")
            response.addHeader("Access-Control-Allow-Origin", session.headers["origin"])
            LogUtils.d(TAG) {
                "${session.method.name} - $uri - ${session.queryParameterString} - End($startAt)"
            }
            return response
        } catch (e: Exception) {
            LogUtils.d(TAG) {
                "${session.method.name} - $uri - ${session.queryParameterString} - Error End($startAt)\n$e\n${e.stackTraceStr}"
            }
            return newFixedLengthResponse(e.message)
        }
    }

    companion object {
        private const val TAG = "HttpServer"
    }

}
