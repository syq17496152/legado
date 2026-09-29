package io.legado.app.web.mcp.tools

import com.google.gson.JsonElement
import io.legado.app.data.entities.BookCharacter
import io.legado.app.data.entities.BookCharacterRelation
import io.legado.app.service.kernel.CharacterKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_CHARACTER
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_INTEGER
import io.legado.app.web.mcp.McpJsonSchema.TYPE_OBJECT
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpParamException
import io.legado.app.web.mcp.McpTool

/**
 * ⑲ 角色域工具声明（角色卡 / 关系图 / 配音路由；web-mcp-productization 二期 · tasks 2.23 / 2.28）。
 *
 * 红线（AD-10）：`invoke` **只调 `service/kernel`**，不内联业务、不 import `api.controller`（REQ-2-305）。
 * 元数据按域单源声明；执行体一律调 [CharacterKernel]。
 *
 * 能力边界与降级（与 Kernel 一致的如实口径）：
 * - **不存在独立的配音路由表** ⇒ `character_voice_route` 即写 `BookCharacter.speechRouteJson`；
 * - `character_save` / `character_relation_save` 走 Room 实体语义：`id = 0` 新增 / `id > 0` 更新；
 * - 工具元数据只暴露 5 个工具（`character_relation_delete` 等 Kernel 能力未在本期工具清单内）。
 */
object CharacterTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "character_list",
            title = "角色列表",
            description = "读取书籍角色列表 / 角色卡（按 重要度 → 排序号 → id 排序）。返回 bookUrl/total/characters" +
                "（单条含 id/name/displayName/avatar/gender/genderLabel/identity/skills/attributes/appearance/" +
                "personality/biography/roleLevel/roleLabel/autoCreated/source/sortOrder/speechConfigured/时间戳）。",
            domain = MCP_DOMAIN_CHARACTER,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.singleRequiredString("bookUrl", "书籍唯一地址"),
        ) { args ->
            CharacterKernel.list(args.requireStr("bookUrl"))
        },

        McpTool(
            name = "character_save",
            title = "保存角色",
            description = "新增 / 编辑角色卡（含外观 / 性格 / 简介 / 身份 / 技能 / 重要度等字段）。" +
                "入参 character 可以是 BookCharacter 对象或角色 JSON 字符串（id = 0 新增，id > 0 更新；" +
                "bookUrl 与 name 必填，性别会归一化）；返回保存后的角色。",
            domain = MCP_DOMAIN_CHARACTER,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("character" to TYPE_OBJECT),
                descriptions = mapOf("character" to "角色对象（BookCharacter 字段子集；bookUrl / name 必填）"),
            ),
        ) { args ->
            val character = parseCharacter(args.raw().get("character"))
                ?: throw McpParamException("参数 character 格式不对（须为角色对象或 JSON 字符串）")
            CharacterKernel.save(character)
        },

        McpTool(
            name = "character_relation_get",
            title = "角色关系图",
            description = "读取角色关系图数据（**节点 + 边**）。返回 bookUrl/nodes（id/name/displayName/" +
                "genderLabel/roleLabel/avatar）/edges（fromCharacterId/toCharacterId/relationName/relationType/" +
                "strength/description…）/nodeCount/edgeCount。",
            domain = MCP_DOMAIN_CHARACTER,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.singleRequiredString("bookUrl", "书籍唯一地址"),
        ) { args ->
            CharacterKernel.relationGet(args.requireStr("bookUrl"))
        },

        McpTool(
            name = "character_relation_save",
            title = "保存角色关系",
            description = "新增 / 编辑角色关系（关系 A / B / 关系名 / 类型 / 强度 / 说明）。" +
                "入参 relation 可以是 BookCharacterRelation 对象或 JSON 字符串（id = 0 新增，id > 0 更新；" +
                "bookUrl / relationName 必填，两端角色 id 须为正数）；返回保存后的关系。",
            domain = MCP_DOMAIN_CHARACTER,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("relation" to TYPE_OBJECT),
                descriptions = mapOf(
                    "relation" to "关系对象（BookCharacterRelation 字段子集；bookUrl / relationName / " +
                        "fromCharacterId / toCharacterId 必填）"
                ),
            ),
        ) { args ->
            val relation = parseRelation(args.raw().get("relation"))
                ?: throw McpParamException("参数 relation 格式不对（须为关系对象或 JSON 字符串）")
            CharacterKernel.relationSave(relation)
        },

        McpTool(
            name = "character_voice_route",
            title = "设置角色配音路由",
            description = "设置角色配音路由（持久化到角色卡的 `speechRouteJson` 字段）。" +
                "speechRouteJson 为路由 JSON：**空白 = 清除路由**（存空串），非空则归一化为规范串后落库；" +
                "返回 characterId/bookUrl/speechRouteJson/configured/cleared。",
            domain = MCP_DOMAIN_CHARACTER,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("characterId" to TYPE_INTEGER),
                optional = mapOf("bookUrl" to TYPE_STRING, "speechRouteJson" to TYPE_STRING),
                descriptions = mapOf(
                    "characterId" to "角色 id（取 character_list 返回值，须为正数）",
                    "bookUrl" to "书籍唯一地址（可选；填了则须与角色所属书一致）",
                    "speechRouteJson" to "路由 JSON（空白 = 清除路由）",
                )
            ),
        ) { args ->
            CharacterKernel.voiceRoute(
                characterId = args.requireInt("characterId").toLong(),
                bookUrl = args.str("bookUrl").orEmpty(),
                speechRouteJson = args.str("speechRouteJson"),
            )
        },
    )

    /** 与 REST 门面同口径：`character` 既接受对象也接受 JSON 字符串。 */
    private fun parseCharacter(element: JsonElement?): BookCharacter? {
        if (element == null || element.isJsonNull) return null
        val json = if (element.isJsonPrimitive) element.asString else GSON.toJson(element)
        return GSON.fromJsonObject<BookCharacter>(json).getOrNull()
    }

    /** 与 REST 门面同口径：`relation` 既接受对象也接受 JSON 字符串。 */
    private fun parseRelation(element: JsonElement?): BookCharacterRelation? {
        if (element == null || element.isJsonNull) return null
        val json = if (element.isJsonPrimitive) element.asString else GSON.toJson(element)
        return GSON.fromJsonObject<BookCharacterRelation>(json).getOrNull()
    }
}