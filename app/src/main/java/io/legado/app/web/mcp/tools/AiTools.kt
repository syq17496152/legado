package io.legado.app.web.mcp.tools

import com.google.gson.JsonElement
import io.legado.app.service.kernel.AiKernel
import io.legado.app.ui.main.ai.AiWorldBookConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_AI
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_BOOLEAN
import io.legado.app.web.mcp.McpJsonSchema.TYPE_INTEGER
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpParamException
import io.legado.app.web.mcp.McpTool

/**
 * ⑳ AI 智能域工具声明（web-mcp-productization 二期 · tasks 2.24 / 2.28）。
 *
 * 红线（AD-10）：`invoke` **只调 `service/kernel`**（[AiKernel]），不内联业务、不 import `api.controller`。
 * 红线（AD-18）：Provider 列表工具只暴露**无凭据子集**，本文件全文不得出现凭据字段名——测试会扫描源码断言。
 * 元数据按域单源声明；执行体一律调 Kernel。
 */
object AiTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "ai_chat_sessions",
            title = "AI 会话列表",
            description = "读取 AI 会话列表（按 updatedAt 倒序）。返回 total / currentSessionId / sessions 数组" +
                "（含 id/title/messageCount 等摘要字段）。",
            domain = MCP_DOMAIN_AI,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            AiKernel.chatSessions()
        },

        McpTool(
            name = "ai_chat_send",
            title = "发送 AI 消息",
            description = "向指定会话发送消息。当前实现**降级为只读**：不代发消息、不写库，" +
                "仅回读该会话消息并在 note 中说明助手回复需端侧 UI 触发。",
            domain = MCP_DOMAIN_AI,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("sessionId" to TYPE_STRING, "content" to TYPE_STRING),
                descriptions = mapOf(
                    "sessionId" to "AI 会话 id（取 ai_chat_sessions 的返回值）",
                    "content" to "待发送的消息内容",
                )
            ),
        ) { args ->
            AiKernel.chatSend(args.requireStr("sessionId"), args.requireStr("content"))
        },

        McpTool(
            name = "ai_chat_history",
            title = "AI 会话历史",
            description = "读取指定会话的历史消息（取末 limit 条，按时间正序返回）。" +
                "返回 sessionId/title/total/returned/messages 数组。",
            domain = MCP_DOMAIN_AI,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                required = mapOf("sessionId" to TYPE_STRING),
                optional = mapOf("limit" to TYPE_INTEGER),
                descriptions = mapOf(
                    "sessionId" to "AI 会话 id",
                    "limit" to "最多返回条数（默认 50；0 或省略 = 不限）",
                )
            ),
        ) { args ->
            AiKernel.chatHistory(args.requireStr("sessionId"), args.int("limit", 50))
        },

        McpTool(
            name = "ai_characters",
            title = "AI 角色助手列表",
            description = "读取 AI 角色助手（type=character 的助手配置）。返回 total / scope / characters 数组" +
                "（含 id/name/avatar/prompt/worldBookIds 等）。",
            domain = MCP_DOMAIN_AI,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            AiKernel.characters()
        },

        McpTool(
            name = "ai_worldbook_list",
            title = "世界书列表",
            description = "读取世界书列表（简要，不含条目正文）。返回 total / items 数组（含 id/name/entryCount 等）。",
            domain = MCP_DOMAIN_AI,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            AiKernel.worldbookList()
        },

        McpTool(
            name = "ai_worldbook_get",
            title = "世界书详情",
            description = "读取世界书详情（含条目与绑定）。返回 id/name/entries/bindings 等；不存在返回错误。",
            domain = MCP_DOMAIN_AI,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.singleRequiredString("id", "世界书 id（取 ai_worldbook_list 的返回值）"),
        ) { args ->
            AiKernel.worldbookGet(args.requireStr("id"))
        },

        McpTool(
            name = "ai_worldbook_save",
            title = "保存世界书",
            description = "新增 / 更新一本世界书（按 config.id 覆盖，缺省自动生成 id）。" +
                "入参 config 可以是世界书配置对象或 JSON 串；返回 saved/updated/id。",
            domain = MCP_DOMAIN_AI,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("config" to McpJsonSchema.TYPE_OBJECT),
                descriptions = mapOf("config" to "世界书配置对象（AiWorldBookConfig 字段子集；name 必填）"),
            ),
        ) { args ->
            val config = parseWorldBookConfig(args.raw().get("config"))
                ?: throw McpParamException("参数 config 格式不对（须为世界书配置对象或 JSON 字符串）")
            AiKernel.worldbookSave(config)
        },

        McpTool(
            name = "ai_worldbook_delete",
            title = "删除世界书",
            description = "按 id 删除世界书。返回 deleted/id。",
            domain = MCP_DOMAIN_AI,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.singleRequiredString("id", "世界书 id"),
        ) { args ->
            AiKernel.worldbookDelete(args.requireStr("id"))
        },

        McpTool(
            name = "ai_worldbook_import",
            title = "导入世界书",
            description = "导入标准世界书 JSON（同 id 生成副本并追加后缀）。返回 imported/id/name/entryCount。",
            domain = MCP_DOMAIN_AI,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.singleRequiredString("json", "标准世界书 JSON 文本"),
        ) { args ->
            AiKernel.worldbookImport(args.requireStr("json"))
        },

        McpTool(
            name = "ai_gallery_list",
            title = "AI 图片库列表",
            description = "读取 AI 图片库列表。filter 取值 all/temporary/favorite/group/book/chapter/source_type/search，" +
                "后五者需 value 参数（groupId/bookKey/chapterKey/sourceType/关键词）。" +
                "返回 total/groups/images 数组。",
            domain = MCP_DOMAIN_AI,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf(
                    "filter" to TYPE_STRING,
                    "value" to TYPE_STRING,
                    "limit" to TYPE_INTEGER,
                ),
                descriptions = mapOf(
                    "filter" to "过滤方式：all/temporary/favorite/group/book/chapter/source_type/search",
                    "value" to "filter 为 group/book/chapter/source_type/search 时的取值",
                    "limit" to "最多返回条数（默认 200；0 或省略 = 不限）",
                )
            ),
        ) { args ->
            AiKernel.galleryList(
                filter = args.str("filter"),
                value = args.str("value"),
                limit = args.int("limit", 200),
            )
        },

        McpTool(
            name = "ai_gallery_group",
            title = "图片归组",
            description = "把图片归入指定分组（groupId 为空表示移出分组）。返回 imageId/groupId。",
            domain = MCP_DOMAIN_AI,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("imageId" to TYPE_STRING, "groupId" to TYPE_STRING),
                descriptions = mapOf(
                    "imageId" to "图片 id（取 ai_gallery_list 的返回值）",
                    "groupId" to "目标分组 id（空串 = 移出分组）",
                )
            ),
        ) { args ->
            AiKernel.galleryGroup(args.requireStr("imageId"), args.str("groupId"))
        },

        McpTool(
            name = "ai_gallery_favorite",
            title = "图片收藏",
            description = "收藏 / 取消收藏图片（收藏时归入默认分组）。返回 imageId/favorite。",
            domain = MCP_DOMAIN_AI,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("imageId" to TYPE_STRING, "favorite" to TYPE_BOOLEAN),
                descriptions = mapOf(
                    "imageId" to "图片 id",
                    "favorite" to "true 收藏 / false 取消收藏",
                )
            ),
        ) { args ->
            AiKernel.galleryFavorite(args.requireStr("imageId"), args.bool("favorite"))
        },

        McpTool(
            name = "ai_provider_list",
            title = "AI 文本 Provider 列表",
            description = "读取 AI 文本 Provider 列表（id/name/baseUrl/apiMode/balanceUrl）。" +
                "**不返回任何 API Key / 凭据**。返回 total/currentProviderId/providers 数组。",
            domain = MCP_DOMAIN_AI,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            AiKernel.providerList()
        },

        McpTool(
            name = "ai_image_provider_list",
            title = "AI 图片 Provider 列表",
            description = "读取 AI 图片 Provider 列表（id/name/type/baseUrl/model/enabled/order）。" +
                "**不返回任何 API Key / 凭据**。返回 total/currentImageProviderId/providers 数组。",
            domain = MCP_DOMAIN_AI,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            AiKernel.imageProviderList()
        },

        McpTool(
            name = "ai_usage_query",
            title = "AI 朗读用量查询",
            description = "查询 AI 朗读用量记录。type 过滤记录类型（空 = 不过滤），bookUrl 过滤指定书籍（空 = 不过滤）；" +
                "limit 最多返回条数（默认 200；0 或省略 = 存储上限）。返回 records 数组。",
            domain = MCP_DOMAIN_AI,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf(
                    "type" to TYPE_STRING,
                    "bookUrl" to TYPE_STRING,
                    "limit" to TYPE_INTEGER,
                ),
                descriptions = mapOf(
                    "type" to "记录类型过滤（空 = 不过滤）",
                    "bookUrl" to "按书籍过滤（空 = 不过滤）",
                    "limit" to "最多返回条数（默认 200）",
                )
            ),
        ) { args ->
            AiKernel.usageQuery(
                type = args.str("type"),
                bookUrl = args.str("bookUrl"),
                limit = args.int("limit", 200),
            )
        },

        McpTool(
            name = "ai_agent_config_get",
            title = "读取 Agent 行为配置",
            description = "读取 Agent 行为配置（agentMode/toolMaxAttempts/maxToolRounds/toolRetryBackoffMillis/" +
                "readToolMode/enabledToolNames）。无任何凭据字段。",
            domain = MCP_DOMAIN_AI,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            AiKernel.agentConfigGet()
        },

        McpTool(
            name = "ai_agent_config_save",
            title = "保存 Agent 行为配置",
            description = "保存 Agent 行为配置（可只给部分字段，其余沿用现值）。可写字段：agentMode/" +
                "toolMaxAttempts/maxToolRounds/toolRetryBackoffMillis/readToolMode/enabledToolNames。返回保存后的完整配置。",
            domain = MCP_DOMAIN_AI,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                optional = mapOf(
                    "agentMode" to TYPE_STRING,
                    "toolMaxAttempts" to TYPE_INTEGER,
                    "maxToolRounds" to TYPE_INTEGER,
                    "toolRetryBackoffMillis" to TYPE_INTEGER,
                    "readToolMode" to TYPE_STRING,
                    "enabledToolNames" to McpJsonSchema.TYPE_ARRAY,
                ),
                descriptions = mapOf(
                    "agentMode" to "Agent 模式 id",
                    "toolMaxAttempts" to "单工具最大尝试次数",
                    "maxToolRounds" to "最大工具轮数",
                    "toolRetryBackoffMillis" to "工具重试退避毫秒数",
                    "readToolMode" to "阅读工具模式",
                    "enabledToolNames" to "启用的工具名数组",
                )
            ),
        ) { args ->
            AiKernel.agentConfigSave(
                agentMode = args.str("agentMode"),
                toolMaxAttempts = if (args.raw().has("toolMaxAttempts")) args.int("toolMaxAttempts") else null,
                maxToolRounds = if (args.raw().has("maxToolRounds")) args.int("maxToolRounds") else null,
                toolRetryBackoffMillis = if (args.raw().has("toolRetryBackoffMillis")) args.int("toolRetryBackoffMillis") else null,
                readToolMode = args.str("readToolMode"),
                enabledToolNames = if (args.raw().has("enabledToolNames")) args.strList("enabledToolNames") else null,
            )
        },
    )

    /** 与 REST 门面同口径：`config` 既接受对象也接受 JSON 字符串。 */
    private fun parseWorldBookConfig(element: JsonElement?): AiWorldBookConfig? {
        if (element == null || element.isJsonNull) return null
        val json = if (element.isJsonPrimitive) element.asString else GSON.toJson(element)
        return GSON.fromJsonObject<AiWorldBookConfig>(json).getOrNull()
    }
}
