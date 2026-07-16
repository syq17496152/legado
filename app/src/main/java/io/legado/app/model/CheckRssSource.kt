package io.legado.app.model

import android.content.Context
import io.legado.app.R
import io.legado.app.constant.IntentAction
import io.legado.app.data.entities.RssSource
import io.legado.app.help.CacheManager
import io.legado.app.help.IntentData
import io.legado.app.service.CheckRssSourceService
import io.legado.app.utils.startService
import splitties.init.appCtx

/**
 * 订阅源校验配置（参考 [CheckSource]）
 */
object CheckRssSource {
    var keyword = "我的"

    // 校验设置
    var timeout = CacheManager.getLong("checkRssSourceTimeout") ?: 180000L
    var checkDomain = CacheManager.get("checkRssDomain")?.toBoolean() ?: false
    // 域名校验方式：0=Socket快速检测, 1=AnalyzeUrl真实请求(默认)
    var domainCheckMode = CacheManager.getInt("checkRssDomainCheckMode") ?: 1
    var checkArticles = CacheManager.get("checkRssArticles")?.toBoolean() ?: true
    var checkSearch = CacheManager.get("checkRssSearch")?.toBoolean() ?: true
    var checkSort = CacheManager.get("checkRssSort")?.toBoolean() ?: true
    var checkContent = CacheManager.get("checkRssContent")?.toBoolean() ?: true
    // 去重设置（默认关闭）
    var enableDedup = CacheManager.get("checkRssEnableDedup")?.toBoolean() ?: false
    val summary get() = upSummary()

    fun start(context: Context, sources: List<RssSource>) {
        val selectedIds = sources.map { it.sourceUrl }
        IntentData.put("checkRssSourceSelectedIds", selectedIds)
        context.startService<CheckRssSourceService> {
            action = IntentAction.start
        }
    }

    fun stop(context: Context) {
        context.startService<CheckRssSourceService> {
            action = IntentAction.stop
        }
    }

    fun resume(context: Context) {
        context.startService<CheckRssSourceService> {
            action = IntentAction.resume
        }
    }

    fun putConfig() {
        CacheManager.put("checkRssSourceTimeout", timeout)
        CacheManager.put("checkRssDomain", checkDomain)
        CacheManager.put("checkRssDomainCheckMode", domainCheckMode)
        CacheManager.put("checkRssArticles", checkArticles)
        CacheManager.put("checkRssSearch", checkSearch)
        CacheManager.put("checkRssSort", checkSort)
        CacheManager.put("checkRssContent", checkContent)
        CacheManager.put("checkRssEnableDedup", enableDedup)
    }

    private fun upSummary(): String {
        var checkItem = ""
        if (checkDomain) checkItem = "$checkItem ${appCtx.getString(R.string.domain)}"
        if (checkArticles) checkItem = "$checkItem ${appCtx.getString(R.string.check_rss_articles)}"
        if (checkSearch) checkItem = "$checkItem ${appCtx.getString(R.string.search)}"
        if (checkSort) checkItem = "$checkItem ${appCtx.getString(R.string.check_rss_sort)}"
        if (checkContent) checkItem = "$checkItem ${appCtx.getString(R.string.main_body)}"
        val dedupText = if (enableDedup) appCtx.getString(R.string.dedup_enabled)
        else appCtx.getString(R.string.dedup_disabled)
        return appCtx.getString(
            R.string.check_rss_source_config_summary,
            (timeout / 1000).toString(),
            checkItem,
            dedupText
        )
    }
}

