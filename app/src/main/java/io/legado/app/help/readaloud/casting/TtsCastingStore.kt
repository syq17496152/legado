package io.legado.app.help.readaloud.casting

import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.TtsCastingTemplate
import io.legado.app.help.readaloud.speech.SpeechRoute
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

    suspend fun all(): List<TtsCastingTemplate> = appDb.ttsCastingTemplateDao.all()

    suspend fun get(id: String): TtsCastingTemplate? = appDb.ttsCastingTemplateDao.get(id)

    suspend fun save(template: TtsCastingTemplate) {
        appDb.ttsCastingTemplateDao.insert(template)
    }

    suspend fun deleteById(id: String) {
        if (activeTemplateId == id) {
            activeTemplateId = null
            activeRuleSet = null
            appCtx.putPrefString(PreferKey.ttsCastingActiveId, "")
        }
        appDb.ttsCastingTemplateDao.deleteById(id)
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
    }

    /**
     * 解析当前生效模板的规则集（含 current 哨兵替换，§3.5.1）
     * 返回 null=多人模式未启用（未激活模板）
     */
    suspend fun resolveActiveRuleSet(bookKey: String): CastingRuleSet? {
        activeRuleSet?.let { return it }
        val templateId = resolveActiveTemplateId(bookKey) ?: return null
        val entity = appDb.ttsCastingTemplateDao.get(templateId) ?: return null
        val ruleSet = CastingRuleSet.fromEntity(entity) ?: return null
        activeRuleSet = ruleSet
        return ruleSet
    }

    /** 使快照失效（模板编辑/导入/切换后调用，下一次 resolve 重读） */
    fun invalidateSnapshot() {
        activeRuleSet = null
    }

    /**
     * tag → 声源解析（§3.5.5 六级链）：AI 角色绑定 > cast_role > 选角模板规则 > 性别兜底 > narrator > 默认
     * 本期（期1）实现：模板规则首命中 → current 哨兵替换 → fallbackSource；L-d AI 链期2 接入角色绑定级
     */
    suspend fun resolveSourceForTag(
        bookKey: String,
        tag: String,
        ruleSet: CastingRuleSet
    ): SpeechRoute? {
        // 模板规则：数组顺序首命中（§3.5.1 优先级语义）
        val rule = ruleSet.rules.firstOrNull { it.tag == tag }
        if (rule != null) {
            val source = substituteCurrentSentinel(rule.source)
            if (source != null) return source
            AppLog.put("选角模板规则声源无效（tag=$tag），回退 fallbackSource")
        }
        // fallbackSource 兜底
        if (ruleSet.fallbackSourceJson.isNotBlank()) {
            val fallback = SpeechRoute.fromJson(ruleSet.fallbackSourceJson)
            if (fallback.engineValue.isNotBlank() || fallback.engineType == SpeechRoute.ENGINE_DEFAULT) {
                return fallback
            }
        }
        // 最终兜底：default 路由（当前引擎默认音）
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
