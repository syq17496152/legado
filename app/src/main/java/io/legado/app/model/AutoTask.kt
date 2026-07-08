package io.legado.app.model

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.CacheManager
import io.legado.app.service.AutoTaskService
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefBoolean
import splitties.init.appCtx

/**
 * F-P1-1 自动任务系统核心对象
 * 借鉴自阅读T (skybbk1001/legadoT)
 *
 * 提供任务规则 CRUD、脚本构建、服务调度控制。
 */
object AutoTask {

    const val SOURCE_KEY = "auto_task"
    private const val BOOK_TASK_PREFIX = "book_update:"
    private const val KEY_RULES = "autoTaskRules"

    const val DEFAULT_CRON = "*/30 * * * *"

    fun bookTaskId(bookUrl: String): String {
        return BOOK_TASK_PREFIX + MD5Utils.md5Encode16(bookUrl)
    }

    fun buildBookUpdateScript(
        bookUrl: String,
        notifyEnabled: Boolean = true,
        cacheEnabled: Boolean = false
    ): String {
        val notify = linkedMapOf<String, Any?>(
            "enable" to notifyEnabled,
            "minCount" to 1
        )
        val cache = linkedMapOf<String, Any?>(
            "enable" to cacheEnabled
        )
        val action = linkedMapOf<String, Any?>(
            "type" to "refreshToc",
            "bookUrl" to bookUrl,
            "notify" to notify,
            "cache" to cache
        )
        val root = linkedMapOf<String, Any?>(
            "actions" to listOf(action)
        )
        val json = GSON.toJson(root)
        return "var __autoTask = $json;\n__autoTask"
    }

    fun normalizeScript(script: String): String {
        val trimmed = script.trim()
        return when {
            trimmed.startsWith("@js:", true) -> trimmed.substring(4).trim()
            trimmed.startsWith("<js>", true) && trimmed.contains("</") ->
                trimmed.substring(4, trimmed.lastIndexOf("<")).trim()
            else -> trimmed
        }
    }

    fun start(context: Context) {
        AutoTaskService.start(context)
    }

    fun stop(context: Context) {
        AutoTaskService.stop(context)
    }

    fun refreshSchedule(context: Context = appCtx) {
        if (!context.getPrefBoolean(PreferKey.autoTaskService)) return
        AutoTaskService.refresh(context)
    }

    fun buildSource(task: AutoTaskRule): BookSource {
        return BookSource(
            bookSourceUrl = "${SOURCE_KEY}:${task.id}",
            bookSourceName = task.name
        ).apply {
            jsLib = task.jsLib
            header = task.header
            concurrentRate = task.concurrentRate
            enabledCookieJar = task.enabledCookieJar
            loginUrl = task.loginUrl
            loginUi = task.loginUi
            loginCheckJs = task.loginCheckJs
        }
    }

    @Synchronized
    fun getRules(): MutableList<AutoTaskRule> {
        val rules = appDb.autoTaskRuleDao.all().toMutableList()
        if (rules.isEmpty()) {
            // 尝试从旧 CacheManager 迁移数据
            migrateFromCache()?.let { rules.addAll(it) }
        }
        return rules
    }

    @Synchronized
    fun saveRules(list: List<AutoTaskRule>, refresh: Boolean = true) {
        appDb.autoTaskRuleDao.deleteAll()
        if (list.isNotEmpty()) {
            appDb.autoTaskRuleDao.insert(*list.toTypedArray())
        }
        // 清除旧缓存中的副本
        CacheManager.delete(KEY_RULES)
        if (refresh) {
            refreshSchedule()
        }
    }

    @Synchronized
    fun upsert(rule: AutoTaskRule) {
        val existing = appDb.autoTaskRuleDao.getById(rule.id)
        if (existing != null) {
            appDb.autoTaskRuleDao.update(rule)
        } else {
            appDb.autoTaskRuleDao.insert(rule)
        }
        refreshSchedule()
    }

    @Synchronized
    fun delete(vararg ids: String) {
        ids.forEach { appDb.autoTaskRuleDao.delete(it) }
        refreshSchedule()
    }

    @Synchronized
    fun update(id: String, updater: (AutoTaskRule) -> AutoTaskRule): AutoTaskRule? {
        val existing = appDb.autoTaskRuleDao.getById(id) ?: return null
        val updated = updater(existing)
        appDb.autoTaskRuleDao.update(updated)
        return updated
    }

    /** 迁移旧 CacheManager 中的数据到 Room */
    private fun migrateFromCache(): MutableList<AutoTaskRule>? {
        val json = CacheManager.get(KEY_RULES) ?: return null
        val rules = GSON.fromJsonArray<AutoTaskRule>(json).getOrNull()?.toMutableList()
            ?: return null
        appDb.autoTaskRuleDao.insert(*rules.toTypedArray())
        CacheManager.delete(KEY_RULES)
        return rules
    }
}
