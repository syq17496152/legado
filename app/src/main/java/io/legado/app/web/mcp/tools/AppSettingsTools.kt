package io.legado.app.web.mcp.tools

import com.google.gson.JsonElement
import io.legado.app.service.kernel.AppSettingsKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_APP
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_ARRAY
import io.legado.app.web.mcp.McpJsonSchema.TYPE_OBJECT
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpParamException
import io.legado.app.web.mcp.McpTool

/**
 * ㉓ 应用设置域工具声明（web-mcp-productization 二期 · tasks 2.24b / 2.28）。
 *
 * 4 个工具：app_prefs_get / app_prefs_save / app_settings_search / app_theme_mode_set。
 *
 * **偏白名单口径（AD-18）**：prefs_get / prefs_save **只接受白名单键**（[AppSettingsKernel] 内维护），
 * 白名单**绝不含** token / apiKey / password / secret / auth / cookie 等凭据类键；未登记键一律回告
 * `unsupported key`（读侧进 `ignored`），从源头杜绝凭据外泄。
 *
 * 红线（AD-10）：invoke **只调 service/kernel**（[AppSettingsKernel]），不内联业务、不访问 DAO、
 * 不 import api.controller（REQ-2-305）。
 */
object AppSettingsTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "app_prefs_get",
            title = "读取应用偏好",
            description = "读取白名单内的应用通用偏好（keys 省略 = 白名单全集）。返回 count/items" +
                "（每项含 key/type/value）、ignored（非白名单或无权限的键，不报错）与 available" +
                "（白名单键元数据：key/type/values/min/max，供 UI 生成控件）。",
            domain = MCP_DOMAIN_APP,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("keys" to TYPE_ARRAY),
                descriptions = mapOf("keys" to "偏好键数组（省略 = 白名单全集；非白名单键进 ignored）"),
            ),
        ) { args ->
            AppSettingsKernel.prefsGet(args.strList("keys"))
        },

        McpTool(
            name = "app_prefs_save",
            title = "保存应用偏好",
            description = "批量写白名单内的应用通用偏好（prefs 为「键 → 值」对象，值支持 boolean/int/string）。" +
                "逐项返回结果：successCount/totalCount/results（每项含 key/ok/value 或 error）；" +
                "非白名单或凭据类键回告 `unsupported key`，不写入。",
            domain = MCP_DOMAIN_APP,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("prefs" to TYPE_OBJECT),
                descriptions = mapOf("prefs" to "偏好键值对象（键须在白名单内；值 boolean/int/string）"),
            ),
        ) { args ->
            val prefs = parsePrefs(args.raw().get("prefs"))
                ?: throw McpParamException("参数 prefs 格式不对（须为「键 → 值」对象）")
            AppSettingsKernel.prefsSave(prefs)
        },

        McpTool(
            name = "app_settings_search",
            title = "设置项搜索",
            description = "全局检索设置项（标题 / 摘要 / key / 命中子项 4 路匹配）。返回 query/total/items" +
                "（每项含 key/title/summary/section/matchedSubItemCount）。",
            domain = MCP_DOMAIN_APP,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.singleRequiredString("query", "搜索关键词（标题 / 摘要 / key / 子项 4 路匹配）"),
        ) { args ->
            AppSettingsKernel.settingsSearch(args.requireStr("query"))
        },

        McpTool(
            name = "app_theme_mode_set",
            title = "主题模式切换",
            description = "快捷切换主题模式。mode 取值：\"1\" 日间 / \"2\" 夜间 / \"3\" E-Ink / " +
                "其它（含 \"0\"）= 跟随系统。返回 themeMode/isNightTheme/isEInkMode。",
            domain = MCP_DOMAIN_APP,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("mode" to TYPE_STRING),
                descriptions = mapOf("mode" to "\"1\" 日 / \"2\" 夜 / \"3\" E-Ink / 其它 = 跟随系统"),
            ),
        ) { args ->
            AppSettingsKernel.themeModeSet(args.requireStr("mode"))
        },

        McpTool(
            name = "legado_ping",
            title = "连通性自检探针",
            description = "轻量探针：确认 MCP 通道连通并回传**非敏感**运行时信息" +
                "（ok / serverTime / versionName / consoleApiLevel / webServiceRunning / mcpEndpoint）。" +
                "控制台「三段自检」第③段与角色二剧本用它定位「连不上」发生在哪一层。",
            domain = MCP_DOMAIN_APP,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
        ) { AppSettingsKernel.ping() },
    )

    /** 复杂入参口径与 REST 门面一致：`prefs` 解析为「键 → 基本类型值」映射。 */
    private fun parsePrefs(element: JsonElement?): Map<String, Any?>? {
        if (element == null || element.isJsonNull || !element.isJsonObject) return null
        return GSON.fromJsonObject<Map<String, Any?>>(GSON.toJson(element)).getOrNull()
    }
}