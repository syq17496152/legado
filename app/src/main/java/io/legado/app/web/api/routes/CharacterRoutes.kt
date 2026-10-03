package io.legado.app.web.api.routes

import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD.Method
import io.legado.app.api.ReturnData
import io.legado.app.data.entities.BookCharacter
import io.legado.app.service.kernel.CharacterKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager.Level
import io.legado.app.web.api.ApiContext
import io.legado.app.web.api.ApiRoute
import io.legado.app.web.mcp.McpArgs

/**
 * M1–M4 角色管理域路由声明（web-mcp-productization 三期 · design 1.2 · REQ-3-533）。
 *
 * 投影二期 [CharacterKernel]（业务零重复，AD-10）。端点与二期 MCP 工具一一对应：
 * character_list / character_save / character_relation_get / character_voice_route。
 *
 * 能力边界（与内核一致）：**不存在独立的配音路由表** ⇒ characterVoiceRoute 即写入角色卡的
 * `speechRouteJson` 字段（空白 = 清除路由）；关系图出**节点 + 边**结构，由 Web 侧自行渲染。
 */
object CharacterRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        // M1 角色列表 / 角色卡
        ApiRoute(Method.GET, "/characterList", Level.READONLY, mcpToolName = "character_list") { ctx ->
            ReturnData().setData(CharacterKernel.list(ctx.requireParam("bookUrl")))
        },

        // M2 角色卡 CRUD（id = 0 新增 / id > 0 更新）
        ApiRoute(Method.POST, "/characterSave", Level.MANAGE, mcpToolName = "character_save") { ctx ->
            ReturnData().setData(CharacterKernel.save(ctx.bodyArgs().model<BookCharacter>("character")))
        },

        // M3 角色关系图数据（节点 + 边）
        ApiRoute(
            Method.GET, "/characterRelationGet", Level.READONLY, mcpToolName = "character_relation_get"
        ) { ctx ->
            ReturnData().setData(CharacterKernel.relationGet(ctx.requireParam("bookUrl")))
        },

        // M4 角色配音路由（持久化到角色卡 speechRouteJson）
        ApiRoute(
            Method.POST, "/characterVoiceRoute", Level.MANAGE, mcpToolName = "character_voice_route"
        ) { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                CharacterKernel.voiceRoute(
                    characterId = body.int("characterId", 0).toLong(),
                    bookUrl = body.str("bookUrl").orEmpty(),
                    speechRouteJson = body.str("speechRouteJson"),
                )
            )
        },
    )
}

/** 请求体 → 参数访问器（与二期 MCP 工具同口径：类型不匹配按缺失处理）。 */
private fun ApiContext.bodyArgs(): McpArgs =
    McpArgs(GSON.fromJsonObject<JsonObject>(requirePostData()).getOrNull() ?: JsonObject())

/**
 * 取嵌套模型：字段既接受对象也接受 JSON 字符串（与二期 MCP 工具 parseCharacter 同口径）。
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