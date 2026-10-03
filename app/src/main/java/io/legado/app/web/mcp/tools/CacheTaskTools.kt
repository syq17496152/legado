package io.legado.app.web.mcp.tools

import io.legado.app.service.kernel.CacheTaskKernel
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_CACHE
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_BOOLEAN
import io.legado.app.web.mcp.McpTool
import java.io.File

/**
 * ㉒ 缓存与任务域工具声明（web-mcp-productization 二期 · tasks 2.24 / 2.28）。
 *
 * **只声明本域净增的 4 个工具**：cache_download / cache_export / cache_sync_list / cache_sync_run。
 * storage_manage / download_list / download_manage / cache_stats 已由 ⑯ [StorageTools] 声明，
 * **跨域复用，不在此重复声明**（否则会在工具名唯一性校验上直接失败）。
 *
 * 红线（AD-10）：invoke **只调 service/kernel**（[CacheTaskKernel]），不内联业务、不访问 DAO、
 * 不 import api.controller（REQ-2-305）。
 */
object CacheTaskTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "cache_download",
            title = "缓存包上传云端",
            description = "把本地缓存包（zipFile 指定的文件）上传到云端缓存库。**端侧指令**：只编排既有上传链路，" +
                "不在服务端生成或改写缓存包。返回 uploaded/fileName/bytes。",
            domain = MCP_DOMAIN_CACHE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("fileName" to McpJsonSchema.TYPE_STRING, "zipFile" to McpJsonSchema.TYPE_STRING),
                descriptions = mapOf(
                    "fileName" to "云端缓存包名（不能为空）",
                    "zipFile" to "本地缓存包文件路径（须为已存在的文件）",
                )
            ),
        ) { args ->
            CacheTaskKernel.cacheDownload(
                fileName = args.requireStr("fileName"),
                zipFile = File(args.requireStr("zipFile")),
            )
        },

        McpTool(
            name = "cache_export",
            title = "缓存导出备份",
            description = "完整备份到云端（webDav=true 时强制走 WebDAV 后端）。**危险操作**：会同名覆盖云端既有备份。" +
                "返回 exported/fileName/webDav。",
            domain = MCP_DOMAIN_CACHE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("fileName" to McpJsonSchema.TYPE_STRING),
                optional = mapOf("webDav" to TYPE_BOOLEAN),
                descriptions = mapOf(
                    "fileName" to "备份名（不能为空）",
                    "webDav" to "true = 走 WebDAV；false 或省略 = 按当前云存储后端",
                )
            ),
        ) { args ->
            CacheTaskKernel.cacheExport(
                fileName = args.requireStr("fileName"),
                webDav = args.bool("webDav", false),
            )
        },

        McpTool(
            name = "cache_sync_list",
            title = "云缓存同步列表",
            description = "读取云端备份名列表与本地缓存索引（索引**只取技术字段**，不含书籍来源域名/业务文本）。" +
                "返回 backupCount/backups/cacheIndexCount/cacheIndex。",
            domain = MCP_DOMAIN_CACHE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            CacheTaskKernel.syncList()
        },

        McpTool(
            name = "cache_sync_run",
            title = "云缓存同步",
            description = "执行云缓存同步。direction 取值：download（云端缓存包 → 本地临时目录）/ " +
                "restore（云端备份覆盖恢复，**危险：覆盖本地数据**）/ delete（删除云端缓存包，**危险**）。" +
                "返回 direction/name 及对应结果字段。",
            domain = MCP_DOMAIN_CACHE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("name" to McpJsonSchema.TYPE_STRING, "direction" to McpJsonSchema.TYPE_STRING),
                descriptions = mapOf(
                    "name" to "备份名 / 缓存包名（不能为空）",
                    "direction" to "download/restore/delete",
                )
            ),
        ) { args ->
            CacheTaskKernel.syncRun(
                name = args.requireStr("name"),
                direction = args.requireStr("direction"),
            )
        },
    )
}