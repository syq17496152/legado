package io.legado.app.web.api.routes

import fi.iki.elonen.NanoHTTPD.Method
import io.legado.app.service.kernel.BookSourceKernel
import io.legado.app.service.kernel.DiagReadKernel
import io.legado.app.service.kernel.SourceDebugKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager.Level
import io.legado.app.web.api.ApiRoute

/**
 * B 组 · 书源/订阅源调试修复闭环路由声明（web-mcp-productization 三期 · spec 4.1，共 10 端点）。
 *
 * 设计原则（AD-3-01 / AD-10）：每端点 = 一个二期 kernel 方法投影；本文件只做
 * 「声明 + 取参 + 委派」，不内联任何业务逻辑；`HttpServer.kt` 全程零改动（REQ-3-109）。
 *
 * 级别照 V2.5 施工卡：探测类（B1–B5、B7）为 READONLY，源启停（B6）为 MANAGE，
 * 审计读出（B10）为 READONLY。响应体由 `ApiEnvelope` 统一装信封，本层不另造 ReturnData。
 *
 * 输出安全（URL 敏感参数打码、超时结构化、失败步骤定位）单源在 kernel / [SourceStepTracer]，
 * 本层不重复处理。（调用面不得 import `api.controller`，见一期 G-21/G-23 同类约束精神。）
 */
object DebugRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        // B1 书源搜索测试
        ApiRoute(Method.GET, "/testSourceSearch", Level.READONLY, mcpToolName = "source_search_test") { ctx ->
            SourceDebugKernel.searchTest(
                sourceUrl = ctx.requireParam("sourceUrl"),
                key = ctx.requireParam("keyword"),
                page = ctx.intParam("page", 1),
            )
        },
        // B2 书源基本信息体检（不联网）
        ApiRoute(Method.GET, "/testSourceInfo", Level.READONLY, mcpToolName = "source_info_test") { ctx ->
            SourceDebugKernel.infoTest(ctx.requireParam("sourceUrl"))
        },
        // B3 书源目录测试
        ApiRoute(Method.GET, "/testSourceToc", Level.READONLY, mcpToolName = "source_toc_test") { ctx ->
            SourceDebugKernel.tocTest(
                sourceUrl = ctx.requireParam("sourceUrl"),
                bookUrl = ctx.requireParam("bookUrl"),
            )
        },
        // B4 书源正文测试（本组核心，失败信息含步骤级定位）
        ApiRoute(Method.GET, "/testSourceContent", Level.READONLY, mcpToolName = "source_content_test") { ctx ->
            SourceDebugKernel.contentTest(
                sourceUrl = ctx.requireParam("sourceUrl"),
                bookUrl = ctx.requireParam("bookUrl"),
                chapterIndex = ctx.intParam("chapterIndex", 0),
            )
        },
        // B5 书源质量校验（L3 深度联网体检）
        ApiRoute(Method.GET, "/validateSource", Level.READONLY, mcpToolName = "validate_source") { ctx ->
            SourceDebugKernel.validateSource(ctx.requireParam("sourceUrl"))
        },
        // B6 书源启停（写面 ⇒ MANAGE）
        ApiRoute(Method.POST, "/setSourceEnabled", Level.MANAGE, mcpToolName = "source_set_enabled") { ctx ->
            val body = GSON.fromJsonObject<Map<String, Any?>>(ctx.requirePostData()).getOrThrow()
            val sourceUrl = body["sourceUrl"] as? String
                ?: throw IllegalArgumentException("参数sourceUrl不能为空")
            val enabled = body["enabled"] as? Boolean
                ?: throw IllegalArgumentException("参数enabled不能为空")
            BookSourceKernel.setEnabled(listOf(sourceUrl), enabled)
            mapOf("sourceUrl" to sourceUrl, "enabled" to enabled)
        },
        // B7 订阅源三件：搜索 / 文章正文 / 质量校验（与 B1/B4/B5 同构，复用 RSS 链）
        ApiRoute(Method.GET, "/testRssSearch", Level.READONLY, mcpToolName = "rss_search_test") { ctx ->
            SourceDebugKernel.rssSearchTest(
                rssSourceUrl = ctx.requireParam("sourceUrl"),
                key = ctx.requireParam("keyword"),
            )
        },
        ApiRoute(Method.GET, "/testRssArticle", Level.READONLY, mcpToolName = "rss_article_test") { ctx ->
            SourceDebugKernel.rssArticleTest(
                rssSourceUrl = ctx.requireParam("sourceUrl"),
                link = ctx.requireParam("link"),
                sort = ctx.param("sort").orEmpty(),
            )
        },
        ApiRoute(Method.GET, "/validateRssSource", Level.READONLY, mcpToolName = "validate_rss_source") { ctx ->
            SourceDebugKernel.validateRssSource(ctx.requireParam("sourceUrl"))
        },
        // B10 P5 工作台「AI 直播」数据源：读 mcp_audit 某源最近 N 条（二期无 1:1 工具 ⇒ 不投影）
        ApiRoute(Method.GET, "/getSourceAudit", Level.READONLY) { ctx ->
            DiagReadKernel.recentAudit(
                sourceUrl = ctx.requireParam("sourceUrl"),
                limit = ctx.intParam("limit", DiagReadKernel.DEFAULT_AUDIT_LIMIT),
            )
        },
    )
}