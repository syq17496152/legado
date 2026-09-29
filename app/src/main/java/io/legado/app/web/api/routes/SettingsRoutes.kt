package io.legado.app.web.api.routes

import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD.Method
import io.legado.app.api.ReturnData
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.ParagraphRule
import io.legado.app.data.entities.ReadAloudBgmGroup
import io.legado.app.data.entities.ReadAloudBgmTrack
import io.legado.app.data.entities.ReadAloudSpeakerGroupItem
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.TtsCastingTemplate
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.model.AutoTaskRule
import io.legado.app.service.kernel.AppSettingsKernel
import io.legado.app.service.kernel.AutoTaskKernel
import io.legado.app.service.kernel.BackupKernel
import io.legado.app.service.kernel.BookSourceKernel
import io.legado.app.service.kernel.DiagKernel
import io.legado.app.service.kernel.RssSourceKernel
import io.legado.app.service.kernel.RuleKernel
import io.legado.app.service.kernel.TtsKernel
import io.legado.app.ui.book.read.config.HighlightRule
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager
import io.legado.app.web.TokenManager.Level
import io.legado.app.web.api.ApiContext
import io.legado.app.web.api.ApiRoute
import io.legado.app.web.mcp.McpArgs

/**
 * G 组 · 缺口补齐路由声明（web-mcp-productization 三期 · spec 4.5 G 组，
 * REQ-3-504 ~ REQ-3-515，共 36 端点）。
 *
 * 覆盖（按 spec 编号）：高亮规则 / 订阅规则与排序 / 朗读角色分组 / TTS 配音模板 / 朗读 BGM /
 * 规则四类（TXT 目录 · 段落 · 字典 · 书内段落）与补全 / 备份配置 / 清缓存 / 应用信息 /
 * 令牌管理 / 书源回收站 / 自动任务（供 P4 / P11 / P12 / P14 页）。
 *
 * 设计原则（AD-3-01 / AD-10）：每端点 = 一个 kernel 方法投影；本文件只做「声明 + 取参 + 委派」，
 * 不内联业务逻辑；`HttpServer.kt` 全程零改动（REQ-3-109 / SC-3-18）。响应体由 [ReturnData] 包
 * `data` 后交 `ApiEnvelope` 装信封（与一期端点同构）。
 *
 * 取参口径：GET 走查询参数；POST 走 JSON 请求体（[bodyArgs]），与二期 MCP 工具同口径。
 *
 * 红线复述：
 * 1. `/clearCache`（REQ-3-511）只清**可重建缓存**（books/video/audio/webview），实现单源在
 *    [DiagKernel.cacheClear] 的白名单目录，**绝不触碰用户数据**（书架 / 书源 / 订阅源 / 阅读记录 /
 *    书签 / 备份）；定级 [Level.ADMIN]（与二期 `cache_clear` 一致）。
 * 2. `/genToken`（REQ-3-513）定级 [Level.ADMIN]，令牌生成是**高权限动作**（可无限续期 / 绕过
 *    撤销）。⚠️ **已知降级**：spec 要求「仅限本机（localhost）调用」，但一期 [ApiContext] 尚未
 *    携带请求来源 IP（无 `remoteIpAddress` 字段）⇒ 本层**无法判定是否本机**，本期只做 admin 级别
 *    拦截（**如实降级**）。升级路径：给 [ApiContext] 补来源 IP 后在此加 `isLocalhost` 判定（不得改 WebAuth）。
 */
object SettingsRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        // ============================================================ REQ-3-504 高亮规则
        ApiRoute(
            Method.GET, "/getHighlightRules", Level.READONLY, mcpToolName = "highlight_rules_get"
        ) { _ ->
            ReturnData().setData(RuleKernel.highlightRules())
        },
        ApiRoute(
            Method.POST, "/saveHighlightRule", Level.MANAGE, mcpToolName = "highlight_rule_save"
        ) { ctx ->
            ReturnData().setData(RuleKernel.saveHighlightRule(ctx.bodyArgs().model<HighlightRule>("rule")))
        },
        ApiRoute(
            Method.POST, "/deleteHighlightRule", Level.MANAGE, mcpToolName = "highlight_rule_delete"
        ) { ctx ->
            val ids = ctx.bodyArgs().strList("ids")
            if (ids.isEmpty()) throw IllegalArgumentException("参数ids不能为空")
            ReturnData().setData(mapOf("deleted" to RuleKernel.deleteHighlightRules(ids)))
        },

        // ============================================================ REQ-3-505 订阅规则 / 排序
        ApiRoute(Method.GET, "/getRuleSubs", Level.READONLY, mcpToolName = "rule_sub_get") { _ ->
            ReturnData().setData(RuleKernel.ruleSubs())
        },
        ApiRoute(Method.POST, "/saveRuleSub", Level.MANAGE, mcpToolName = "rule_sub_save") { ctx ->
            ReturnData().setData(RuleKernel.saveRuleSub(ctx.bodyArgs().model<RuleSub>("sub")))
        },
        // 订阅源排序：`url`+`order` 精确设置，或 `urls` 数组按序整体重排（二者之一）
        ApiRoute(Method.POST, "/setRssSort", Level.MANAGE, mcpToolName = "rss_set_sort") { ctx ->
            val body = ctx.bodyArgs()
            if (body.strOrNull("url") == null && body.strList("urls").isEmpty()) {
                throw IllegalArgumentException("需要 url+order 或 urls 之一")
            }
            ReturnData().setData(
                RssSourceKernel.setSort(
                    url = body.strOrNull("url"),
                    order = body.intOrNull("order"),
                    urls = body.strList("urls"),
                )
            )
        },

        // ============================================================ REQ-3-506 朗读角色分组
        ApiRoute(
            Method.GET, "/getSpeakerGroups", Level.READONLY, mcpToolName = "speaker_groups_get"
        ) { _ ->
            ReturnData().setData(TtsKernel.speakerGroupsGet())
        },
        ApiRoute(
            Method.POST, "/saveSpeakerGroup", Level.MANAGE, mcpToolName = "speaker_group_save"
        ) { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                TtsKernel.speakerGroupSave(
                    groupId = body.long("groupId", 0L),
                    name = body.strOrNull("name"),
                    enabled = body.boolOrNull("enabled"),
                    items = body.modelOrNull<List<ReadAloudSpeakerGroupItem>>("items"),
                )
            )
        },

        // ============================================================ REQ-3-507 TTS 配音模板
        ApiRoute(
            Method.GET, "/getTtsCastingTemplates", Level.READONLY, mcpToolName = "tts_casting_get"
        ) { _ ->
            ReturnData().setData(TtsKernel.castingGet())
        },
        ApiRoute(
            Method.POST, "/saveTtsCastingTemplate", Level.MANAGE, mcpToolName = "tts_casting_save"
        ) { ctx ->
            ReturnData().setData(
                TtsKernel.castingSave(ctx.bodyArgs().model<TtsCastingTemplate>("template"))
            )
        },

        // ============================================================ REQ-3-508 朗读 BGM
        ApiRoute(
            Method.GET, "/getReadAloudBgm", Level.READONLY, mcpToolName = "read_aloud_bgm_get"
        ) { ctx ->
            ReturnData().setData(TtsKernel.bgmGet(ctx.param("assetType")))
        },
        ApiRoute(
            Method.POST, "/saveReadAloudBgm", Level.MANAGE, mcpToolName = "read_aloud_bgm_save"
        ) { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                TtsKernel.bgmSave(
                    group = body.modelOrNull<ReadAloudBgmGroup>("group"),
                    tracks = body.modelOrNull<List<ReadAloudBgmTrack>>("tracks"),
                )
            )
        },

        // ============================================================ REQ-3-509 规则四类 + 补全
        ApiRoute(Method.GET, "/getTxtTocRules", Level.READONLY, mcpToolName = "txt_toc_rule_get") { _ ->
            ReturnData().setData(RuleKernel.txtTocRules())
        },
        ApiRoute(
            Method.POST, "/saveTxtTocRule", Level.MANAGE, mcpToolName = "txt_toc_rule_save"
        ) { ctx ->
            ReturnData().setData(RuleKernel.saveTxtTocRule(ctx.bodyArgs().model<TxtTocRule>("rule")))
        },
        ApiRoute(
            Method.POST, "/deleteTxtTocRule", Level.MANAGE, mcpToolName = "txt_toc_rule_delete"
        ) { ctx ->
            val ids = ctx.bodyArgs().strList("ids").mapNotNull { it.toLongOrNull() }
            if (ids.isEmpty()) throw IllegalArgumentException("参数ids不能为空")
            ReturnData().setData(mapOf("deleted" to RuleKernel.deleteTxtTocRules(ids)))
        },
        ApiRoute(
            Method.GET, "/getParagraphRules", Level.READONLY, mcpToolName = "paragraph_rule_get"
        ) { _ ->
            ReturnData().setData(RuleKernel.paragraphRules())
        },
        ApiRoute(
            Method.POST, "/saveParagraphRule", Level.MANAGE, mcpToolName = "paragraph_rule_save"
        ) { ctx ->
            ReturnData().setData(
                RuleKernel.saveParagraphRule(ctx.bodyArgs().model<ParagraphRule>("rule"))
            )
        },
        // 段落规则调试（与 App 内调试链同源，不落库）
        ApiRoute(
            Method.POST, "/testParagraphRule", Level.MANAGE, mcpToolName = "test_paragraph_rule"
        ) { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                RuleKernel.testParagraphRule(
                    bookUrl = body.str("bookUrl")
                        ?: throw IllegalArgumentException("参数bookUrl不能为空"),
                    chapterIndex = body.intOrNull("chapterIndex") ?: 0,
                    maxLength = body.intOrNull("maxLength") ?: 2000,
                )
            )
        },
        ApiRoute(Method.GET, "/getDictRules", Level.READONLY, mcpToolName = "dict_rule_get") { _ ->
            ReturnData().setData(RuleKernel.dictRules())
        },
        ApiRoute(Method.POST, "/saveDictRule", Level.MANAGE, mcpToolName = "dict_rule_save") { ctx ->
            val rule = ctx.bodyArgs().model<DictRule>("rule")
            if (rule.name.isBlank()) throw IllegalArgumentException("字典规则name不能为空")
            ReturnData().setData(RuleKernel.saveDictRule(rule))
        },
        ApiRoute(
            Method.GET, "/getBookParagraphRules", Level.READONLY,
            mcpToolName = "book_paragraph_rule_get"
        ) { ctx ->
            ReturnData().setData(RuleKernel.bookParagraphRules(ctx.requireParam("bookUrl")))
        },
        ApiRoute(
            Method.POST, "/saveBookParagraphRule", Level.MANAGE,
            mcpToolName = "book_paragraph_rule_save"
        ) { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                RuleKernel.saveBookParagraphRules(
                    bookUrl = body.str("bookUrl")
                        ?: throw IllegalArgumentException("参数bookUrl不能为空"),
                    ruleIds = body.strList("ruleIds").mapNotNull { it.toLongOrNull() },
                    enabled = body.bool("enabled", true),
                )
            )
        },
        ApiRoute(
            Method.GET, "/getRuleCompletions", Level.READONLY, mcpToolName = "rule_completions_get"
        ) { ctx ->
            ReturnData().setData(
                RuleKernel.completions(
                    rules = ctx.requireParam("rules"),
                    preRule = ctx.param("preRule"),
                    type = ctx.intParam("type", 1),
                )
            )
        },

        // ============================================================ REQ-3-510 备份配置
        ApiRoute(
            Method.GET, "/getBackupConfig", Level.READONLY, mcpToolName = "backup_config_get"
        ) { _ ->
            ReturnData().setData(BackupKernel.configGet())
        },
        ApiRoute(
            Method.POST, "/saveBackupConfig", Level.MANAGE, mcpToolName = "backup_config_save"
        ) { ctx ->
            ReturnData().setData(
                BackupKernel.configSave(ctx.bodyArgs().model<Map<String, Boolean>>("ignore"))
            )
        },

        // ============================================================ REQ-3-511 清缓存（白名单，绝不碰用户数据）
        ApiRoute(Method.POST, "/clearCache", Level.ADMIN, mcpToolName = "cache_clear") { ctx ->
            val kind = ctx.bodyArgsOrEmpty().strOrNull("kind") ?: DiagKernel.KIND_ALL
            ReturnData().setData(DiagKernel.cacheClear(kind))
        },

        // ============================================================ REQ-3-512 应用信息（含 consoleApiLevel）
        ApiRoute(Method.GET, "/getAppInfo", Level.READONLY) { _ ->
            ReturnData().setData(AppSettingsKernel.appInfo())
        },

        // ============================================================ REQ-3-513 令牌管理（admin；localhost 限制见类注释降级说明）
        ApiRoute(Method.POST, "/genToken", Level.ADMIN) { ctx ->
            ReturnData().setData(TokenManager.generate(levelOf(ctx.bodyArgsOrEmpty().strOrNull("level"))))
        },

        // ============================================================ REQ-3-514 书源回收站（供 P4）
        ApiRoute(
            Method.GET, "/getSourceRecycleBin", Level.READONLY, mcpToolName = "source_recycle_list"
        ) { ctx ->
            ReturnData().setData(BookSourceKernel.recycleBin(ctx.param("type")))
        },
        ApiRoute(Method.POST, "/restoreSource", Level.MANAGE, mcpToolName = "source_restore") { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                BookSourceKernel.restoreFromRecycle(
                    id = body.requireLong("id"),
                    overwrite = body.bool("overwrite", false),
                )
            )
        },

        // ============================================================ REQ-3-515 自动任务（供 P12）
        ApiRoute(
            Method.GET, "/getAutoTaskRules", Level.READONLY, mcpToolName = "auto_task_rule_get"
        ) { _ ->
            ReturnData().setData(AutoTaskKernel.rules())
        },
        ApiRoute(
            Method.POST, "/saveAutoTaskRule", Level.MANAGE, mcpToolName = "auto_task_rule_save"
        ) { ctx ->
            ReturnData().setData(AutoTaskKernel.ruleSave(ctx.bodyArgs().model<AutoTaskRule>("rule")))
        },
        ApiRoute(
            Method.POST, "/deleteAutoTaskRule", Level.MANAGE, mcpToolName = "auto_task_rule_delete"
        ) { ctx ->
            val ids = ctx.bodyArgs().strList("ids")
            if (ids.isEmpty()) throw IllegalArgumentException("参数ids不能为空")
            ReturnData().setData(AutoTaskKernel.ruleDelete(ids))
        },
        ApiRoute(Method.POST, "/runAutoTask", Level.MANAGE, mcpToolName = "auto_task_run") { ctx ->
            ReturnData().setData(AutoTaskKernel.run(ctx.bodyArgsOrEmpty().strOrNull("ruleId")))
        },
        ApiRoute(
            Method.GET, "/getAutoTaskTocDiff", Level.READONLY, mcpToolName = "auto_task_toc_diff"
        ) { ctx ->
            ReturnData().setData(AutoTaskKernel.tocDiff(ctx.requireParam("ruleId")))
        },
        ApiRoute(Method.GET, "/autoTaskLog", Level.READONLY, mcpToolName = "auto_task_log") { ctx ->
            ReturnData().setData(
                AutoTaskKernel.log(
                    ruleId = ctx.requireParam("ruleId"),
                    maxLength = ctx.intParam("maxLength", 4000),
                )
            )
        },
    )

    /** 令牌级别参数 → [TokenManager.Level]（缺省 readonly；未知值 ⇒ 400）。 */
    private fun levelOf(raw: String?): TokenManager.Level = when (raw?.trim()?.lowercase()) {
        null, "", "readonly" -> TokenManager.Level.READONLY
        "manage" -> TokenManager.Level.MANAGE
        "admin" -> TokenManager.Level.ADMIN
        else -> throw IllegalArgumentException("level 仅支持 readonly/manage/admin：$raw")
    }
}

/** 请求体 → 参数访问器（与二期 MCP 工具同口径：类型不匹配按缺失处理）。 */
private fun ApiContext.bodyArgs(): McpArgs =
    McpArgs(GSON.fromJsonObject<JsonObject>(requirePostData()).getOrNull() ?: JsonObject())

/**
 * 请求体 → 参数访问器（**入参全可选的端点**用：无请求体 / 请求体非 JSON 对象 ⇒ 空对象）。
 *
 * 适用端点：`/clearCache`、`/genToken`、`/runAutoTask` —— 它们可以在不带任何入参时调用，
 * 若强制要求请求体会把「无参调用」误判成 400。
 */
private fun ApiContext.bodyArgsOrEmpty(): McpArgs =
    McpArgs(
        postData?.takeIf { it.isNotBlank() }
            ?.let { GSON.fromJsonObject<JsonObject>(it).getOrNull() }
            ?: JsonObject()
    )

/**
 * 取嵌套模型：字段既接受对象也接受 JSON 字符串（与二期 MCP 工具 parseJson 同口径）。
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

/** 取可选嵌套模型：字段缺失 / JSON null ⇒ null（= 内核「该项不改」语义）。 */
private inline fun <reified T> McpArgs.modelOrNull(key: String): T? {
    val element = raw().get(key) ?: return null
    if (element.isJsonNull) return null
    val json = if (element.isJsonPrimitive) element.asString else GSON.toJson(element)
    return GSON.fromJsonObject<T>(json)
        .getOrElse { throw IllegalArgumentException("参数 $key 格式不正确：${it.message}") }
}

/** 取必填长整数（缺失 / 非法格式 ⇒ 400）。 */
private fun McpArgs.requireLong(key: String): Long =
    str(key)?.toLongOrNull()
        ?: throw IllegalArgumentException("缺少必填参数或格式非法：$key（须为整数）")

/** 取可选布尔：字段缺失或 JSON null ⇒ null（= 内核「沿用现值」语义）。 */
private fun McpArgs.boolOrNull(key: String): Boolean? =
    if (raw().has(key) && !raw().get(key).isJsonNull) bool(key) else null

/** 取可选整数：字段缺失或 JSON null ⇒ null。 */
private fun McpArgs.intOrNull(key: String): Int? =
    if (raw().has(key) && !raw().get(key).isJsonNull) int(key) else null

/** 取可选字符串：字段缺失或 JSON null ⇒ null（空串保留，供「清空字段」用）。 */
private fun McpArgs.strOrNull(key: String): String? =
    if (raw().has(key) && !raw().get(key).isJsonNull) str(key) else null