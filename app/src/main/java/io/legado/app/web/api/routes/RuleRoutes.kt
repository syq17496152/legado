package io.legado.app.web.api.routes

import fi.iki.elonen.NanoHTTPD.Method
import io.legado.app.api.controller.ReplaceRuleController
import io.legado.app.web.TokenManager.Level
import io.legado.app.web.api.ApiRoute

/**
 * 规则域路由声明（web-mcp-productization 一期 · 5.4）。
 *
 * 覆盖原 `HttpServer.serve()` 中替换规则相关的 4 个端点。
 */
object RuleRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        ApiRoute(Method.GET, "/getReplaceRules", Level.READONLY, mcpToolName = "replace_rule_get") { _ ->
            ReplaceRuleController.allRules
        },
        ApiRoute(Method.POST, "/saveReplaceRule", Level.MANAGE, mcpToolName = "replace_rule_save") { ctx ->
            ReplaceRuleController.saveRule(ctx.postData)
        },
        ApiRoute(Method.POST, "/deleteReplaceRule", Level.MANAGE, mcpToolName = "replace_rule_delete") { ctx ->
            ReplaceRuleController.delete(ctx.postData)
        },
        ApiRoute(Method.POST, "/testReplaceRule", Level.MANAGE, mcpToolName = "test_replace_rule") { ctx ->
            ReplaceRuleController.testRule(ctx.postData)
        },
    )
}
