package io.legado.app.web.api.routes

import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD.Method
import io.legado.app.api.ReturnData
import io.legado.app.service.kernel.RssSourceKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager.Level
import io.legado.app.web.api.ApiContext
import io.legado.app.web.api.ApiRoute
import io.legado.app.web.mcp.McpArgs

/**
 * 订阅源管理补全路由声明（第 5 轮 UX/IA 重构 · 见 `docs/specs/web-mcp-productization/UX-IA-REDESIGN.md` §6 / `issues-found.md` **IF-20**）。
 *
 * **为什么新增本文件**：审计发现订阅**收藏 / 分组 / OPML / 导入 / 文章详情**五类能力
 * **内核齐备**（`RssSourceKernel`）且**MCP 工具齐备**（`web/mcp/tools/RssTools.kt`），
 * 但 **HTTP 路由缺席**（设计列项遗漏，与 IF-12 同类）⇒ 控制台「订阅」页的收藏与分组导入分区无法接线。
 * 本文件把 9 个内核方法投影为 REST 路由（AD-3-01：只做「声明 + 取参 + 委派」，不内联业务逻辑）。
 *
 * 级别口径：读取类（收藏列表 / 分组列表 / OPML 导出 / 文章详情）= READONLY；
 * 写类（收藏保存删除 / 分组重命名 / 源导入 / OPML 导入）= MANAGE。
 * `mcpToolName` 仅为 REST ↔ MCP 对拍标注（AD-10），工具元数据唯一真源仍在 `web/mcp/tools/RssTools.kt`。
 */
object RssRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        // ---- 收藏：列表 / 保存 / 删除 ----
        ApiRoute(Method.GET, "/getRssFavorites", Level.READONLY, mcpToolName = "rss_favorite_list") { ctx ->
            ReturnData().setData(RssSourceKernel.favorites(ctx.param("group")))
        },
        ApiRoute(Method.POST, "/saveRssFavorite", Level.MANAGE, mcpToolName = "rss_favorite_save") { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                RssSourceKernel.favorite(
                    origin = body.requiredStr("origin"),
                    link = body.requiredStr("link"),
                    // 缺省 true（加收藏）；显式传 false 即取消收藏
                    star = if (body.raw().has("star")) body.bool("star") else true,
                    group = body.strOrNull("group"),
                )
            )
        },
        ApiRoute(Method.POST, "/deleteRssFavorite", Level.MANAGE, mcpToolName = "rss_favorite_delete") { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                mapOf(
                    "deleted" to RssSourceKernel.deleteFavorite(
                        origin = body.strOrNull("origin"),
                        link = body.strOrNull("link"),
                        group = body.strOrNull("group"),
                    )
                )
            )
        },

        // ---- 分组：列表 / 重命名 ----
        ApiRoute(Method.GET, "/getRssGroups", Level.READONLY, mcpToolName = "rss_groups_get") { _ ->
            ReturnData().setData(RssSourceKernel.groups())
        },
        ApiRoute(Method.POST, "/saveRssGroup", Level.MANAGE, mcpToolName = "rss_group_save") { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                mapOf(
                    "affected" to RssSourceKernel.renameGroup(
                        oldName = body.requiredStr("oldName"),
                        newName = body.strOrNull("newName"),
                    )
                )
            )
        },

        // ---- 导入 / 导出 ----
        ApiRoute(Method.POST, "/importRssSources", Level.MANAGE, mcpToolName = "rss_source_import") { ctx ->
            ReturnData().setData(RssSourceKernel.importJson(ctx.bodyArgs().requiredStr("json")))
        },
        // OPML 导出：`urls` 可重复传（多值）以限定范围；不传 ⇒ 全部启用源
        ApiRoute(Method.GET, "/exportRssOpml", Level.READONLY, mcpToolName = "rss_opml_export") { ctx ->
            ReturnData().setData(RssSourceKernel.opmlExport(ctx.parameters["urls"].orEmpty()))
        },
        ApiRoute(Method.POST, "/importRssOpml", Level.MANAGE, mcpToolName = "rss_opml_import") { ctx ->
            ReturnData().setData(RssSourceKernel.opmlImport(ctx.bodyArgs().requiredStr("text")))
        },

        // ---- 文章详情（含收藏/已读标记，供正文面板与换源使用）----
        ApiRoute(Method.GET, "/getRssArticleInfo", Level.READONLY, mcpToolName = "rss_article_info") { ctx ->
            ReturnData().setData(
                RssSourceKernel.articleInfo(
                    origin = ctx.requireParam("origin"),
                    link = ctx.requireParam("link"),
                )
            )
        },
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// 请求体 → 参数访问器（与 AiRoutes / CharacterRoutes 同口径的私有扩展；不跨文件共享）
// ══════════════════════════════════════════════════════════════════════════════

/** 请求体 → 参数访问器（空 / 非法 JSON ⇒ 空对象，随后由 [requiredStr] 报 400）。 */
private fun ApiContext.bodyArgs(): McpArgs =
    McpArgs(GSON.fromJsonObject<JsonObject>(requirePostData()).getOrNull() ?: JsonObject())

/** 取可选字符串：字段缺失或 JSON null ⇒ null（空串保留）。 */
private fun McpArgs.strOrNull(key: String): String? =
    if (raw().has(key) && !raw().get(key).isJsonNull) str(key) else null

/** 取必填字符串：缺失 / 空串 ⇒ [IllegalArgumentException]（由信封统一映射为 HTTP 400）。 */
private fun McpArgs.requiredStr(key: String): String =
    str(key)?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("参数${key}不能为空")