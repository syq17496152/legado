package io.legado.app.web.mcp.tools

import io.legado.app.service.kernel.BackupKernel
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_BACKUP
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_BOOLEAN
import io.legado.app.web.mcp.McpJsonSchema.TYPE_OBJECT
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpParamException
import io.legado.app.web.mcp.McpTool

/**
 * ⑪ 备份域工具声明（web-mcp-productization 二期 · tasks 2.16 / §7.3）。
 *
 * 共 5 个工具：`backup_export` / `backup_preview`（只读）＋ 本批次追加的 `backup_restore`
 * （**ADMIN + dangerous**，默认干跑，真正恢复触发**端侧确认闸门**）/ `backup_config_get`
 * （只读）/ `backup_config_save`（MANAGE）。执行体一律只调一期 [BackupKernel]。
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
        McpTool(
            name = "backup_restore",
            title = "恢复备份",
            description = "恢复备份（**破坏性操作，走端侧确认闸门**）。**默认干跑**：未传 dryRun 或 dryRun=true 时" +
                "只做预检并返回将恢复的概览（fileName/totalSize/itemCount/items），不落库。" +
                "真正恢复需 dryRun=false 且给出 cloudName（云备份名）或 path（本地备份文件路径），二者按优先级取云。",
            domain = MCP_DOMAIN_BACKUP,
            level = TokenManager.Level.ADMIN,
            dangerous = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf(
                    "dryRun" to TYPE_BOOLEAN,
                    "cloudName" to TYPE_STRING,
                    "path" to TYPE_STRING,
                ),
                descriptions = mapOf(
                    "dryRun" to "是否干跑（默认 true，只预检不落库）",
                    "cloudName" to "云备份名（真正恢复时二选一）",
                    "path" to "本地备份文件路径（真正恢复时二选一）",
                )
            ),
        ) { args ->
            // 未传 dryRun 即默认干跑（保守口径）；显式传值以传入为准
            val dryRun = if (args.raw().has("dryRun")) args.bool("dryRun") else true
            BackupKernel.restore(
                dryRun = dryRun,
                cloudName = args.str("cloudName"),
                path = args.str("path"),
            )
        },
        McpTool(
            name = "backup_config_get",
            title = "读取备份配置",
            description = "读取备份忽略配置：items（键 key / 标题 title / 当前是否忽略 ignored）与原始映射 raw。",
            domain = MCP_DOMAIN_BACKUP,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            BackupKernel.configGet()
        },
        McpTool(
            name = "backup_config_save",
            title = "保存备份配置",
            description = "保存备份忽略配置。ignore 为「配置键 → 是否忽略」的对象，**只接受已知配置键白名单**，" +
                "命中 0 个已知键会报错。返回 saved（已保存条数）/ applied。",
            domain = MCP_DOMAIN_BACKUP,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("ignore" to TYPE_OBJECT),
                descriptions = mapOf("ignore" to "忽略配置对象：\"配置键\": true/false"),
            ),
        ) { args ->
            val element = args.raw().get("ignore")
            if (element == null || !element.isJsonObject) {
                throw McpParamException("缺少必填参数或格式非法：ignore（须为对象）")
            }
            val ignore = LinkedHashMap<String, Boolean>()
            element.asJsonObject.entrySet().forEach { (key, value) ->
                val bool = value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                    ?: throw McpParamException("参数 ignore.$key 须为布尔值")
                ignore[key] = bool.asBoolean
            }
            if (ignore.isEmpty()) throw McpParamException("参数 ignore 不能为空")
            BackupKernel.configSave(ignore)
        },
    )
}
