package io.legado.app.service.kernel

import io.legado.app.constant.AppConst
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.service.WebService
import io.legado.app.ui.main.my.buildSettingsSections
import io.legado.app.ui.main.my.buildSettingsSubSearchItems
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * ㉓ 应用设置域业务内核（web-mcp-productization 二期 · tasks 2.28）。
 *
 * 契约同 [BookKernel]：只返回领域对象 / 结构化 Map、全链挂起、零阻塞调用、失败抛异常。
 *
 * 数据源（全部为既有能力）：
 * - 通用偏好：`AppConfig`（`object`，SharedPreferences）+ `PreferKey` 键常量；
 * - 设置检索：`buildSettingsSections(appCtx)` + `buildSettingsSubSearchItems(appCtx)`（与「我的」页搜索同源）；
 * - 主题模式：`AppConfig.themeMode`（三态）。
 *
 * ## 边界声明（AD-18 同类约束：偏好白名单，禁止凭据类键）
 * [prefsGet] / [prefsSave] **只接受白名单键**（[settingDefs]）；白名单**绝不含**
 * `token` / `key` / `apiKey` / `password` / `secret` / `auth` / `cookie` 等凭据类键，
 * 且写入前再做一次 [isForbiddenKey] 防御性拒绝。本内核**不 import `web` 层**。
 * 白名单键集合镜像 `help/ai/AiSettingsTool` 的 `get_app_settings` / `set_app_setting`（避免与 AI 设置工具漂移）。
 *
 * ## 已知上限 / 降级
 * 1. [settingsSearch] 复用「我的」页同源数据（`buildSettingsSections` + `buildSettingsSubSearchItems`）；
 *    `buildVisibleSections` 为 `MySettingsScreen` 私有实现，无法直接调用，故此处**对齐其 4 路匹配口径**
 *    （标题 / 摘要 / key / 命中子项），但**动态摘要**（WebService / ThemeMode 的运行时摘要）未纳入匹配（降级）。
 * 2. 白名单为**已核实的通用设置子集**，非全量偏好；未登记键一律拒绝（返回 `unsupported key`）。
 */
object AppSettingsKernel {

    private data class SettingDef(
        val key: String,
        val type: String,
        val values: Set<String> = emptySet(),
        val min: Int? = null,
        val max: Int? = null,
    )

    /** 白名单（镜像 `AiSettingsTool`；**绝不含凭据类键**）。 */
    private val settingDefs = listOf(
        // 注：themeMode 实际以 String 存储（"0"~"3"），故按 string 读取，不用 getPrefInt（避免类型不匹配）
        SettingDef(PreferKey.themeMode, "string", values = setOf("0", "1", "2", "3")),
        SettingDef(PreferKey.modernDiscoveryPage, "boolean"),
        SettingDef(
            PreferKey.discoveryPageMode,
            "string",
            values = setOf(
                AppConfig.DISCOVERY_PAGE_MODE_LEGACY,
                AppConfig.DISCOVERY_PAGE_MODE_MODERN,
                AppConfig.DISCOVERY_PAGE_MODE_SUITE,
            ),
        ),
        SettingDef(PreferKey.modernRssPage, "boolean"),
        SettingDef(PreferKey.defaultHomePage, "string", values = setOf("bookshelf", "explore", "rss", "my")),
        SettingDef(PreferKey.aiAssistantEnabled, "boolean"),
        SettingDef(PreferKey.aiEnterToSend, "boolean"),
        SettingDef(PreferKey.aiTavilyEnabled, "boolean"),
        SettingDef(PreferKey.aiTavilyTopic, "string", values = setOf("general", "news", "finance")),
        SettingDef(PreferKey.aiTavilySearchDepth, "string", values = setOf("basic", "advanced", "ultra-fast")),
        SettingDef(PreferKey.aiTavilyMaxResults, "int", min = 1, max = 10),
        SettingDef(PreferKey.aiAgentMaxToolRounds, "int", min = 4, max = 64),
        SettingDef(PreferKey.aiAgentToolMaxAttempts, "int", min = 1, max = 5),
        SettingDef(PreferKey.aiAgentToolRetryBackoffMillis, "int", min = 0, max = 5_000),
    )
    private val settingDefMap = settingDefs.associateBy { it.key }

    /** 凭据类键防御性拒绝（白名单已排除，此为兜底双保险）。 */
    private val FORBIDDEN_KEY_PARTS = listOf(
        "token", "apikey", "api_key", "password", "passwd", "secret", "auth", "cookie", "credential",
    )

    private fun isForbiddenKey(key: String): Boolean {
        val lower = key.lowercase()
        return FORBIDDEN_KEY_PARTS.any { lower.contains(it) } || lower == "key"
    }

    private fun readSetting(key: String, type: String): Any? {
        return when (key) {
            PreferKey.discoveryPageMode -> AppConfig.discoveryPageMode
            PreferKey.modernDiscoveryPage -> AppConfig.modernDiscoveryPage
            else -> when (type) {
                "boolean" -> appCtx.getPrefBoolean(key, false)
                "int" -> appCtx.getPrefInt(key, 0)
                else -> appCtx.getPrefString(key).orEmpty()
            }
        }
    }

    /** `prefs_get`：读取白名单偏好（非白名单键进 `ignored`，不报错）。 */
    suspend fun prefsGet(keys: List<String>): Map<String, Any?> = withContext(IO) {
        val requested = keys.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val items = mutableListOf<Map<String, Any?>>()
        val ignored = mutableListOf<String>()
        requested.forEach { key ->
            val def = settingDefMap[key]
            if (def == null || isForbiddenKey(key)) {
                ignored.add(key)
            } else {
                items.add(
                    mapOf(
                        "key" to key,
                        "type" to def.type,
                        "value" to readSetting(key, def.type),
                    )
                )
            }
        }
        mapOf(
            "count" to items.size,
            "items" to items,
            "ignored" to ignored,
        )
    }

    private fun applySetting(key: String, rawValue: Any?): Map<String, Any?> {
        val def = settingDefMap[key]
        if (def == null || isForbiddenKey(key)) {
            return mapOf("key" to key, "ok" to false, "error" to "unsupported key")
        }
        return runCatching {
            when (def.type) {
                "boolean" -> {
                    val value = when (rawValue) {
                        is Boolean -> rawValue
                        is String -> rawValue.equals("true", ignoreCase = true)
                        is Number -> rawValue.toInt() != 0
                        else -> throw IllegalArgumentException("invalid boolean")
                    }
                    if (key == PreferKey.modernDiscoveryPage) {
                        AppConfig.discoveryPageMode = if (value) {
                            AppConfig.DISCOVERY_PAGE_MODE_MODERN
                        } else {
                            AppConfig.DISCOVERY_PAGE_MODE_LEGACY
                        }
                    } else {
                        appCtx.putPrefBoolean(key, value)
                    }
                }

                "int" -> {
                    val value = when (rawValue) {
                        is Number -> rawValue.toInt()
                        is String -> rawValue.toIntOrNull() ?: throw IllegalArgumentException("invalid int")
                        else -> throw IllegalArgumentException("invalid int")
                    }
                    appCtx.putPrefInt(key, value.coerceIn(def.min ?: Int.MIN_VALUE, def.max ?: Int.MAX_VALUE))
                }

                else -> {
                    val value = rawValue?.toString()?.trim().orEmpty()
                    if (def.values.isNotEmpty() && value !in def.values) {
                        throw IllegalArgumentException("invalid enum")
                    }
                    if (key == PreferKey.discoveryPageMode) {
                        AppConfig.discoveryPageMode = value
                    } else {
                        appCtx.putPrefString(key, value)
                    }
                }
            }
            mapOf("key" to key, "ok" to true, "value" to readSetting(key, def.type))
        }.getOrElse {
            mapOf("key" to key, "ok" to false, "error" to (it.localizedMessage ?: "failed"))
        }
    }

    /** `prefs_save`：批量写白名单偏好（逐项返回结果；非白名单/凭据类键返回 `unsupported key`）。 */
    suspend fun prefsSave(values: Map<String, Any?>): Map<String, Any?> = withContext(IO) {
        require(values.isNotEmpty()) { "values 不能为空" }
        val results = values.map { (key, value) -> applySetting(key.trim(), value) }
        mapOf(
            "successCount" to results.count { it["ok"] == true },
            "totalCount" to results.size,
            "results" to results,
        )
    }

    /** `settings_search`：设置项检索（4 路匹配：标题 / 摘要 / key / 命中子项）。 */
    suspend fun settingsSearch(query: String): Map<String, Any?> = withContext(IO) {
        val q = query.trim().lowercase()
        val sections = buildSettingsSections(appCtx)
        val subItems = buildSettingsSubSearchItems(appCtx)
        val hits = mutableListOf<Map<String, Any?>>()
        sections.forEach { section ->
            section.rows.forEach { row ->
                val summary = row.summary.orEmpty()
                val matchedSubItems = if (q.isEmpty()) {
                    emptyList()
                } else {
                    subItems.filter { it.ownerKey == row.key && it.searchText.contains(q) }
                }
                val visible = q.isEmpty() ||
                    row.title.lowercase().contains(q) ||
                    summary.lowercase().contains(q) ||
                    row.key.lowercase().contains(q) ||
                    matchedSubItems.isNotEmpty()
                if (visible) {
                    hits.add(
                        mapOf(
                            "key" to row.key,
                            "title" to row.title,
                            "summary" to (matchedSubItems.firstOrNull()?.title ?: summary),
                            "section" to section.title,
                            "matchedSubItemCount" to matchedSubItems.size,
                        )
                    )
                }
            }
        }
        mapOf("query" to query, "total" to hits.size, "items" to hits)
    }

    /**
     * `theme_mode_set`：设置主题模式（三态）。
     *
     * `mode`：`"1"`=日 / `"2"`=夜 / `"3"`=E-Ink / 其它（含 `"0"`）=跟随系统；写 `AppConfig.themeMode`。
     */
    suspend fun themeModeSet(mode: String): Map<String, Any?> = withContext(IO) {
        val normalized = mode.trim().takeIf { it == "1" || it == "2" || it == "3" } ?: "0"
        AppConfig.themeMode = normalized
        // 派生值按口径现算（AppConfig 的派生字段依赖异步偏好监听，避免读取到滞后值）：
        // "1"→日 / "2"→夜 / "3"→E-Ink / 其它→跟随系统（读 AppConfig.isNightTheme）
        val isNight = when (normalized) {
            "1", "3" -> false
            "2" -> true
            else -> AppConfig.isNightTheme
        }
        mapOf(
            "themeMode" to normalized,
            "isNightTheme" to isNight,
            "isEInkMode" to (normalized == "3"),
        )
    }

    // ============================================================ 三期 G 组（REST：应用信息）

    /**
     * 应用信息（三期 REQ-3-512 `GET /getAppInfo`）。
     *
     * `consoleApiLevel` 为**控制台接口契约级别**（四期 boot 页版本协议用：前端 `manifest.minAppApiLevel`
     * 与之比对，高于则提示"请升级 App"；接口契约变更时递增）。
     */
    suspend fun appInfo(): Map<String, Any?> = withContext(IO) {
        val info = AppConst.appInfo
        mapOf(
            "versionName" to info.versionName,
            "versionCode" to info.versionCode,
            "appVariant" to info.appVariant.name,
            "packageName" to appCtx.packageName,
            "consoleApiLevel" to CONSOLE_API_LEVEL,
        )
    }

    /** 控制台接口契约级别（四期版本协议比对基线；接口契约变更时递增）。 */
    const val CONSOLE_API_LEVEL: Int = 1

    // ============================================================ 三期 L11（S3 支撑件：连通性自检）

    /**
     * 连通性自检探针（三期 `L11` / REQ-3-905，REST `/ping` + MCP `legado_ping` 同源）。
     *
     * 控制台「三段自检」的第③段用它（① REST 可达性、② MCP `initialize` 由客户端/前端各自完成）；
     * 四期 SC-4-09（自检）与 SC-4-13（角色一/二剧本）复用**同一实现，不另立**（AD-11）。
     *
     * **安全边界（AD-18 同类）**：只回**非敏感**运行时信息 —— 版本 / 契约级别 / 服务与中继是否在跑 /
     * 服务器时间 / MCP 挂载路径；**绝不含**令牌、访问地址、IP、用户数据。
     */
    suspend fun ping(): Map<String, Any?> = withContext(IO) {
        val info = AppConst.appInfo
        mapOf(
            "ok" to true,
            "serverTime" to System.currentTimeMillis(),
            "versionName" to info.versionName,
            "consoleApiLevel" to CONSOLE_API_LEVEL,
            "webServiceRunning" to WebService.isRun,
            "mcpEndpoint" to "/mcp",
        )
    }
}