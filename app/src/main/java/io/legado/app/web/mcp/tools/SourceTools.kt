package io.legado.app.web.mcp.tools

import io.legado.app.service.kernel.BookSourceKernel
import io.legado.app.service.kernel.SourceTempKernel
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_SOURCE
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_BOOLEAN
import io.legado.app.web.mcp.McpJsonSchema.TYPE_INTEGER
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpTool

/**
 * ④⑤ 书源域（含源校验）工具声明（web-mcp-productization 二期 · tasks 2.11）。
 *
 * 关键语义（spec §4.2④ / tasks 2.11）：
 * - `source_save` 落 **temp 沙箱**（正式书源列表不可见，72h 过期）—— 见 [SourceTempKernel]；
 *   正式落库走 `source_import`（用户/AI 确认后导入）。
 * - `source_delete` 走 admin + 危险标记（批量 > 3 需端侧确认，§7.4）；
 * - 源登录（`source_login_*`）属**凭据边界**，本期**不暴露**（spec §4.2④ 注）。
 */
object SourceTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "source_get",
            title = "读取书源",
            description = "读取书源：带 url 返回单条书源完整 JSON（含规则字段）；不带 url 返回全部书源数组。" +
                "AI 修改规则前应先读一次拿到当前规则原文。",
            domain = MCP_DOMAIN_SOURCE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("url" to TYPE_STRING),
                descriptions = mapOf("url" to "书源地址（bookSourceUrl）；省略则返回全部")
            ),
        ) { args ->
            val url = args.str("url")?.trim().orEmpty()
            if (url.isEmpty()) BookSourceKernel.sources() else BookSourceKernel.source(url)
        },

        McpTool(
            name = "source_save",
            title = "保存书源（temp 沙箱）",
            description = "把 AI 修改后的书源存入 **temp 沙箱**（正式书源列表不可见，72 小时后自动过期）。" +
                "沙箱内容用于 L3 工具复测；复测通过后请用 `source_import` 正式导入。返回沙箱条目。",
            domain = MCP_DOMAIN_SOURCE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("url" to TYPE_STRING, "json" to TYPE_STRING),
                optional = mapOf("name" to TYPE_STRING, "note" to TYPE_STRING),
                descriptions = mapOf(
                    "url" to "书源地址（bookSourceUrl）",
                    "json" to "书源完整 JSON（单条）",
                    "name" to "书源名（便于识别）",
                    "note" to "改动说明（可选）",
                )
            ),
        ) { args ->
            SourceTempKernel.save(
                url = args.requireStr("url"),
                name = args.str("name").orEmpty(),
                json = args.requireStr("json"),
                note = args.str("note"),
            )
        },

        McpTool(
            name = "source_delete",
            title = "删除书源",
            description = "删除书源（admin，**危险操作**）：批量 > 3 条需端侧确认。删除按 App 既有语义走回收站" +
                "（可在 source_recycle_list / source_restore 里找回）。返回删除条数。",
            domain = MCP_DOMAIN_SOURCE,
            level = TokenManager.Level.ADMIN,
            dangerous = true,
            readOnlyHint = false,
            inputSchema = McpJsonSchema.of(
                required = mapOf("urls" to McpJsonSchema.TYPE_ARRAY),
                descriptions = mapOf("urls" to "书源地址数组"),
            ),
        ) { args ->
            val urls = args.strList("urls")
            val sources = BookSourceKernel.sources().filter { it.bookSourceUrl in urls }
            if (sources.isEmpty()) throw IllegalArgumentException("没有匹配到的书源")
            BookSourceKernel.deleteSources(sources)
            mapOf("deleted" to sources.size)
        },

        McpTool(
            name = "source_import",
            title = "导入书源",
            description = "批量导入书源到**正式列表**（URL/文件/粘贴的 JSON 数组或对象）。" +
                "导入前按与 App 导入页同口径做校验，确定性失败的源会被跳过并在 skippedDetail 里给出原因。",
            domain = MCP_DOMAIN_SOURCE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("json" to TYPE_STRING),
                descriptions = mapOf("json" to "书源 JSON（数组或单对象）"),
            ),
        ) { args ->
            BookSourceKernel.importJson(args.requireStr("json"))
        },

        McpTool(
            name = "source_set_enabled",
            title = "启用/停用书源",
            description = "批量启用或停用书源（enabled 开关）。返回受影响条数。",
            domain = MCP_DOMAIN_SOURCE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("urls" to McpJsonSchema.TYPE_ARRAY, "enabled" to TYPE_BOOLEAN),
                descriptions = mapOf("urls" to "书源地址数组", "enabled" to "true 启用 / false 停用"),
            ),
        ) { args ->
            mapOf("affected" to BookSourceKernel.setEnabled(args.strList("urls"), args.bool("enabled")))
        },

        McpTool(
            name = "source_groups_get",
            title = "读取书源分组",
            description = "读取全部书源分组名（书源分组是每条例上的逗号串，DAO 已拆分去重）。",
            domain = MCP_DOMAIN_SOURCE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            BookSourceKernel.groups()
        },

        McpTool(
            name = "source_group_save",
            title = "保存书源分组",
            description = "重命名书源分组（oldName → newName；newName 传空串表示删除该分组标记）。" +
                "分组没有独立表，重命名会改写每条受影响源的逗号串。返回受影响条数。",
            domain = MCP_DOMAIN_SOURCE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("oldName" to TYPE_STRING),
                optional = mapOf("newName" to TYPE_STRING),
                descriptions = mapOf("oldName" to "原分组名", "newName" to "新分组名（空串 = 删除分组标记）"),
            ),
        ) { args ->
            mapOf("affected" to BookSourceKernel.renameGroup(args.requireStr("oldName"), args.str("newName")))
        },

        McpTool(
            name = "source_recycle_list",
            title = "书源回收站",
            description = "读取书源回收站条目（type 可选 book/rss）。返回 id/type/name/groupName/deletedAt/expireAt。",
            domain = MCP_DOMAIN_SOURCE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("type" to TYPE_STRING),
                descriptions = mapOf("type" to "book / rss（省略 = 全部）"),
            ),
        ) { args ->
            BookSourceKernel.recycleBin(args.str("type"))
        },

        McpTool(
            name = "source_restore",
            title = "从回收站恢复",
            description = "从回收站恢复一条源（overwrite=false 且同名冲突时返回 conflict=true 且不落库）。返回恢复结果。",
            domain = MCP_DOMAIN_SOURCE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("id" to TYPE_INTEGER),
                optional = mapOf("overwrite" to TYPE_BOOLEAN),
                descriptions = mapOf("id" to "回收站条目 id", "overwrite" to "同名冲突时是否覆盖（默认 false）"),
            ),
        ) { args ->
            BookSourceKernel.restoreFromRecycle(
                id = args.requireInt("id").toLong(),
                overwrite = args.bool("overwrite"),
            )
        },

        McpTool(
            name = "source_export",
            title = "导出书源",
            description = "导出书源 JSON 串（指定 url 数组，省略 = 全部启用源）。返回 {count,content}。",
            domain = MCP_DOMAIN_SOURCE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("urls" to McpJsonSchema.TYPE_ARRAY),
                descriptions = mapOf("urls" to "书源地址数组（省略 = 全部启用源）"),
            ),
        ) { args ->
            val urls = args.strList("urls")
            mapOf("count" to urls.size, "content" to BookSourceKernel.exportJson(urls))
        },

        McpTool(
            name = "source_share_qr",
            title = "书源分享二维码",
            description = "生成书源分享二维码：返回分享内容串（与 App 分享一致）+ PNG 图片 base64。" +
                "内容过长时返回结构化错误。",
            domain = MCP_DOMAIN_SOURCE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("urls" to McpJsonSchema.TYPE_ARRAY, "size" to TYPE_INTEGER),
                descriptions = mapOf("urls" to "书源地址数组（省略 = 全部启用源）", "size" to "二维码边长像素（默认 480）"),
            ),
        ) { args ->
            BookSourceKernel.shareQr(args.strList("urls"), args.int("size", 480))
        },

        McpTool(
            name = "source_var_get",
            title = "读取源变量",
            description = "读取书源变量（源可写入的持久变量串）。返回 {url, variable}。",
            domain = MCP_DOMAIN_SOURCE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.singleRequiredString("url", "书源地址"),
        ) { args ->
            BookSourceKernel.variables(args.requireStr("url"))
        },

        McpTool(
            name = "source_var_save",
            title = "保存源变量",
            description = "保存书源变量（variable 传空串表示清除），返回保存后的变量。",
            domain = MCP_DOMAIN_SOURCE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("url" to TYPE_STRING),
                optional = mapOf("variable" to TYPE_STRING),
                descriptions = mapOf("url" to "书源地址", "variable" to "变量串（空 = 清除）"),
            ),
        ) { args ->
            BookSourceKernel.saveVariables(args.requireStr("url"), args.str("variable"))
        },

        McpTool(
            name = "source_quality_report",
            title = "源质量报告",
            description = "批量源质量体检（**L1 静态检查，0 网络**）：逐源返回 score/coverage/失败维度与证据。" +
                "apply 可落库：none（只看报告）/ disable_failed（停用确定性失败的源）/ delete_failed（删除它们）。",
            domain = MCP_DOMAIN_SOURCE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("urls" to McpJsonSchema.TYPE_ARRAY, "apply" to TYPE_STRING),
                descriptions = mapOf(
                    "urls" to "书源地址数组（省略 = 全部书源）",
                    "apply" to "none / disable_failed / delete_failed（默认 none）",
                )
            ),
        ) { args ->
            BookSourceKernel.qualityReport(args.strList("urls"), args.str("apply"))
        },
    )
}
