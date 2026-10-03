package io.legado.app.web.api.routes

import fi.iki.elonen.NanoHTTPD.Method
import io.legado.app.api.controller.BackupController
import io.legado.app.web.TokenManager.Level
import io.legado.app.web.api.ApiRoute

/**
 * 备份域路由声明（web-mcp-productization 一期 · 5.4）。
 *
 * 覆盖原 `HttpServer.serve()` 中备份相关的 2 个端点。
 *
 * 说明：`/backup` 的 handler 返回 **`NanoHTTPD.Response`**（ZIP 文件流），
 * 走 [io.legado.app.web.api.ApiEnvelope] 的**逃生舱**分支而非 JSON 信封
 * —— 与改造前「`/backup` 特判并直接 return」行为一致。
 * 备份为高敏操作（可拉走全部数据）⇒ 级别 **admin**（design §1.2.2 路由级别表）。
 */
object BackupRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        ApiRoute(Method.GET, "/backup", Level.ADMIN, mcpToolName = "backup_export") { _ ->
            BackupController.backup()
        },
        ApiRoute(Method.GET, "/backupPreview", Level.ADMIN, mcpToolName = "backup_preview") { _ ->
            BackupController.getBackupPreview()
        },
    )
}
