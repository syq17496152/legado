package io.legado.app.web.api.routes

import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD.Method
import io.legado.app.api.ReturnData
import io.legado.app.data.entities.Book
import io.legado.app.service.kernel.BookKernel
import io.legado.app.service.kernel.BookshelfKernel
import io.legado.app.service.kernel.ContentKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager.Level
import io.legado.app.web.api.ApiContext
import io.legado.app.web.api.ApiRoute
import io.legado.app.web.mcp.McpArgs

/**
 * G 组 · 书架 / 书籍详情 / 阅读菜单路由声明（web-mcp-productization 三期 · spec 4.5 G 组，
 * REQ-3-501 ~ REQ-3-503，共 11 端点）。
 *
 * 设计原则（AD-3-01 / AD-10）：每端点 = 一个二期 kernel 方法投影；本文件只做
 * 「声明 + 取参 + 委派」，不内联业务逻辑；`HttpServer.kt` 全程零改动（REQ-3-109 / SC-3-18）。
 * 响应体由 [ReturnData] 包 `data` 后交 `ApiEnvelope` 装信封（与一期端点同构）。
 *
 * 取参口径：GET 走查询参数（`ctx.param` / `ctx.intParam`）；POST 走 JSON 请求体（[bodyArgs]，
 * 与二期 MCP 工具同口径：类型不匹配按缺失处理）。
 *
 * 说明（REQ-3-503 中 `/getReadConfig` 的扩展字段：高级标题 / 点击区域 / 内容选择菜单）：
 * 该端点由**一期既有路由**承载（`BookRoutes` 的 GET `/getReadConfig` · POST `/saveReadConfig`），
 * 读写同链在 `ContentKernel.readConfigGet` / `readConfigSave`；扩展字段属该配置 JSON 的**内部结构**
 * （不透明串，原样往返），故本期**不重复注册**（同一「方法 + 路径」重复注册会让启动期抛错）。
 */
object BookshelfRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        // ---- REQ-3-501 书架：搜索 / 分组 / 排序 ----
        // 书架内搜索（书名 / 作者模糊匹配，0 网络请求）
        ApiRoute(Method.GET, "/searchBookshelf", Level.READONLY, mcpToolName = "bookshelf_search") { ctx ->
            ReturnData().setData(BookshelfKernel.searchBookshelf(ctx.requireParam("keyword")))
        },

        // 书架分组列表（含内置分组：全部 / 本地 / 音频 / 视频 / 未分组等）
        ApiRoute(
            Method.GET, "/getBookshelfGroups", Level.READONLY, mcpToolName = "bookshelf_groups_get"
        ) { _ ->
            ReturnData().setData(BookshelfKernel.groups())
        },

        // 新增 / 重命名分组；`deleteGroupId` 非空 ⇒ 删分组（与二期 bookshelf_group_save 同口径）
        ApiRoute(
            Method.POST, "/saveBookshelfGroup", Level.MANAGE, mcpToolName = "bookshelf_group_save"
        ) { ctx ->
            val body = ctx.bodyArgs()
            val deleteId = body.long("deleteGroupId", 0L)
            if (deleteId != 0L) {
                BookshelfKernel.deleteGroup(deleteId)
                ReturnData().setData(mapOf("deleted" to deleteId))
            } else {
                ReturnData().setData(
                    BookshelfKernel.saveGroup(
                        groupId = body.long("groupId", 0L),
                        groupName = body.str("groupName")
                            ?: throw IllegalArgumentException("参数groupName不能为空"),
                        show = body.boolOrNull("show"),
                        bookSort = body.intOrNull("bookSort"),
                    )
                )
            }
        },

        // 设置书籍所属分组（单条）
        ApiRoute(Method.POST, "/setBookGroup", Level.MANAGE, mcpToolName = "book_set_group") { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                BookshelfKernel.setBookGroup(
                    bookUrl = body.str("bookUrl")
                        ?: throw IllegalArgumentException("参数bookUrl不能为空"),
                    groupId = body.requireLong("groupId"),
                )
            )
        },

        // 调整书籍排序（`order` 绝对序号 / `move` 相对移动，二者至少给一个）
        ApiRoute(
            Method.POST, "/setBookshelfOrder", Level.MANAGE, mcpToolName = "book_set_order"
        ) { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                BookshelfKernel.setBookOrder(
                    bookUrl = body.str("bookUrl")
                        ?: throw IllegalArgumentException("参数bookUrl不能为空"),
                    order = body.intOrNull("order"),
                    move = body.strOrNull("move"),
                )
            )
        },

        // ---- REQ-3-502 书籍详情 ----
        // 书籍详情（不存在 ⇒ NoSuchElementException ⇒ 404）
        ApiRoute(Method.GET, "/getBookInfo", Level.READONLY, mcpToolName = "book_get_info") { ctx ->
            ReturnData().setData(
                BookshelfKernel.bookInfo(ctx.requireParam("bookUrl"))
                    ?: throw NoSuchElementException("书籍不存在")
            )
        },

        // 保存书籍详情编辑（`book` 既接受对象也接受 JSON 字符串）
        ApiRoute(Method.POST, "/saveBookInfo", Level.MANAGE, mcpToolName = "book_save_info") { ctx ->
            val book = ctx.bodyArgs().model<Book>("book")
            if (book.bookUrl.isBlank()) throw IllegalArgumentException("书籍bookUrl不能为空")
            BookKernel.saveBook(book)
            ReturnData().setData(book)
        },

        // ---- REQ-3-503 阅读菜单 / 自动阅读 ----
        // 阅读菜单自定义按钮（layout 布局 JSON + buttons 自定义按钮实体）
        ApiRoute(
            Method.GET, "/getReadMenuButtons", Level.READONLY, mcpToolName = "read_menu_buttons_get"
        ) { _ ->
            ReturnData().setData(ContentKernel.menuButtonsGet())
        },
        ApiRoute(
            Method.POST, "/saveReadMenuButtons", Level.MANAGE, mcpToolName = "read_menu_buttons_save"
        ) { ctx ->
            val body = ctx.bodyArgs()
            val layout = body.strOrNull("layoutJson")
            val buttons = body.strOrNull("buttonsJson")
            if (layout == null && buttons == null) {
                throw IllegalArgumentException("layoutJson 与 buttonsJson 至少给一个")
            }
            ReturnData().setData(ContentKernel.menuButtonsSave(layout, buttons))
        },

        // 自动阅读设置（速度 / 模式）
        ApiRoute(
            Method.GET, "/getAutoReadConfig", Level.READONLY, mcpToolName = "auto_read_config_get"
        ) { _ ->
            ReturnData().setData(ContentKernel.autoReadConfigGet())
        },
        ApiRoute(
            Method.POST, "/saveAutoReadConfig", Level.MANAGE, mcpToolName = "auto_read_config_save"
        ) { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                ContentKernel.autoReadConfigSave(
                    speed = body.intOrNull("autoReadSpeed"),
                    mode = body.intOrNull("autoReadMode"),
                )
            )
        },
    )
}

/** 请求体 → 参数访问器（与二期 MCP 工具同口径：类型不匹配按缺失处理）。 */
private fun ApiContext.bodyArgs(): McpArgs =
    McpArgs(GSON.fromJsonObject<JsonObject>(requirePostData()).getOrNull() ?: JsonObject())

/**
 * 取嵌套模型：字段既接受对象也接受 JSON 字符串（与二期 MCP 工具 parseBook 同口径）。
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

/** 取必填长整数（缺失 / 非法格式 ⇒ 400）。 */
private fun McpArgs.requireLong(key: String): Long =
    str(key)?.toLongOrNull()
        ?: throw IllegalArgumentException("缺少必填参数或格式非法：$key（须为整数）")

/** 取可选布尔：字段缺失或 JSON null ⇒ null（= 内核「沿用现值」语义）。 */
private fun McpArgs.boolOrNull(key: String): Boolean? =
    if (raw().has(key) && !raw().get(key).isJsonNull) bool(key) else null

/** 取可选整数：字段缺失或 JSON null ⇒ null。 */
private fun McpArgs.intOrNull(key: String): Int? =
    if (raw().has(key) && !raw().get(key).isJsonNull) int(key) else null

/** 取可选字符串：字段缺失或 JSON null ⇒ null（空串保留）。 */
private fun McpArgs.strOrNull(key: String): String? =
    if (raw().has(key) && !raw().get(key).isJsonNull) str(key) else null