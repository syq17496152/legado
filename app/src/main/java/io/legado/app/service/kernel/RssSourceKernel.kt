package io.legado.app.service.kernel

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.help.rss.OpmlExporter
import io.legado.app.help.rss.OpmlImporter
import io.legado.app.help.rss.OpmlParseResult
import io.legado.app.help.rss.OpmlParser
import io.legado.app.help.rss.RssReadRecordMarker
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.DimState
import io.legado.app.model.SourceQualityChecker
import io.legado.app.model.rss.Rss
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 订阅源域业务内核（web-mcp-productization 一期 · 2.3.1 / REQ-1-201 · REQ-1-202）。
 *
 * 契约同 [BookSourceKernel]；删源同样委派 [SourceHelp.deleteRssSources]（内部已接入回收站）。
 */
object RssSourceKernel {

    suspend fun sources(): List<RssSource> = withContext(IO) { appDb.rssSourceDao.all }

    suspend fun source(url: String): RssSource? =
        withContext(IO) { appDb.rssSourceDao.getByKey(url) }

    suspend fun saveSource(source: RssSource) {
        withContext(IO) { appDb.rssSourceDao.insert(source) }
    }

    /** 批量保存有效源，返回实际写入的源。 */
    suspend fun saveSources(sources: List<RssSource>): List<RssSource> {
        val saved = ArrayList<RssSource>(sources.size)
        sources.forEach { source ->
            if (hasValidIdentity(source)) {
                saveSource(source)
                saved.add(source)
            }
        }
        return saved
    }

    suspend fun deleteSources(sources: List<RssSource>) {
        withContext(IO) { SourceHelp.deleteRssSources(sources) }
    }

    /** 源身份是否完整（口径同 [BookSourceKernel.hasValidIdentity]）。 */
    internal fun hasValidIdentity(source: RssSource): Boolean =
        source.sourceName.isNotBlank() && source.sourceUrl.isNotBlank()

    // ============================================================ 导入校验链（2.3.1 / REQ-1-311）

    /** 批量导入前的校验结果。 */
    data class ValidatedSources(
        val accepted: List<RssSource>,
        val skipped: List<SkippedSource>,
    )

    /** 被跳过的源（含白话原因）。 */
    data class SkippedSource(val name: String, val url: String, val reason: String)

    /**
     * 解析 + 校验（**与书源同口径**，REQ-1-311）。
     *
     * 与书源侧的差异仅在解析件：订阅源无增量解析器 ⇒ 用 GSON 数组解析
     * （`fromJsonArray`，与原 Controller 相同路径）；**校验件与判据完全一致**
     * （[SourceQualityChecker.l1StaticCheckRssSource] + `deterministicFail`，L1 0 网络成本）。
     *
     * @throws Exception 解析失败 —— 由门面转 "转换源失败" / "格式不对"
     */
    suspend fun parseAndValidate(json: String): ValidatedSources {
        val parsed = GSON.fromJsonArray<RssSource>(json).getOrThrow()
        val accepted = ArrayList<RssSource>(parsed.size)
        val skipped = ArrayList<SkippedSource>()
        parsed.forEach { source ->
            val reason = skipReason(source)
            if (reason == null) {
                accepted.add(source)
            } else {
                skipped.add(SkippedSource(source.sourceName, source.sourceUrl, reason))
            }
        }
        return ValidatedSources(accepted, skipped)
    }

    /** 单条保存前的校验口径（与批量完全一致）。 */
    fun skipReason(source: RssSource): String? {
        if (!hasValidIdentity(source)) return "源名称和URL不能为空"
        val report = SourceQualityChecker.l1StaticCheckRssSource(source)
        if (!report.deterministicFail) return null
        return report.dimensions.values.firstOrNull { it.state == DimState.FAIL }?.evidence
            ?: "规则残缺"
    }

    // ============================================================ 管理（二期 tasks 2.12）

    /** 分组名列表（订阅源分组同样是源上的逗号串，DAO 负责拆分去重）。 */
    suspend fun groups(): List<String> = withContext(IO) { appDb.rssSourceDao.allGroups() }

    /** 分组重命名（`newName` 为空 ⇒ 删除该分组标记）。口径同 [BookSourceKernel.renameGroup]。 */
    suspend fun renameGroup(oldName: String, newName: String?): Int = withContext(IO) {
        val targets = appDb.rssSourceDao.getByGroup(oldName)
        var affected = 0
        targets.forEach { source ->
            val parts = source.sourceGroup.orEmpty()
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val renamed = parts.map { if (it == oldName) newName.orEmpty() else it }
                .filter { it.isNotEmpty() }
                .distinct()
            val joined = renamed.joinToString(",")
            if (joined != source.sourceGroup.orEmpty()) {
                source.sourceGroup = joined
                appDb.rssSourceDao.update(source)
                affected++
            }
        }
        affected
    }

    /** 批量启用 / 停用订阅源。 */
    suspend fun setEnabled(urls: List<String>, enabled: Boolean): Int = withContext(IO) {
        urls.count { url ->
            if (appDb.rssSourceDao.getByKey(url) == null) {
                false
            } else {
                appDb.rssSourceDao.enable(url, enabled)
                true
            }
        }
    }

    /**
     * 调整订阅源排序。
     *
     * `customOrder` 单值精确设置（DAO `upOrder(sourceUrl, customOrder)`）；
     * 传入 `urls` 顺序时按列表下标整体重排（同 `upOrder(sources)` 语义）。
     */
    suspend fun setSort(url: String?, order: Int?, urls: List<String>): Map<String, Any?> = withContext(IO) {
        when {
            !url.isNullOrBlank() && order != null -> {
                appDb.rssSourceDao.upOrder(url, order)
                mapOf("updated" to 1)
            }

            urls.isNotEmpty() -> {
                val sources = urls.mapNotNull { appDb.rssSourceDao.getByKey(it) }
                sources.forEachIndexed { index, source -> source.customOrder = index }
                appDb.rssSourceDao.upOrder(sources)
                mapOf("updated" to sources.size)
            }

            else -> throw IllegalArgumentException("需要 url+order 或 urls 之一")
        }
    }

    /** 批量导入订阅源（复用 [parseAndValidate] 同一校验口径）。 */
    suspend fun importJson(json: String): Map<String, Any?> {
        val validated = parseAndValidate(json)
        val saved = saveSources(validated.accepted)
        return mapOf(
            "imported" to saved.size,
            "skipped" to validated.skipped.size,
            "skippedDetail" to validated.skipped.take(20),
        )
    }

    /** 导出订阅源 JSON 串（为空 ⇒ 全部启用源）。 */
    suspend fun exportJson(urls: List<String>): String = withContext(IO) {
        val list = if (urls.isEmpty()) {
            appDb.rssSourceDao.allEnabled
        } else {
            urls.mapNotNull { appDb.rssSourceDao.getByKey(it) }
        }
        GSON.toJson(list)
    }

    // ============================================================ 文章（二期 tasks 2.12）

    /** 某订阅源某分组下的文章列表。 */
    suspend fun articles(origin: String, sort: String, limit: Int, keyword: String?): List<RssArticle> =
        withContext(IO) {
            val list = appDb.rssArticleDao.getListByOriginSort(origin, sort)
            val filtered = if (keyword.isNullOrBlank()) {
                list
            } else {
                list.filter {
                    it.title.contains(keyword, true) || it.description.orEmpty().contains(keyword, true)
                }
            }
            if (limit > 0) filtered.take(limit) else filtered
        }

    /**
     * 读取文章正文（复用 App 阅读 RSS 的同一链 [Rss.getContentAwait]）。
     *
     * 传 `origin + link + sort` 定位文章；正文按源规则抓取并做净化。
     */
    suspend fun articleContent(origin: String, link: String, sort: String): Map<String, Any?> {
        val article = withContext(IO) {
            if (sort.isBlank()) {
                appDb.rssArticleDao.getByLink(origin, link)
            } else {
                appDb.rssArticleDao.get(origin, link, sort)
            }
        } ?: throw NoSuchElementException("文章不存在：$origin/$link")
        val source = withContext(IO) { appDb.rssSourceDao.getByKey(origin) }
            ?: throw NoSuchElementException("订阅源不存在：$origin")
        val content = Rss.getContentAwait(
            rssArticle = article,
            ruleContent = source.ruleContent.orEmpty(),
            rssSource = source,
        )
        return mapOf(
            "title" to article.title,
            "link" to article.link,
            "pubDate" to article.pubDate,
            "content" to content,
        )
    }

    /**
     * 标记文章已读 / 未读。
     *
     * 说明（口径来源）：项目**已读状态由 `rssReadRecords` 承担**（`RssArticle.read` 列无写入方，
     * 见 `RssArticleDao` 注释），故"已读"走 [RssReadRecordMarker.markRead]（按 origin 整源标记，
     * 与 App「全部标记已读」同一条链）；"未读"删除对应已读记录。
     */
    suspend fun markRead(origins: List<String>, read: Boolean): Map<String, Any?> = withContext(IO) {
        if (read) {
            mapOf("read" to true, "affected" to RssReadRecordMarker.markRead(origins))
        } else {
            origins.forEach { appDb.rssReadRecordDao.deleteRecordsByOrigin(it) }
            mapOf("read" to false, "affected" to origins.size)
        }
    }

    // ============================================================ OPML / 收藏夹 / 搜索

    /** 导出 OPML（复用 [OpmlExporter.export]）。 */
    suspend fun opmlExport(urls: List<String>): Map<String, Any?> {
        val sources = withContext(IO) {
            if (urls.isEmpty()) appDb.rssSourceDao.allEnabled
            else urls.mapNotNull { appDb.rssSourceDao.getByKey(it) }
        }
        return mapOf("count" to sources.size, "opml" to OpmlExporter.export(sources))
    }

    /** 导入 OPML（解析 → [OpmlImporter.import] 落库）。 */
    suspend fun opmlImport(text: String): Map<String, Any?> {
        val parsed = OpmlParser.parseText(text)
        val feeds = when (parsed) {
            is OpmlParseResult.Ok -> parsed.feeds
            is OpmlParseResult.Error -> throw IllegalArgumentException("OPML 解析失败：${parsed.reason}")
        }
        val flattened = feeds.count { it.groupTags.size > 1 }
        val outcome = OpmlImporter.import(feeds, flattened)
        return mapOf(
            "feeds" to feeds.size,
            "inserted" to outcome.inserted,
            "merged" to outcome.merged,
            "flattenedGroups" to outcome.flattenedGroups,
        )
    }

    /** 收藏夹列表（`group` 为空 ⇒ 全部收藏）。 */
    suspend fun favorites(group: String?): List<RssStar> = withContext(IO) {
        if (group.isNullOrBlank()) {
            appDb.rssStarDao.all
        } else {
            appDb.rssStarDao.flowByGroup(group).first()
        }
    }

    /** 收藏 / 取消收藏文章。 */
    suspend fun favorite(origin: String, link: String, star: Boolean, group: String?): Map<String, Any?> =
        withContext(IO) {
            if (star) {
                val exist = appDb.rssStarDao.get(origin, link)
                if (exist != null) {
                    group?.let { exist.group = it }
                    appDb.rssStarDao.update(exist)
                } else {
                    val article = appDb.rssArticleDao.getByLink(origin, link)
                    val target = article?.toStar() ?: RssStar(origin = origin, link = link)
                    target.starTime = System.currentTimeMillis()
                    group?.let { target.group = it }
                    appDb.rssStarDao.insert(target)
                }
                mapOf("starred" to true, "origin" to origin, "link" to link)
            } else {
                appDb.rssStarDao.delete(origin, link)
                mapOf("starred" to false, "origin" to origin, "link" to link)
            }
        }

    /** 删除收藏夹条目（按 origin+link 或按分组）。 */
    suspend fun deleteFavorite(origin: String?, link: String?, group: String?): Int = withContext(IO) {
        when {
            group != null -> appDb.rssStarDao.deleteByGroup(group).let { appDb.rssStarDao.all.size }
            !origin.isNullOrBlank() && !link.isNullOrBlank() -> {
                appDb.rssStarDao.delete(origin, link)
                1
            }

            else -> throw IllegalArgumentException("需要 group 或 origin+link")
        }
    }

    /**
     * RSS 跨源搜索（逐源调 `Rss.getArticlesAwait(sortName="搜索", sortUrl=source.searchUrl, key=…)`）。
     *
     * 与 `RssSearchModel` **同一调用形态**与同一超时（30s/源）；这里改为**顺序执行**并逐源落 `AppLog`，
     * 结果按源聚合返回（避免 MCP 侧引入并行度与并发上限问题）。
     */
    suspend fun search(key: String, sourceUrls: List<String>, maxSources: Int, perSourceLimit: Int): List<Map<String, Any?>> {
        if (key.isBlank()) throw IllegalArgumentException("搜索关键词不能为空")
        val sources = withContext(IO) {
            val all = if (sourceUrls.isEmpty()) appDb.rssSourceDao.allEnabled
            else sourceUrls.mapNotNull { appDb.rssSourceDao.getByKey(it) }
            all.filter { !it.searchUrl.isNullOrBlank() }.take(maxSources.coerceIn(1, 64))
        }
        val result = ArrayList<Map<String, Any?>>()
        sources.forEach { source ->
            val items = kotlin.runCatching {
                withTimeout(30_000L) {
                    Rss.getArticlesAwait(
                        sortName = "搜索",
                        sortUrl = source.searchUrl!!,
                        rssSource = source,
                        page = 1,
                        key = key,
                    ).first
                }
            }.onFailure {
                AppLog.put("MCP RSS 搜索失败（sourceHash=${source.sourceUrl.hashCode()}）：${it.localizedMessage}", it)
            }.getOrNull().orEmpty()
            val limited = if (perSourceLimit > 0) items.take(perSourceLimit) else items
            limited.forEach { article ->
                result.add(
                    mapOf(
                        "origin" to article.origin,
                        "originName" to source.sourceName,
                        "title" to article.title,
                        "link" to article.link,
                        "sort" to article.sort,
                        "pubDate" to article.pubDate,
                        "description" to article.description,
                    )
                )
            }
        }
        return result
    }

    /** 文章详情（含多源信息：同 link 在各订阅源的归属，供换源选择）。 */
    suspend fun articleInfo(origin: String, link: String): Map<String, Any?> = withContext(IO) {
        val article = appDb.rssArticleDao.getByLink(origin, link)
            ?: throw NoSuchElementException("文章不存在：$origin/$link")
        mapOf(
            "origin" to article.origin,
            "link" to article.link,
            "sort" to article.sort,
            "title" to article.title,
            "pubDate" to article.pubDate,
            "description" to article.description,
            "image" to article.image,
            "group" to article.group,
            "type" to article.type,
            "read" to appDb.rssReadRecordDao.getRecords().any { it.record == link && it.origin == origin },
            "starred" to (appDb.rssStarDao.get(origin, link) != null),
        )
    }
}
