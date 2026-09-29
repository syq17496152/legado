package io.legado.app.web.api.routes

import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD.Method
import io.legado.app.api.ReturnData
import io.legado.app.service.kernel.AppSettingsKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager.Level
import io.legado.app.web.api.ApiContext
import io.legado.app.web.api.ApiRoute
import io.legado.app.web.mcp.McpArgs

/**
 * 应用设置域 HTTP 路由声明（第 5 轮 UX/IA 重构 · 见 `docs/specs/web-mcp-productization/UX-IA-REDESIGN.md` §7 / `issues-found.md` **IF-22**）。
 *
 * **为什么新增本文件**：审计发现「应用偏好 / 设置检索 / 主题模式」三类能力的
 * **内核齐备**（`AppSettingsKernel`）且 **MCP 工具齐备**（`web/mcp/tools/AppSettingsTools.kt`），
 * 但 **HTTP 路由缺席**（与 IF-20 同类）⇒ 控制台设置页无法落地「应用偏好」分区，
 * 用户「设置分散在多个页面、找不到东西」的问题无法根治。
 *
 * 级别口径：读取类（偏好读取 / 设置检索）= READONLY；写类（偏好保存 / 主题模式）= MANAGE。
 * `mcpToolName` 仅为 REST ↔ MCP 对拍标注（AD-10），工具元数据唯一真源仍在
 * `web/mcp/tools/AppSettingsTools.kt`。
 *
 * **安全边界（AD-18 同类）**：偏好读写**只走白名单**（`AppSettingsKernel` 内维护，绝不含
 * token / apiKey / password / secret / auth / cookie 等凭据类键）；未登记键一律回 `unsupported key`，
 * 不写入、不外泄。
 */
object AppSettingsRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        // ---- 应用偏好：读取（keys 省略 = 白名单全集）/ 保存 ----
        ApiRoute(Method.GET, "/getAppPrefs", Level.READONLY, mcpToolName = "app_prefs_get") { ctx ->
            ReturnData().setData(AppSettingsKernel.prefsGet(ctx.parameters["keys"].orEmpty()))
        },
        ApiRoute(Method.POST, "/saveAppPrefs", Level.MANAGE, mcpToolName = "app_prefs_save") { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(AppSettingsKernel.prefsSave(body.prefsMap()))
        },

        // ---- 设置项检索（与「我的」页搜索同源，4 路匹配）----
        ApiRoute(Method.GET, "/searchAppSettings", Level.READONLY, mcpToolName = "app_settings_search") { ctx ->
            ReturnData().setData(AppSettingsKernel.settingsSearch(ctx.param("query").orEmpty()))
        },

        // ---- 主题模式快捷切换（日 / 夜 / E-Ink / 跟随系统）----
        ApiRoute(Method.POST, "/setAppThemeMode", Level.MANAGE, mcpToolName = "app_theme_mode_set") { ctx ->
            ReturnData().setData(AppSettingsKernel.themeModeSet(ctx.bodyArgs().requiredStr("mode")))
        },
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// 请求体 → 参数访问器（与 RssRoutes / AiRoutes 同口径的私有扩展；不跨文件共享）
// ══════════════════════════════════════════════════════════════════════════════

/** 请求体 → 参数访问器（空 / 非法 JSON ⇒ 空对象，随后由 [requiredStr] 报 400）。 */
private fun ApiContext.bodyArgs(): McpArgs =
    McpArgs(GSON.fromJsonObject<JsonObject>(requirePostData()).getOrNull() ?: JsonObject())

/** 取必填字符串：缺失 / 空串 ⇒ [IllegalArgumentException]（由信封统一映射为 HTTP 400）。 */
private fun McpArgs.requiredStr(key: String): String =
    str(key)?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("参数${key}不能为空")

/**
 * 取「键 → 基本类型值」对象（`prefs` 字段）。
 *
 * 缺失 / 非对象 ⇒ [IllegalArgumentException]（对齐 MCP 侧 `McpParamException` 的报错口径）；
 * 空对象交由内核 `require(values.isNotEmpty())` 拦截。
 */
private fun McpArgs.prefsMap(): Map<String, Any?> {
    val element = raw().get("prefs")
        ?: throw IllegalArgumentException("参数prefs不能为空")
    if (element.isJsonNull || !element.isJsonObject) {
        throw IllegalArgumentException("参数prefs格式不对（须为「键 → 值」对象）")
    }
    return GSON.fromJsonObject<Map<String, Any?>>(GSON.toJson(element)).getOrNull()
        ?: throw IllegalArgumentException("参数prefs格式不对（须为「键 → 值」对象）")
}