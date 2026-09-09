package io.legado.app.help.readaloud.casting

import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.TtsCastingTemplate
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.readaloud.speech.SpeechRouteSanitizer
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.flow.Flow
import splitties.init.appCtx

/**
 * 范式模板配置层仓库（AD-09 L-c，§3.5）
 * 单一权威源=ttsCastingTemplates Room 表（§1.8-D 防三源同步）；内存只读快照随调用生命周期
 * 内置 4 模板经 assets/defaultData/tts/castingTemplates.json 首装/升级幂等导入（DefaultData 同款机制）
 */
object TtsCastingStore {

    /** 全局默认激活模板 id（ PreferKey 单键承载，防动态键游离注册表） */
    @Volatile
    private var activeTemplateId: String? = null

    /** 激活模板快照缓存（调用侧只读，禁止外部写） */
    @Volatile
    private var activeRuleSet: CastingRuleSet? = null

    /** 快照缓存键（期2 修复：含书级覆盖维度，防 A 书覆盖串到 B 书） */
    @Volatile
    private var activeRuleSetCacheKey: String? = null

    suspend fun all(): List<TtsCastingTemplate> = appDb.ttsCastingTemplateDao.all()

    suspend fun get(id: String): TtsCastingTemplate? = appDb.ttsCastingTemplateDao.get(id)

    suspend fun save(template: TtsCastingTemplate) {
        appDb.ttsCastingTemplateDao.insert(template)
        // 编辑器保存热生效：写入口统一收敛失效（四路写入口联动链口径）
        invalidateSnapshot()
    }

    suspend fun deleteById(id: String) {
        if (activeTemplateId == id) {
            activeTemplateId = null
            activeRuleSet = null
            activeRuleSetCacheKey = null
            appCtx.putPrefString(PreferKey.ttsCastingActiveId, "")
        }
        appDb.ttsCastingTemplateDao.deleteById(id)
        // 删除模板后书级覆盖引用由 resolve 侧空安全兜底回退（AD-13），快照必须失效
        invalidateSnapshot()
    }

    /** 激活模板（全局默认）：空=单声（builtin_mono 语义） */
    fun activeTemplateId(): String? {
        if (activeTemplateId == null) {
            activeTemplateId = appCtx.getPrefString(PreferKey.ttsCastingActiveId)
                ?.ifBlank { null }
        }
        return activeTemplateId
    }

    fun setActiveTemplateId(id: String?) {
        activeTemplateId = id
        activeRuleSet = null
        appCtx.putPrefString(PreferKey.ttsCastingActiveId, id ?: "")
    }

    /** 书级模板覆盖（§1.8-D-2/R11 验收 6）：书级优先全局；bookKey=书唯一键 */
    suspend fun resolveActiveTemplateId(bookKey: String): String? {
        val bookOverride = appCtx.getPrefString(PreferKey.ttsCastingBookOverridePrefix + bookKey)
        return bookOverride?.ifBlank { null } ?: activeTemplateId()
    }

    fun setBookOverrideTemplateId(bookKey: String, templateId: String?) {
        appCtx.putPrefString(
            PreferKey.ttsCastingBookOverridePrefix + bookKey,
            templateId ?: ""
        )
        // 书级覆盖写入口收敛失效（四路写入口联动链口径：书级覆盖切换立即生效）
        invalidateSnapshot()
    }

    /**
     * 解析当前生效模板的规则集（含 current 哨兵替换，§3.5.1）
     * 返回 null=多人模式未启用（未激活模板）
     * 快照缓存键控=(书级覆盖 id, 模板 id) 二元组：同书同模板命中缓存，跨书/覆盖变更重读 Room
     */
    suspend fun resolveActiveRuleSet(bookKey: String): CastingRuleSet? {
        val templateId = resolveActiveTemplateId(bookKey)
        if (templateId == null) {
            // TtsTrace 真机联调：未激活模板=多角色关闭的第一嫌疑点
            AppLog.putDebugWithTag(
                AppLog.TAG_TTS_TRACE,
                "resolve 未激活模板（multi 关闭）bookKey=${bookKey.takeLast(24)}",
                level = AppLog.Level.INFO
            )
            return null
        }
        val overrideId = appCtx.getPrefString(PreferKey.ttsCastingBookOverridePrefix + bookKey)
            ?.ifBlank { null }
        val cacheKey = "override:$overrideId|template:$templateId"
        val cached = activeRuleSet
        if (cached != null && activeRuleSetCacheKey == cacheKey) {
            return cached
        }
        val entity = appDb.ttsCastingTemplateDao.get(templateId)
        if (entity == null) {
            // TtsTrace 真机联调：模板 id 存在但 Room 无记录（导入缺失/库异常）
            AppLog.putWarn("TtsTrace resolve 模板不存在 templateId=$templateId")
            return null
        }
        val ruleSet = CastingRuleSet.fromEntity(entity)
        if (ruleSet == null) {
            // TtsTrace 真机联调：rulesJson 解析失败（schemaVersion/结构异常）
            AppLog.putWarn("TtsTrace resolve 规则集解析失败 templateId=$templateId")
            return null
        }
        AppLog.putDebugWithTag(
            AppLog.TAG_TTS_TRACE,
            "resolve 载入规则集 templateId=$templateId override=${overrideId ?: "无"} 规则=${ruleSet.rules.size}",
            level = AppLog.Level.INFO
        )
        activeRuleSet = ruleSet
        activeRuleSetCacheKey = cacheKey
        return ruleSet
    }

    /** 使快照失效（模板编辑/导入/切换/书级覆盖变更后调用，下一次 resolve 重读） */
    fun invalidateSnapshot() {
        activeRuleSet = null
        activeRuleSetCacheKey = null
    }

    /**
     * tag → 声源解析（§3.5.5 六级链）：AI 角色绑定 > cast_role > 选角模板规则 > 性别兜底 > narrator > 默认
     * 期2：前两级仅在 tag 带 ai: 前缀时激活（characterId 由 AI 分镜段传入，既有调用方默认 0 零改动）
     */
    suspend fun resolveSourceForTag(
        bookKey: String,
        tag: String,
        ruleSet: CastingRuleSet,
        characterId: Long = 0L
    ): SpeechRoute? {
        // 前两级激活闸：ai: 命名空间 + characterId>0（AI 分镜段才携带）
        if (characterId > 0L && tag.startsWith(CastingTag.AI_PREFIX)) {
            // ① BookCharacter 显式绑定：角色 speechRouteJson 经校验后命中
            runCatching {
                appDb.bookCharacterDao.getCharacter(characterId)?.let { character ->
                    SpeechRouteSanitizer.validOrNull(SpeechRoute.fromJson(character.speechRouteJson))
                        ?.takeIf { it.isConfigured }
                        ?.let { return it }
                }
            }.onFailure {
                AppLog.put("TTS 选角角色绑定解析失败：${it.message}")
            }
            // ② cast_role 每书角色绑定：按角色名查询本书绑定（写入经 AI 链既有 AUTO 收编路径）
            val roleName = tag.removePrefix(CastingTag.AI_PREFIX)
            runCatching {
                appDb.bookCharacterDao.getCharacter(bookKey, roleName)?.let { character ->
                    SpeechRouteSanitizer.validOrNull(SpeechRoute.fromJson(character.speechRouteJson))
                        ?.takeIf { it.isConfigured }
                        ?.let { return it }
                }
            }.onFailure {
                AppLog.put("TTS cast_role 绑定解析失败：${it.message}")
            }
        }
        // 模板规则：数组顺序首命中（§3.5.1 优先级语义）
        val rule = ruleSet.rules.firstOrNull { it.tag == tag }
        if (rule != null) {
            val source = substituteCurrentSentinel(rule.source)
            if (source != null) {
                // TtsTrace 真机联调：规则首命中（tag→声源映射核心证据）
                AppLog.putDebugWithTag(
                    AppLog.TAG_TTS_TRACE,
                    "resolveSource tag=$tag 命中规则 → ${source.engineType}:${source.engineValue}:${source.speakerName}",
                    level = AppLog.Level.INFO
                )
                return source
            }
            AppLog.putWarn("TtsTrace resolveSource tag=$tag 规则声源无效，回退 fallbackSource")
        }
        // fallbackSource 兜底
        if (ruleSet.fallbackSourceJson.isNotBlank()) {
            val fallback = SpeechRoute.fromJson(ruleSet.fallbackSourceJson)
            if (fallback.engineValue.isNotBlank() || fallback.engineType == SpeechRoute.ENGINE_DEFAULT) {
                AppLog.putDebugWithTag(
                    AppLog.TAG_TTS_TRACE,
                    "resolveSource tag=$tag 未命中规则 → fallbackSource ${fallback.engineType}:${fallback.engineValue}",
                    level = AppLog.Level.INFO
                )
                return fallback
            }
        }
        // 最终兜底：default 路由（当前引擎默认音）
        AppLog.putWarn("TtsTrace resolveSource tag=$tag → 引擎默认音兜底（无规则无有效fallback）")
        return SpeechRoute(engineType = SpeechRoute.ENGINE_DEFAULT)
    }

    /** current 哨兵替换（§3.5.1）：engineValue=current → 当前生效路由 */
    private fun substituteCurrentSentinel(source: SpeechRoute): SpeechRoute? {
        if (source.engineType == SpeechRoute.ENGINE_SYSTEM &&
            source.engineValue == CastingRule.CURRENT_SENTINEL
        ) {
            val current = SpeechRoute.resolveSpeechRoute(ReadAloudDelegate.currentTtsEngineRaw())
            return current.copy(
                speakerName = source.speakerName.ifBlank { current.speakerName },
                source = source.source
            )
        }
        if (source.engineValue.isBlank() && source.engineType == SpeechRoute.ENGINE_SYSTEM) {
            // 空包名=系统默认引擎，合法
            return source
        }
        if (source.engineValue.isBlank() && source.engineType != SpeechRoute.ENGINE_DEFAULT) {
            return null
        }
        return source
    }

    /**
     * 内置模板幂等导入（assets/defaultData/tts/castingTemplates.json，DefaultData 同款机制）
     * 幂等键=templateId；builtin 冲突跳过+计数；仅导入 builtin=true 记录
     */
    suspend fun importBuiltinTemplates(json: String): Pair<Int, Int> {
        var imported = 0
        var skipped = 0
        runCatching {
            val array = org.json.JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val template = GSON.fromJsonObject<TtsCastingTemplate>(obj.toString()).getOrNull()
                    ?: continue
                if (!template.builtin) {
                    skipped++
                    continue
                }
                val existing = appDb.ttsCastingTemplateDao.get(template.id)
                if (existing != null) {
                    skipped++
                    continue
                }
                appDb.ttsCastingTemplateDao.insert(template)
                imported++
            }
        }.onFailure {
            AppLog.put("内置选角模板导入失败：${it.message}", it)
        }
        if (imported > 0) invalidateSnapshot()
        return imported to skipped
    }

    /**
     * 导出模板为分享 JSON（文件/剪贴板双通道由宿主承接）
     * 结构=CastingRulesWrapper（schemaVersion+rules），对齐 tts-server 分享习惯
     */
    fun exportToJson(ruleSet: CastingRuleSet): String {
        val wrapper = CastingRuleSet.CastingRulesWrapper(
            schemaVersion = CastingRuleSet.SCHEMA_VERSION,
            rules = ruleSet.rules
        )
        return GSON.toJson(wrapper)
    }

    /** 导入结果（编辑器 IMPORT 态呈现依据） */
    data class ImportResult(
        val imported: Int = 0,
        val keptBoth: Int = 0,
        val pendingBinding: Int = 0,
        val error: String? = null
    )

    /**
     * 导入校验链（顺序固定，§3.2-14/15）：
     * ignoreUnknownKeys 容错解析 → schemaVersion 超前拒绝 → 同通道/保留字/regex 预编译/pattern 限长校验
     * → 声源可达性校验（缺声源规则标记"待绑定"而非静默生效）→ 幂等写入（builtin 跳过+计数 / 自定义冲突 KEEP_BOTH）
     * → invalidateSnapshot()
     */
    suspend fun importFromJson(json: String, name: String): ImportResult {
        val wrapper = runCatching {
            GSON.fromJsonObject<CastingRuleSet.CastingRulesWrapper>(json).getOrThrow()
        }.getOrElse {
            return ImportResult(error = "JSON 解析失败：${it.message}")
        }
        val schemaVersion = wrapper.schemaVersion
        if (schemaVersion > CastingRuleSet.SCHEMA_VERSION) {
            return ImportResult(error = "模板版本（schema $schemaVersion）高于当前版本，请升级 App 后导入")
        }
        val rules = wrapper.rules
        if (rules.isEmpty()) {
            return ImportResult(error = "模板无有效规则行")
        }
        // ReDoS 防护：pattern 限长 + regex 预编译
        for (rule in rules) {
            if (rule.pattern.length > MAX_PATTERN_LENGTH) {
                return ImportResult(error = "规则 pattern 超过 ${MAX_PATTERN_LENGTH} 字符限长（tag=${rule.tag}）")
            }
            if (rule.matchType == CastingMatchType.REGEX) {
                runCatching { Regex(rule.pattern) }.getOrElse {
                    return ImportResult(error = "非法正则（tag=${rule.tag}）：${it.message}")
                }
            }
        }
        // 同通道校验（跨通道组合拒绝导入）
        if (!CastingRuleSet(rules = rules, name = name).sameChannel()) {
            return ImportResult(error = "同通道约束：规则内全部声源须为同一引擎类型（跨通道组合拒绝导入）")
        }
        // 声源可达性校验：缺声源规则标记"待绑定"（sourceJson 保留，UI 呈现待绑定态）
        var pendingBinding = 0
        for (rule in rules) {
            if (!rule.sourceValid) pendingBinding++
        }
        // 幂等写入：新模板 id（导入即新建，冲突 KEEP_BOTH）
        val templateId = "import_${System.currentTimeMillis()}"
        val entity = TtsCastingTemplate(
            id = templateId,
            name = name.ifBlank { "导入模板" },
            builtin = false,
            enabled = true,
            sortOrder = 0,
            rulesJson = GSON.toJson(
                CastingRuleSet.CastingRulesWrapper(
                    schemaVersion = CastingRuleSet.SCHEMA_VERSION,
                    rules = rules
                )
            ),
            fallbackSourceJson = "",
            lastUpdateTime = System.currentTimeMillis()
        )
        appDb.ttsCastingTemplateDao.insert(entity)
        invalidateSnapshot()
        return ImportResult(imported = 1, pendingBinding = pendingBinding)
    }

    /** pattern 限长（ReDoS 防护第一道，§3.2-15） */
    const val MAX_PATTERN_LENGTH = 256
}

/**
 * ReadAloud 依赖桥（避免 casting 层直接依赖 model 层造成循环引用）
 */
object ReadAloudDelegate {
    fun currentTtsEngineRaw(): String {
        return io.legado.app.model.ReadBook.book?.getTtsEngine()
            ?: io.legado.app.help.config.AppConfig.ttsEngine.orEmpty()
    }
}
