package io.legado.app.web.mcp.tools

import io.legado.app.service.kernel.BackupKernel
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_BACKUP
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpTool

/**
 * ⑪ 备份域工具声明（web-mcp-productization 二期 · tasks 2.16）。
 *
 * **本文件按 tasks 2.16 增量补齐**：当前落地"执行体只需一期 `BackupKernel`"的 2 个工具；
 * `backup_restore`（admin + **端侧确认闸门**，§7.3）与备份配置读写随 §7 与后续批次追加。
 *
 * 口径：`backup_export` 在 MCP 侧**只回文件元数据**（name/size/时间），不回 ZIP 二进制 ——
 * MCP 出参是 JSON，二进制导出属 REST `/backup` 的职责（避免把两种传输语义混在一个工具里）。
 */
object BackupTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "backup_export",
            title = "导出备份",
            description = "执行一次完整备份并返回打包结果元数据（文件名 / 字节数 / 生成时间）；" +
                "**不返回 ZIP 内容**，需要文件本体请走 REST `GET /backup`。",
            domain = MCP_DOMAIN_BACKUP,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            val file = BackupKernel.backup()
            mapOf(
                "fileName" to file.name,
                "size" to file.length(),
                "lastModified" to file.lastModified()
            )
        },
        McpTool(
            name = "backup_preview",
            title = "预览备份内容",
            description = "读取备份内容概览：文件名 / 总字节数 / 生成时间 / 各类条目数量（书 / 书签 / 源 / 记录…）。",
            domain = MCP_DOMAIN_BACKUP,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            BackupKernel.preview()
        },
    )
}
