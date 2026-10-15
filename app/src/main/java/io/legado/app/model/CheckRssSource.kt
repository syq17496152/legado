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

    // 校验设置
    var timeout = CacheManager.getLong("checkRssSourceTimeout") ?: 180000L
    var checkDomain = CacheManager.get("checkRssDomain")?.toBoolean() ?: false
    // 域名校验方式：0=Socket快速检测, 1=AnalyzeUrl真实请求(默认)
    var domainCheckMode = CacheManager.getInt("checkRssDomainCheckMode") ?: 1
    var checkArticles = CacheManager.get("checkRssArticles")?.toBoolean() ?: true
    var checkSearch = CacheManager.get("checkRssSearch")?.toBoolean() ?: true
    var checkSort = CacheManager.get("checkRssSort")?.toBoolean() ?: true
    var checkContent = CacheManager.get("checkRssContent")?.toBoolean() ?: true
    // 去重设置（NEW-28 接线时改为默认开启：该去重标注此前因整条链路不可达而从未生效，
    // 无存量用户受影响；默认开启才能让「按真实域名+type 标记重复源」这一订阅源侧独有收益真正落地）
    var enableDedup = CacheManager.get("checkRssEnableDedup")?.toBoolean() ?: true
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

    /**
     * 单源保存入口（UI 与测试共用）：一次性写入全部配置项并落盘。
     *
     * 为什么收口：8 个配置项的「字段 ↔ 持久化键」映射原先只在 [putConfig] 内，UI 若各自赋值再调
     * putConfig，任何一处漏赋值都会**静默沿用旧值**（用户感知＝「设置不生效」，编译期无提示）。
     * 收口后「赋值 = 落盘」不可分离，且可被 JVM 用例直接断言键值往返。
     */
    fun applyConfig(
        timeoutMs: Long,
        checkDomain: Boolean,
        domainCheckMode: Int,
        checkArticles: Boolean,
        checkSearch: Boolean,
        checkSort: Boolean,
        checkContent: Boolean,
        enableDedup: Boolean
    ) {
        this.timeout = timeoutMs
        this.checkDomain = checkDomain
        this.domainCheckMode = domainCheckMode
        this.checkArticles = checkArticles
        this.checkSearch = checkSearch
        this.checkSort = checkSort
        this.checkContent = checkContent
        this.enableDedup = enableDedup
        putConfig()
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

