package io.legado.app.web.api.routes

import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD.Method
import io.legado.app.api.ReturnData
import io.legado.app.service.kernel.DiagKernel
import io.legado.app.service.kernel.StorageKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager.Level
import io.legado.app.web.api.ApiContext
import io.legado.app.web.api.ApiRoute
import io.legado.app.web.mcp.McpArgs

/**
 * L15–L18 文件与存储域路由声明（web-mcp-productization 三期 · design 1.2 · REQ-3-534）。
 *
 * 投影二期 [StorageKernel] / [DiagKernel]（业务零重复，AD-10）。端点与二期 MCP 工具一一对应：
 * download_list / download_manage / storage_manage / file_list / file_delete / url_record_query。
 *
 * 红线（同 REQ-3-511 判据）：storageManage 走**白名单**缓存目录（books/video/audio/webview/all），
 * **绝不触碰用户数据**（书架 / 书源 / 订阅源 / 阅读记录 / 书签 / 备份）；fileDelete 只允许
 * App 私有根目录内路径（越界即拒），删除类端点定级 [Level.ADMIN]。
 */
object StorageRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        // L15 下载任务列表（Room 主存 + 运行态内存态合并）
        ApiRoute(Method.GET, "/downloadList", Level.READONLY, mcpToolName = "download_list") { _ ->
            ReturnData().setData(StorageKernel.downloadList())
        },

        // L15 下载任务管理（start / pause / delete）
        ApiRoute(Method.POST, "/downloadManage", Level.MANAGE, mcpToolName = "download_manage") { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                StorageKernel.downloadManage(
                    action = body.str("action").orEmpty(),
                    taskId = body.long("taskId", 0L),
                    url = body.strOrNull("url"),
                    fileName = body.strOrNull("fileName"),
                )
            )
        },

        // L16 缓存 / 存储清理（白名单，禁通配删除 ⇒ 定级 admin）
        ApiRoute(Method.POST, "/storageManage", Level.ADMIN, mcpToolName = "storage_manage") { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                StorageKernel.manage(
                    kind = body.strOrNull("kind") ?: DiagKernel.KIND_ALL,
                    clear = body.bool("clear", false),
                )
            )
        },

        // L18 文件管理（相对根目录，越界即拒）
        ApiRoute(Method.GET, "/fileList", Level.READONLY, mcpToolName = "file_list") { ctx ->
            ReturnData().setData(
                StorageKernel.fileList(
                    path = ctx.param("path").orEmpty(),
                    limit = ctx.intParam("limit", StorageKernel.DEFAULT_FILE_LIMIT),
                )
            )
        },
        // 删除类 ⇒ 定级 admin（批量 > 3 端侧二次确认由工具层负责）
        ApiRoute(Method.POST, "/fileDelete", Level.ADMIN, mcpToolName = "file_delete") { ctx ->
            val paths = ctx.bodyArgs().strList("paths")
            if (paths.isEmpty()) {
                throw IllegalArgumentException("参数paths不能为空")
            }
            ReturnData().setData(StorageKernel.fileDelete(paths))
        },

        // L17 URL 访问记录（URL 敏感 query 已打码）
        ApiRoute(Method.GET, "/urlRecordQuery", Level.READONLY, mcpToolName = "url_record_query") { ctx ->
            ReturnData().setData(
                DiagKernel.urlRecordQuery(
                    keyword = ctx.param("keyword"),
                    domain = ctx.param("domain"),
                    limit = ctx.intParam("limit", 200),
                )
            )
        },
    )
}

/** 请求体 → 参数访问器（与二期 MCP 工具同口径：类型不匹配按缺失处理）。 */
private fun ApiContext.bodyArgs(): McpArgs =
    McpArgs(GSON.fromJsonObject<JsonObject>(requirePostData()).getOrNull() ?: JsonObject())

/** 取可选字符串：字段缺失或 JSON null ⇒ null（空串保留）。 */
private fun McpArgs.strOrNull(key: String): String? =
    if (raw().has(key) && !raw().get(key).isJsonNull) str(key) else null