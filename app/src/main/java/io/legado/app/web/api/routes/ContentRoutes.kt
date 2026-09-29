package io.legado.app.web.api.routes

import fi.iki.elonen.NanoHTTPD.Method
import io.legado.app.api.ReturnData
import io.legado.app.service.kernel.BookmarkKernel
import io.legado.app.service.kernel.ContentKernel
import io.legado.app.service.kernel.MangaKernel
import io.legado.app.service.kernel.ReadStatsKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager.Level
import io.legado.app.web.api.ApiRoute

/**
 * E 组 · 内容面补全路由声明（web-mcp-productization 三期 · spec 4.4，共 9 端点）。
 *
 * 设计原则（AD-3-01 / AD-10）：每端点 = 一个 kernel 方法投影；本文件只做
 * 「声明 + 取参 + 委派」，不内联任何业务逻辑；`HttpServer.kt` 全程零改动（REQ-3-109）。
 *
 * 级别照 V2.5 施工卡：读取类（RSS 文章/正文、统计、书签读、漫画、热力图）为 READONLY，
 * 写类（标记已读、保存书签）为 MANAGE，恢复备份为 ADMIN（L2 危险级、默认 dryRun）。
 *
 * 说明（E 组口径修正）：`/getRssArticles` 除 spec 的 `origin`/`page` 外**必须再带 `sort`（订阅分类）**
 * —— 底层文章列表按「源 + 分类」定位（DAO `getListByOriginSort`），无「按源跨分类」查询能力，
 * 故分类不可省略；`/markRssRead` 按**单篇链接 + origin** 标记（spec 仅述 urls，缺 origin ⇒ 补之）。
 *
 * 响应体由 [ReturnData] 包 `data` 后交 `ApiEnvelope` 装信封（与一期端点同构）。
 */
object ContentRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        // ---- E1 RSS 文章列表（readonly）----
        ApiRoute(Method.GET, "/getRssArticles", Level.READONLY, mcpToolName = "rss_articles_list") { ctx ->
            ReturnData().setData(
                ContentKernel.rssArticles(
                    origin = ctx.requireParam("origin"),
                    sort = ctx.requireParam("sort"),
                    page = ctx.intParam("page", 1),
                    pageSize = ctx.intParam("pageSize", 50),
                    keyword = ctx.param("keyword"),
                )
            )
        },

        // ---- E2 RSS 文章正文 + 图片列表（readonly）----
        ApiRoute(Method.GET, "/getRssArticleContent", Level.READONLY, mcpToolName = "rss_article_content") { ctx ->
            ReturnData().setData(
                ContentKernel.rssArticleContent(
                    origin = ctx.requireParam("origin"),
                    link = ctx.requireParam("url"),
                    sort = ctx.param("sort").orEmpty(),
                )
            )
        },

        // ---- E3 标记文章已读/未读（manage）----
        ApiRoute(Method.POST, "/markRssRead", Level.MANAGE, mcpToolName = "rss_mark_read") { ctx ->
            val body = GSON.fromJsonObject<Map<String, Any?>>(ctx.requirePostData()).getOrThrow()
            val origin = str(body, "origin") ?: throw IllegalArgumentException("参数origin不能为空")
            ReturnData().setData(
                ContentKernel.markRssRead(
                    origin = origin,
                    links = stringList(body, "urls").ifEmpty { stringList(body, "links") },
                    read = bool(body, "read") ?: true,
                )
            )
        },

        // ---- E4 阅读统计（readonly，默认 30 天）----
        ApiRoute(Method.GET, "/getReadStats", Level.READONLY, mcpToolName = "read_stats_get") { ctx ->
            ReturnData().setData(ContentKernel.readStats(ctx.intParam("days", 30)))
        },

        // ---- E5 书签列表（readonly）----
        ApiRoute(Method.GET, "/getBookmarks", Level.READONLY, mcpToolName = "bookmark_list") { ctx ->
            ReturnData().setData(
                BookmarkKernel.bookmarks(ctx.param("bookName"), ctx.param("bookAuthor"))
            )
        },

        // ---- E6 保存书签（manage）----
        ApiRoute(Method.POST, "/saveBookmark", Level.MANAGE, mcpToolName = "bookmark_save") { ctx ->
            ReturnData().setData(ContentKernel.saveBookmark(ctx.requirePostData()))
        },

        // ---- E7 恢复备份（admin，危险级：默认 dryRun，confirm=true 才真执行）----
        ApiRoute(
            Method.POST,
            "/restoreBackup",
            Level.ADMIN,
            mcpToolName = "backup_restore",
            mcpDangerous = true,
        ) { ctx ->
            val filePath = ctx.files["file"] ?: ctx.files["backup"]
                ?: throw IllegalArgumentException("缺少备份文件")
            ReturnData().setData(
                ContentKernel.restoreBackup(
                    filePath = filePath,
                    dryRun = ctx.param("confirm") != "true",
                )
            )
        },

        // ---- E8 漫画章图片列表（readonly，只出地址）----
        ApiRoute(Method.GET, "/getMangaChapter", Level.READONLY, mcpToolName = "manga_pages") { ctx ->
            ReturnData().setData(
                MangaKernel.pages(
                    bookUrl = ctx.requireParam("bookUrl"),
                    chapterIndex = ctx.intParam("chapterIndex"),
                )
            )
        },

        // ---- E9 阅读热力图数据（readonly，Web 自行渲染）----
        ApiRoute(Method.GET, "/getReadHeatmap", Level.READONLY, mcpToolName = "read_heatmap_get") { ctx ->
            ReturnData().setData(ReadStatsKernel.heatmap(ctx.intParam("days", 0)))
        },
    )

    // ---- 请求体取值（JSON 松解析：整数经 GSON 落为 Long，统一按 Number 收敛）----

    private fun str(body: Map<String, Any?>, key: String): String? = body[key] as? String

    private fun bool(body: Map<String, Any?>, key: String): Boolean? = body[key] as? Boolean

    private fun stringList(body: Map<String, Any?>, key: String): List<String> =
        (body[key] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
}