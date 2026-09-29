package io.legado.app.web.api.routes

import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD.Method
import io.legado.app.api.ReturnData
import io.legado.app.service.kernel.MangaKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager.Level
import io.legado.app.web.api.ApiContext
import io.legado.app.web.api.ApiRoute
import io.legado.app.web.mcp.McpArgs

/**
 * N 多形态（漫画 / 图片）域路由声明（web-mcp-productization 三期 · design 1.2 · REQ-3-531）。
 *
 * 投影二期 [MangaKernel]（业务零重复，AD-10）：REST 与 MCP 两个信封共用同一 kernel 方法，
 * 故行为不可能漂移。端点与二期 MCP 工具一一对应：manga_chapters / manga_pages /
 * manga_config_get / manga_config_save / image_gallery_list。
 *
 * 能力边界（与内核一致，不夸大）：mangaPages 只回图片 URL 列表，**不代抓图片**；
 * mangaChapters 只读已落库目录（loaded=false 表示未拉取过目录）。
 */
object MangaRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        // N1 漫画章节目录（只读元数据）
        ApiRoute(Method.GET, "/mangaChapters", Level.READONLY, mcpToolName = "manga_chapters") { ctx ->
            ReturnData().setData(MangaKernel.chapters(ctx.requireParam("bookUrl")))
        },

        // N1 某章图片 URL 列表（只出地址，不代抓图片）
        ApiRoute(Method.GET, "/mangaPages", Level.READONLY, mcpToolName = "manga_pages") { ctx ->
            ReturnData().setData(
                MangaKernel.pages(ctx.requireParam("bookUrl"), ctx.intParam("chapterIndex"))
            )
        },

        // N2 / N3 漫画阅读配置（缩放 / 预下载 / 自动翻页 / 页脚 / 色滤镜 / 墨水屏）
        ApiRoute(Method.GET, "/mangaConfigGet", Level.READONLY, mcpToolName = "manga_config_get") { _ ->
            ReturnData().setData(MangaKernel.configGet())
        },
        ApiRoute(
            Method.POST, "/mangaConfigSave", Level.MANAGE, mcpToolName = "manga_config_save"
        ) { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                MangaKernel.configSave(
                    disableMangaScale = body.boolOrNull("disableMangaScale"),
                    mangaVolumeKeyPage = body.boolOrNull("mangaVolumeKeyPage"),
                    disableMangaPageAnim = body.boolOrNull("disableMangaPageAnim"),
                    mangaPreDownloadNum = body.intOrNull("mangaPreDownloadNum"),
                    mangaAutoPageSpeed = body.intOrNull("mangaAutoPageSpeed"),
                    mangaFooterConfig = body.strOrNull("mangaFooterConfig"),
                    enableMangaHorizontalScroll = body.boolOrNull("enableMangaHorizontalScroll"),
                    mangaColorFilter = body.strOrNull("mangaColorFilter"),
                    hideMangaTitle = body.boolOrNull("hideMangaTitle"),
                    enableMangaEInk = body.boolOrNull("enableMangaEInk"),
                    mangaEInkThreshold = body.intOrNull("mangaEInkThreshold"),
                    enableMangaGray = body.boolOrNull("enableMangaGray"),
                )
            )
        },

        // N4 图片画廊（数据源 = AI 图库，与 App 内一致）
        ApiRoute(Method.GET, "/imageGalleryList", Level.READONLY, mcpToolName = "image_gallery_list") { ctx ->
            ReturnData().setData(
                MangaKernel.galleryList(
                    filter = ctx.param("filter") ?: MangaKernel.GALLERY_ALL,
                    groupId = ctx.param("group"),
                    limit = ctx.intParam("limit", 100),
                )
            )
        },
    )
}

/** 请求体 → 参数访问器（与二期 MCP 工具同口径：类型不匹配按缺失处理）。 */
private fun ApiContext.bodyArgs(): McpArgs =
    McpArgs(GSON.fromJsonObject<JsonObject>(requirePostData()).getOrNull() ?: JsonObject())

/** 取可选布尔：字段缺失或 JSON null ⇒ null（= 内核「沿用现值」语义）。 */
private fun McpArgs.boolOrNull(key: String): Boolean? =
    if (raw().has(key) && !raw().get(key).isJsonNull) bool(key) else null

/** 取可选整数：字段缺失或 JSON null ⇒ null。 */
private fun McpArgs.intOrNull(key: String): Int? =
    if (raw().has(key) && !raw().get(key).isJsonNull) int(key) else null

/** 取可选字符串：字段缺失或 JSON null ⇒ null（空串保留，供「清空字段」用）。 */
private fun McpArgs.strOrNull(key: String): String? =
    if (raw().has(key) && !raw().get(key).isJsonNull) str(key) else null