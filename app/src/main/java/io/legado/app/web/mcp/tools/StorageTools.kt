package io.legado.app.web.mcp.tools

import io.legado.app.service.kernel.DiagKernel
import io.legado.app.service.kernel.StorageKernel
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_STORAGE
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_ARRAY
import io.legado.app.web.mcp.McpJsonSchema.TYPE_BOOLEAN
import io.legado.app.web.mcp.McpJsonSchema.TYPE_INTEGER
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpParamException
import io.legado.app.web.mcp.McpTool

/**
 * ⑯ 文件与存储域工具声明（web-mcp-productization 二期 · tasks 2.20 / 2.28）。
 *
 * 6 个工具：storage_manage / file_list / file_delete / download_list / download_manage / cache_stats。
 * 能力范围**仅 App 私有目录**（缓存、下载、外部私有根），**不含设备全盘浏览**（L16 判不暴露）。
 *
 * 红线（AD-10）：invoke **只调 service/kernel**（[StorageKernel]），不内联业务、不访问 DAO、
 * 不 import api.controller（REQ-2-305）。
 */
object StorageTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "storage_manage",
            title = "存储管理",
            description = "查看缓存/存储占用，按类别清理（白名单：books 书籍文本 / video 视频 / " +
                "audio 音频 / webview WebView 数据 / all 全部；不触碰书架/书源/记录/书签/备份）。" +
                "clear=true 时执行清理，返回 kind/stats/cleared/freedBytes（视频播放中、朗读运行中" +
                "命中的目录会被跳过并在 clearResult 里如实回告）。",
            domain = MCP_DOMAIN_STORAGE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("kind" to TYPE_STRING, "clear" to TYPE_BOOLEAN),
                descriptions = mapOf(
                    "kind" to "缓存类别：books/video/audio/webview/all（默认 all）",
                    "clear" to "true = 执行清理，false 或省略 = 仅查看占用",
                )
            ),
        ) { args ->
            StorageKernel.manage(
                kind = args.str("kind") ?: DiagKernel.KIND_ALL,
                clear = args.bool("clear", false),
            )
        },

        McpTool(
            name = "file_list",
            title = "文件列表",
            description = "列出 App 私有根目录内的文件（相对根目录；目录在前、再按名称排序，与 App 文件管理页同规则）。" +
                "返回 root/path/total/returned/files（每项含 name/path/isDir/size/lastModified）。",
            domain = MCP_DOMAIN_STORAGE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("path" to TYPE_STRING, "limit" to TYPE_INTEGER),
                descriptions = mapOf(
                    "path" to "相对根目录的路径（省略或空串 = 根目录；越界路径会被拒绝）",
                    "limit" to "最多返回条数（默认 200，≤0 = 不限）",
                )
            ),
        ) { args ->
            StorageKernel.fileList(
                path = args.str("path").orEmpty(),
                limit = args.int("limit", StorageKernel.DEFAULT_FILE_LIMIT),
            )
        },

        McpTool(
            name = "file_delete",
            title = "删除文件",
            description = "删除 App 私有根目录内的文件或目录（paths 为相对根目录的路径数组，**逐项回告**，" +
                "单项失败不影响其它项；不允许删除根目录）。返回 total/deleted/results。" +
                "**批量删除超过 3 项需在端侧二次确认**。",
            domain = MCP_DOMAIN_STORAGE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("paths" to TYPE_ARRAY),
                descriptions = mapOf("paths" to "相对根目录的路径数组（须非空）"),
            ),
        ) { args ->
            val paths = args.strList("paths")
            if (paths.isEmpty()) throw McpParamException("缺少必填参数：paths（须为非空字符串数组）")
            StorageKernel.fileDelete(paths)
        },

        McpTool(
            name = "download_list",
            title = "下载任务列表",
            description = "读取下载任务列表（Room 主存 + 运行态内存态合并，内存态优先）。返回 total/items" +
                "（每项含 taskId/fileName/url/taskType/status/progress/totalSize/downloadedSize/speed/" +
                "localPath/errorCode/startTime；url 的敏感 query 参数已打码）。",
            domain = MCP_DOMAIN_STORAGE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            StorageKernel.downloadList()
        },

        McpTool(
            name = "download_manage",
            title = "下载任务管理",
            description = "下载任务管理。action 取值：start（需 url，可选 fileName ⇒ 发起下载任务）/ " +
                "pause（需 taskId ⇒ 暂停）/ delete（需 taskId ⇒ 移除任务并清理本地产物）。" +
                "返回 action/accepted 等字段。",
            domain = MCP_DOMAIN_STORAGE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("action" to TYPE_STRING),
                optional = mapOf("taskId" to TYPE_INTEGER, "url" to TYPE_STRING, "fileName" to TYPE_STRING),
                descriptions = mapOf(
                    "action" to "start/pause/delete",
                    "taskId" to "任务 id（pause / delete 必填）",
                    "url" to "下载地址（start 必填；返回时敏感 query 会被打码）",
                    "fileName" to "保存文件名（start 可选）",
                )
            ),
        ) { args ->
            StorageKernel.downloadManage(
                action = args.requireStr("action"),
                taskId = args.long("taskId", 0L),
                url = args.str("url"),
                fileName = args.str("fileName"),
            )
        },

        McpTool(
            name = "cache_stats",
            title = "缓存统计",
            description = "读取缓存占用统计（分项：books 书籍文本 / video 视频 / audio 音频 / webview WebView 数据）。" +
                "返回 total（字节合计）与 items（每项含 kind/bytes/paths）。",
            domain = MCP_DOMAIN_STORAGE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            StorageKernel.cacheStats()
        },
    )
}