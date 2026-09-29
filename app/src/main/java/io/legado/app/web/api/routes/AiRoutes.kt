package io.legado.app.web.api.routes

import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD.Method
import io.legado.app.api.ReturnData
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.help.config.NavigationBarIconConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.model.BookCover
import io.legado.app.service.kernel.AiKernel
import io.legado.app.service.kernel.AppearanceKernel
import io.legado.app.service.kernel.CacheTaskKernel
import io.legado.app.service.kernel.StorageKernel
import io.legado.app.ui.main.ai.AiWorldBookConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager.Level
import io.legado.app.web.api.ApiContext
import io.legado.app.web.api.ApiRoute
import io.legado.app.web.mcp.McpArgs
import java.io.File

/**
 * 第三轮补齐组：P AI 智能域 / Q 外观资源定义源 / R 缓存与任务调度 路由声明
 * （web-mcp-productization 三期 · design 1.2 · REQ-3-541 ~ 543）。
 *
 * 本文件按域拆为 3 个 `object`（[AiRoutes] / [AppearanceRoutes] / [CacheTaskRoutes]），
 * 每个端点仍是「kernel 方法 + 一行 ApiRoute」（业务零重复，AD-10）。
 *
 * **跨域不重复声明**：`/downloadList`、`/downloadManage`、`/storageManage` 归 L 组
 * （[StorageRoutes]），本文件的缓存组不重复注册（否则 `ApiRegistry` 重复键直接抛错）。
 */

// ══════════════════════════════════════════════════════════════════════════════
// P AI 智能域（15 端点，REQ-3-541；页面 P22/P23/P24/P25）
// ══════════════════════════════════════════════════════════════════════════════

/**
 * P AI 智能域路由声明（对话 / 世界书 / 图库 / 智能配置）。
 *
 * 投影二期 [AiKernel]。**AD-18 边界**：Provider 类端点只投影内核的**无凭据子集**
 * （内核已做字段裁剪，本层不再加工、亦不新增凭据入参）。
 *
 * 已知降级（与内核一致）：aiChatSend 只回读会话消息并在 note 说明助手回复需端侧 UI 触发；
 * aiCharacters 只返回 AI 角色助手子集。
 */
object AiRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        // P1 AI 对话（P22）：会话列表 / 发消息 / 历史
        ApiRoute(Method.GET, "/aiChatSessions", Level.READONLY, mcpToolName = "ai_chat_sessions") { _ ->
            ReturnData().setData(AiKernel.chatSessions())
        },
        ApiRoute(Method.POST, "/aiChatSend", Level.MANAGE, mcpToolName = "ai_chat_send") { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                AiKernel.chatSend(
                    sessionId = body.requiredStr("sessionId"),
                    content = body.requiredStr("content"),
                )
            )
        },
        ApiRoute(Method.GET, "/aiChatHistory", Level.READONLY, mcpToolName = "ai_chat_history") { ctx ->
            ReturnData().setData(
                AiKernel.chatHistory(
                    sessionId = ctx.requireParam("sessionId"),
                    limit = ctx.intParam("limit", 50),
                )
            )
        },

        // P3 世界书（P23）：列表 / 详情 / 保存 / 删除 / 导入
        ApiRoute(Method.GET, "/aiWorldbookList", Level.READONLY, mcpToolName = "ai_worldbook_list") { _ ->
            ReturnData().setData(AiKernel.worldbookList())
        },
        ApiRoute(Method.GET, "/aiWorldbookGet", Level.READONLY, mcpToolName = "ai_worldbook_get") { ctx ->
            ReturnData().setData(AiKernel.worldbookGet(ctx.requireParam("id")))
        },
        ApiRoute(Method.POST, "/aiWorldbookSave", Level.MANAGE, mcpToolName = "ai_worldbook_save") { ctx ->
            ReturnData().setData(
                AiKernel.worldbookSave(ctx.bodyArgs().model<AiWorldBookConfig>("config"))
            )
        },
        ApiRoute(Method.POST, "/aiWorldbookDelete", Level.MANAGE, mcpToolName = "ai_worldbook_delete") { ctx ->
            ReturnData().setData(AiKernel.worldbookDelete(ctx.bodyArgs().requiredStr("id")))
        },
        ApiRoute(Method.POST, "/aiWorldbookImport", Level.MANAGE, mcpToolName = "ai_worldbook_import") { ctx ->
            ReturnData().setData(AiKernel.worldbookImport(ctx.bodyArgs().requiredStr("json")))
        },

        // P4 AI 图库（P24）：列表 / 归组 / 收藏
        ApiRoute(Method.GET, "/aiGalleryList", Level.READONLY, mcpToolName = "ai_gallery_list") { ctx ->
            ReturnData().setData(
                AiKernel.galleryList(
                    filter = ctx.param("filter"),
                    value = ctx.param("value"),
                    limit = ctx.intParam("limit", 200),
                )
            )
        },
        ApiRoute(Method.POST, "/aiGalleryGroup", Level.MANAGE, mcpToolName = "ai_gallery_group") { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                AiKernel.galleryGroup(
                    imageId = body.requiredStr("imageId"),
                    groupId = body.str("groupId"),
                )
            )
        },
        ApiRoute(Method.POST, "/aiGalleryFavorite", Level.MANAGE, mcpToolName = "ai_gallery_favorite") { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                AiKernel.galleryFavorite(
                    imageId = body.requiredStr("imageId"),
                    favorite = body.bool("favorite", false),
                )
            )
        },

        // P5 / P6 / P7 智能配置（P25）：角色助手 / 文本 Provider / 图片 Provider / 用量
        ApiRoute(Method.GET, "/aiCharacters", Level.READONLY, mcpToolName = "ai_characters") { _ ->
            ReturnData().setData(AiKernel.characters())
        },
        ApiRoute(Method.GET, "/aiProviderList", Level.READONLY, mcpToolName = "ai_provider_list") { _ ->
            ReturnData().setData(AiKernel.providerList())
        },
        ApiRoute(Method.GET, "/aiImageProviderList", Level.READONLY, mcpToolName = "ai_image_provider_list") { _ ->
            ReturnData().setData(AiKernel.imageProviderList())
        },
        ApiRoute(Method.GET, "/aiUsageQuery", Level.READONLY, mcpToolName = "ai_usage_query") { ctx ->
            ReturnData().setData(
                AiKernel.usageQuery(
                    type = ctx.param("type"),
                    bookUrl = ctx.param("bookUrl"),
                    limit = ctx.intParam("limit", 200),
                )
            )
        },
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// Q 外观与资源定义源（18 端点，REQ-3-542；页面 P15/P26）
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Q 外观资源定义源路由声明（主题包 / 应用套件 / 顶栏包 / 底栏包 / 气泡模板 /
 * 分享模板 / 封面图集 / 封面规则 / 标题模板）。
 *
 * 投影二期 [AppearanceKernel]。**AD-17 边界**：只投影**数据定义源**，不暴露渲染实现
 * （状态栏落地 / 沉浸开关 / 启动图标 / Compose 渲染骨架 / 主题刷新链路一概不涉及）。
 * 安装 / 应用类端点定级 [Level.ADMIN] 并标记端侧确认（AD-14）。
 *
 * **已知降级**：标题模板（titleTemplate 两端点）二期未交付对应 kernel 方法与 MCP 工具
 * ⇒ 如实返回 `{supported:false, note:...}`，不伪造能力。
 * 封面规则（coverConfig 两端点）投影内核的「书籍展示侧封面规则」`BookCover.CoverRule`
 * （全局规则，bookUrl 仅作定位上下文）。
 */
object AppearanceRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        // Q1 / Q2 主题包
        ApiRoute(Method.GET, "/themePackList", Level.READONLY, mcpToolName = "theme_pack_list") { _ ->
            ReturnData().setData(AppearanceKernel.themePackList())
        },
        ApiRoute(
            Method.POST, "/themePackInstall", Level.ADMIN,
            mcpToolName = "theme_pack_install", mcpDangerous = true
        ) { ctx ->
            ReturnData().setData(AppearanceKernel.themePackInstall(ctx.bodyArgs().requiredStr("filePath")))
        },

        // Q3 应用套件
        ApiRoute(Method.GET, "/appearanceKitList", Level.READONLY, mcpToolName = "appearance_kit_list") { _ ->
            ReturnData().setData(AppearanceKernel.kitList())
        },
        ApiRoute(
            Method.POST, "/appearanceKitSave", Level.MANAGE,
            mcpToolName = "appearance_kit_save", mcpDangerous = true
        ) { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                AppearanceKernel.kitSave(
                    action = body.requiredStr("action"),
                    filePath = body.str("filePath"),
                    kitId = body.str("kitId"),
                    name = body.str("name"),
                )
            )
        },

        // Q4 顶栏包
        ApiRoute(Method.GET, "/topBarPackList", Level.READONLY, mcpToolName = "top_bar_pack_list") { _ ->
            ReturnData().setData(AppearanceKernel.topBarPackList())
        },
        ApiRoute(Method.POST, "/topBarPackSave", Level.MANAGE, mcpToolName = "top_bar_pack_save") { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                AppearanceKernel.topBarPackSave(
                    config = body.model<TopBarConfig.Config>("config"),
                    oldDirName = body.strOrNull("oldDirName"),
                )
            )
        },

        // Q5 底栏包
        ApiRoute(Method.GET, "/navBarPackList", Level.READONLY, mcpToolName = "nav_bar_pack_list") { _ ->
            ReturnData().setData(AppearanceKernel.navBarPackList())
        },
        ApiRoute(Method.POST, "/navBarPackSave", Level.MANAGE, mcpToolName = "nav_bar_pack_save") { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                AppearanceKernel.navBarPackSave(
                    config = body.model<NavigationBarIconConfig.Config>("config"),
                    oldDirName = body.strOrNull("oldDirName"),
                )
            )
        },

        // Q6 气泡模板
        ApiRoute(Method.GET, "/bubbleTemplateList", Level.READONLY, mcpToolName = "bubble_template_list") { _ ->
            ReturnData().setData(AppearanceKernel.bubbleTemplateList())
        },
        ApiRoute(Method.POST, "/bubbleTemplateSave", Level.MANAGE, mcpToolName = "bubble_template_save") { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                AppearanceKernel.bubbleTemplateSave(
                    config = body.model<BubblePackageManager.Config>("config"),
                    oldDirName = body.strOrNull("oldDirName"),
                )
            )
        },

        // Q7 分享模板
        ApiRoute(Method.GET, "/shareTemplateList", Level.READONLY, mcpToolName = "share_template_list") { _ ->
            ReturnData().setData(AppearanceKernel.shareTemplateList())
        },
        ApiRoute(Method.POST, "/shareTemplateSave", Level.MANAGE, mcpToolName = "share_template_save") { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                AppearanceKernel.shareTemplateSave(
                    html = body.requiredStr("html"),
                    oldDirName = body.strOrNull("oldDirName"),
                )
            )
        },

        // Q8 封面图集
        ApiRoute(Method.GET, "/coverCollectionList", Level.READONLY, mcpToolName = "cover_collection_list") { _ ->
            ReturnData().setData(AppearanceKernel.coverCollectionList())
        },
        ApiRoute(Method.POST, "/coverCollectionSave", Level.MANAGE, mcpToolName = "cover_collection_save") { ctx ->
            val body = ctx.bodyArgs()
            val action = body.requiredStr("action")
            val id = body.str("id")
            val name = body.str("name")
            val saved = if (body.raw().has("isNight")) {
                AppearanceKernel.coverCollectionSave(action, id, name, body.bool("isNight", false))
            } else {
                AppearanceKernel.coverCollectionSave(action, id, name)
            }
            ReturnData().setData(saved)
        },

        // Q9 封面规则（内核口径 = 书籍展示侧封面规则 BookCover.CoverRule，全局）
        ApiRoute(Method.GET, "/coverConfigGet", Level.READONLY, mcpToolName = "book_info_layout_get") { ctx ->
            ReturnData().setData(AppearanceKernel.bookInfoLayoutGet(ctx.requireParam("bookUrl")))
        },
        ApiRoute(Method.POST, "/coverConfigSave", Level.MANAGE, mcpToolName = "book_info_layout_save") { ctx ->
            ReturnData().setData(
                AppearanceKernel.bookInfoLayoutSave(ctx.bodyArgs().model<BookCover.CoverRule>("config"))
            )
        },

        // Q9 标题模板 —— **内核未交付** ⇒ 如实降级（不伪造能力）
        ApiRoute(Method.GET, "/titleTemplateList", Level.READONLY) { _ ->
            ReturnData().setData(
                mapOf(
                    "supported" to false,
                    "items" to emptyList<Any>(),
                    "note" to "标题模板定义源未交付：二期未提供对应 kernel 方法，本端点如实降级未实现",
                )
            )
        },
        ApiRoute(Method.POST, "/titleTemplateSave", Level.MANAGE) { _ ->
            ReturnData().setData(
                mapOf(
                    "supported" to false,
                    "note" to "标题模板定义源未交付：二期未提供对应 kernel 方法，保存未执行（不伪造成功）",
                )
            )
        },
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// R 缓存与任务调度（5 端点，REQ-3-543；页面 P27）
// ══════════════════════════════════════════════════════════════════════════════

/**
 * R 缓存与任务调度路由声明（缓存包上传 / 导出备份 / 云同步 / 缓存统计）。
 *
 * 投影二期 [CacheTaskKernel] 与 [StorageKernel.cacheStats]。
 * **跨域不重复**：下载管理（downloadList / downloadManage）与存储清理（storageManage）
 * 沿用 L 组 [StorageRoutes] 声明，本组不重复注册。
 *
 * ⚠️ cacheExport / cacheSyncRun(restore|delete) 属**覆盖 / 删除类危险操作**，危险属性由
 * 工具层（`web/mcp` 域文件）标记；内核只执行、不拦截。
 */
object CacheTaskRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        // R1 缓存包上传云端（本地缓存包 → 云端缓存库）
        ApiRoute(Method.POST, "/cacheDownload", Level.MANAGE, mcpToolName = "cache_download") { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                CacheTaskKernel.cacheDownload(
                    fileName = body.requiredStr("fileName"),
                    zipFile = File(body.requiredStr("zipFile")),
                )
            )
        },
        // R2 缓存导出备份（webDav=true 时强制走 WebDAV 后端）
        ApiRoute(Method.POST, "/cacheExport", Level.MANAGE, mcpToolName = "cache_export") { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                CacheTaskKernel.cacheExport(
                    fileName = body.requiredStr("fileName"),
                    webDav = body.bool("webDav", false),
                )
            )
        },
        // R3 云缓存同步（云端备份名列表 + 本地缓存索引，索引只取技术字段）
        ApiRoute(Method.GET, "/cacheSyncList", Level.READONLY, mcpToolName = "cache_sync_list") { _ ->
            ReturnData().setData(CacheTaskKernel.syncList())
        },
        // R3 云缓存同步执行（download / restore / delete）
        ApiRoute(Method.POST, "/cacheSyncRun", Level.MANAGE, mcpToolName = "cache_sync_run") { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                CacheTaskKernel.syncRun(
                    name = body.requiredStr("name"),
                    direction = body.requiredStr("direction"),
                )
            )
        },
        // R6 缓存统计（复用 StorageKernel.cacheStats，与 ⑯ 同源）
        ApiRoute(Method.GET, "/cacheStats", Level.READONLY, mcpToolName = "cache_stats") { _ ->
            ReturnData().setData(StorageKernel.cacheStats())
        },
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// 请求体解析辅助（与二期 MCP 工具同口径）
// ══════════════════════════════════════════════════════════════════════════════

/** 请求体 → 参数访问器（类型不匹配按缺失处理；空 / 非法 JSON ⇒ 空对象，随后由 requiredStr 报 400）。 */
private fun ApiContext.bodyArgs(): McpArgs =
    McpArgs(GSON.fromJsonObject<JsonObject>(requirePostData()).getOrNull() ?: JsonObject())

/**
 * 取嵌套模型：字段既接受对象也接受 JSON 字符串（与二期 MCP 工具 parseModel 同口径）。
 *
 * @throws IllegalArgumentException 字段缺失 / null（→ HTTP 400），或 JSON 结构非法
 */
private inline fun <reified T> McpArgs.model(key: String): T {
    val element = raw().get(key) ?: throw IllegalArgumentException("参数 $key 不能为空")
    if (element.isJsonNull) throw IllegalArgumentException("参数 $key 不能为空")
    val json = if (element.isJsonPrimitive) element.asString else GSON.toJson(element)
    return GSON.fromJsonObject<T>(json)
        .getOrElse { throw IllegalArgumentException("参数 $key 格式不正确：${it.message}") }
}

/** 取可选字符串：字段缺失或 JSON null ⇒ null（空串保留）。 */
private fun McpArgs.strOrNull(key: String): String? =
    if (raw().has(key) && !raw().get(key).isJsonNull) str(key) else null

/** 取必填字符串：缺失 / 空串 ⇒ [IllegalArgumentException]（由信封统一映射为 HTTP 400）。 */
private fun McpArgs.requiredStr(key: String): String =
    str(key)?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("参数${key}不能为空")