package io.legado.app.help.source

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppLog.Level
import io.legado.app.constant.AppLog.TAG_SOURCE_RECYCLE_BIN
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.SourceRecycleBin
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.config.HighlightRule
import io.legado.app.ui.book.read.config.HighlightRuleStore
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import splitties.init.appCtx
import java.util.concurrent.TimeUnit

object SourceRecycleBinHelp {

    const val TYPE_BOOK_SOURCE = "book_source"
    const val TYPE_RSS_SOURCE = "rss_source"
    const val TYPE_REPLACE_RULE = "replace_rule"
    const val TYPE_TXT_TOC_RULE = "txt_toc_rule"
    const val TYPE_HTTP_TTS = "http_tts"
    const val TYPE_DICT_RULE = "dict_rule"
    const val TYPE_HIGHLIGHT_RULE = "highlight_rule"
    private const val RETENTION_DAYS = 7L

    fun recycleBookSources(bookSources: List<BookSource>, now: Long = System.currentTimeMillis()) {
        recycle(bookSources, now) {
            SourceRecycleBin(
                type = TYPE_BOOK_SOURCE,
                key = it.bookSourceUrl,
                name = it.bookSourceName,
                groupName = it.bookSourceGroup,
                payload = GSON.toJson(it)
            )
        }
    }

    fun recycleRssSources(rssSources: List<RssSource>, now: Long = System.currentTimeMillis()) {
        recycle(rssSources, now) {
            SourceRecycleBin(
                type = TYPE_RSS_SOURCE,
                key = it.sourceUrl,
                name = it.sourceName,
                groupName = it.sourceGroup,
                payload = GSON.toJson(it)
            )
        }
    }

    fun recycleReplaceRules(replaceRules: List<ReplaceRule>, now: Long = System.currentTimeMillis()) {
        recycle(replaceRules, now) {
            SourceRecycleBin(
                type = TYPE_REPLACE_RULE,
                key = it.id.toString(),
                name = it.name,
                groupName = it.group,
                payload = GSON.toJson(it)
            )
        }
    }

    fun recycleTxtTocRules(txtTocRules: List<TxtTocRule>, now: Long = System.currentTimeMillis()) {
        recycle(txtTocRules, now) {
            SourceRecycleBin(
                type = TYPE_TXT_TOC_RULE,
                key = it.id.toString(),
                name = it.name,
                payload = GSON.toJson(it)
            )
        }
    }

    fun recycleHttpTtsRules(httpTtsRules: List<HttpTTS>, now: Long = System.currentTimeMillis()) {
        recycle(httpTtsRules, now) {
            SourceRecycleBin(
                type = TYPE_HTTP_TTS,
                key = it.id.toString(),
                name = it.name,
                payload = GSON.toJson(it)
            )
        }
    }

    fun recycleDictRules(dictRules: List<DictRule>, now: Long = System.currentTimeMillis()) {
        recycle(dictRules, now) {
            SourceRecycleBin(
                type = TYPE_DICT_RULE,
                key = it.name,
                name = it.name,
                payload = GSON.toJson(it)
            )
        }
    }

    fun recycleHighlightRules(highlightRules: List<HighlightRule>, now: Long = System.currentTimeMillis()) {
        recycle(highlightRules, now) {
            SourceRecycleBin(
                type = TYPE_HIGHLIGHT_RULE,
                key = it.id,
                name = it.name.ifBlank { it.pattern },
                groupName = it.group,
                payload = GSON.toJson(it)
            )
        }
    }

    private fun <T> recycle(
        rules: List<T>,
        now: Long,
        toRecycleBin: (T) -> SourceRecycleBin
    ) {
        if (!AppConfig.sourceRecycleBinEnabled) return
        cleanupExpired(now)
        if (rules.isEmpty()) return
        val expireAt = now + TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        val items = rules.map {
            toRecycleBin(it).apply {
                deletedAt = now
                this.expireAt = expireAt
            }
        }
        appDb.sourceRecycleBinDao.insert(*items.toTypedArray())
        AppLog.putDebugWithTag(
            TAG_SOURCE_RECYCLE_BIN,
            "回收规则入库: type=${items.firstOrNull()?.type} count=${items.size} 保留至=$expireAt",
            level = Level.INFO
        )
    }

    fun restore(item: SourceRecycleBin, overwrite: Boolean) {
        cleanupExpired()
        var restored = true
        when (item.type) {
            TYPE_BOOK_SOURCE -> {
                val source = GSON.fromJsonObject<BookSource>(item.payload).getOrNull()
                    ?: return logRestoreFailed(item, "payload 解析失败")
                if (!overwrite && appDb.bookSourceDao.has(source.bookSourceUrl))
                    return logRestoreFailed(item, "目标已存在")
                appDb.bookSourceDao.insert(source)
            }
            TYPE_RSS_SOURCE -> {
                val source = GSON.fromJsonObject<RssSource>(item.payload).getOrNull()
                    ?: return logRestoreFailed(item, "payload 解析失败")
                if (!overwrite && appDb.rssSourceDao.has(source.sourceUrl))
                    return logRestoreFailed(item, "目标已存在")
                appDb.rssSourceDao.insert(source)
            }
            TYPE_REPLACE_RULE -> {
                val rule = GSON.fromJsonObject<ReplaceRule>(item.payload).getOrNull()
                    ?: return logRestoreFailed(item, "payload 解析失败")
                if (!overwrite && appDb.replaceRuleDao.findById(rule.id) != null)
                    return logRestoreFailed(item, "目标已存在")
                appDb.replaceRuleDao.insert(rule)
            }
            TYPE_TXT_TOC_RULE -> {
                val rule = GSON.fromJsonObject<TxtTocRule>(item.payload).getOrNull()
                    ?: return logRestoreFailed(item, "payload 解析失败")
                if (!overwrite && appDb.txtTocRuleDao.get(rule.id) != null)
                    return logRestoreFailed(item, "目标已存在")
                appDb.txtTocRuleDao.insert(rule)
            }
            TYPE_HTTP_TTS -> {
                val rule = GSON.fromJsonObject<HttpTTS>(item.payload).getOrNull()
                    ?: return logRestoreFailed(item, "payload 解析失败")
                if (!overwrite && appDb.httpTTSDao.get(rule.id) != null)
                    return logRestoreFailed(item, "目标已存在")
                appDb.httpTTSDao.insert(rule)
            }
            TYPE_DICT_RULE -> {
                val rule = GSON.fromJsonObject<DictRule>(item.payload).getOrNull()
                    ?: return logRestoreFailed(item, "payload 解析失败")
                if (!overwrite && appDb.dictRuleDao.getByName(rule.name) != null)
                    return logRestoreFailed(item, "目标已存在")
                appDb.dictRuleDao.insert(rule)
            }
            TYPE_HIGHLIGHT_RULE -> {
                val rule = GSON.fromJsonObject<HighlightRule>(item.payload).getOrNull()
                    ?: return logRestoreFailed(item, "payload 解析失败")
                val rules = HighlightRuleStore.load(appCtx)
                val index = rules.indexOfFirst { it.id == rule.id }
                if (index >= 0) {
                    if (!overwrite) return logRestoreFailed(item, "目标已存在")
                    rules[index] = rule
                } else {
                    rules.add(rule)
                }
                HighlightRuleStore.save(appCtx, rules)
            }
            else -> {
                restored = false
                AppLog.putDebugWithTag(
                    TAG_SOURCE_RECYCLE_BIN,
                    "恢复失败: type=${item.type} key=${item.key} 原因=未知类型",
                    level = Level.WARN
                )
            }
        }
        if (restored) {
            appDb.sourceRecycleBinDao.delete(item)
            AppLog.putDebugWithTag(
                TAG_SOURCE_RECYCLE_BIN,
                "恢复规则成功: type=${item.type} key=${item.key} name=${item.name} overwrite=$overwrite",
                level = Level.INFO
            )
        }
    }

    private fun logRestoreFailed(item: SourceRecycleBin, reason: String) {
        AppLog.putDebugWithTag(
            TAG_SOURCE_RECYCLE_BIN,
            "恢复冲突/失败: type=${item.type} key=${item.key} name=${item.name} 原因=$reason",
            level = Level.WARN
        )
    }

    fun hasConflict(item: SourceRecycleBin): Boolean {
        return when (item.type) {
            TYPE_BOOK_SOURCE -> appDb.bookSourceDao.has(item.key)
            TYPE_RSS_SOURCE -> appDb.rssSourceDao.has(item.key)
            TYPE_REPLACE_RULE -> item.key.toLongOrNull()?.let { appDb.replaceRuleDao.findById(it) != null } == true
            TYPE_TXT_TOC_RULE -> item.key.toLongOrNull()?.let { appDb.txtTocRuleDao.get(it) != null } == true
            TYPE_HTTP_TTS -> item.key.toLongOrNull()?.let { appDb.httpTTSDao.get(it) != null } == true
            TYPE_DICT_RULE -> appDb.dictRuleDao.getByName(item.key) != null
            TYPE_HIGHLIGHT_RULE -> HighlightRuleStore.load(appCtx).any { it.id == item.key }
            else -> false
        }
    }

    fun cleanupExpired(now: Long = System.currentTimeMillis()) {
        val count = appDb.sourceRecycleBinDao.deleteExpired(now)
        if (count > 0) {
            AppLog.putDebugWithTag(
                TAG_SOURCE_RECYCLE_BIN,
                "清理过期回收项: count=$count",
                level = Level.INFO
            )
        }
    }
}
