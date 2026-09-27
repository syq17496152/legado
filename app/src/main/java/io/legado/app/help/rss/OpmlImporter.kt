package io.legado.app.help.rss

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

/**
 * W4 / REQ-19：OPML 导入的**数据落地**（解析与导出在 [OpmlParser] / [OpmlExporter]，本类只管写库）。
 *
 * 落地语义（与「扁平标签」口径一致）：
 * - 订阅**以 `xmlUrl` 为唯一键**去重：不存在 ⇒ 新建源；已存在 ⇒ **只合并分组标签**，
 *   **不覆盖**用户既有规则/名称（避免导入把本机调好的源打回原形）；
 * - 分组标签写入 `sourceGroup`（逗号分隔扁平串，多级已在解析期拼成单标签）。
 */
object OpmlImporter {

    data class Outcome(
        /** 新建的订阅源数 */
        val inserted: Int,
        /** 已存在、仅合并分组的源数 */
        val merged: Int,
        /** 被扁平化的多级分组数（回执展示） */
        val flattenedGroups: Int
    )

    suspend fun import(feeds: List<OpmlFeed>, flattenedGroups: Int): Outcome = withContext(IO) {
        var inserted = 0
        var merged = 0
        feeds.forEach { feed ->
            val group = feed.groupTags.joinToString(",")
            val existing = appDb.rssSourceDao.getByKey(feed.xmlUrl)
            if (existing == null) {
                appDb.rssSourceDao.insert(
                    RssSource(
                        sourceUrl = feed.xmlUrl,
                        sourceName = feed.title,
                        sourceIcon = "",
                        sourceGroup = group.ifBlank { null },
                        enabled = true
                    )
                )
                inserted++
            } else {
                if (group.isNotBlank()) {
                    existing.addGroup(group)
                    appDb.rssSourceDao.update(existing)
                }
                merged++
            }
        }
        // 诊断日志（日志子规范：只记计数，不落源名/URL）
        AppLog.putInfo(
            "OpmlImport: feeds=${feeds.size}, inserted=$inserted, merged=$merged, " +
                "flattenedGroups=$flattenedGroups"
        )
        Outcome(inserted, merged, flattenedGroups)
    }
}