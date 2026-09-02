package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.video.MacCmsNormalizer
import org.json.JSONObject

/**
 * 视频书源目录助手（video-booksource-multiroute）
 *
 * L0 零规则分支：MacCMS 响应经 [MacCmsNormalizer] 注入 routes 结构后，
 * 直接产出"卷=线路（isVolume）、章=集数"的 BookChapter 列表，源侧零规则成本。
 *
 * 产出的卷行 url 为空（由调用方按引擎惯例以 title+index 替代，
 * 与 BookChapterList 既有行为一致），卷不参与正文采集。
 * 章行 url 即集数视频地址（直链或播放页 URL，播放时直链直出/嗅探兜底）。
 */
object VideoBookChapterHelper {

    /**
     * 从 MacCMS 规范化 body（含 $.routes）直产卷章列表
     *
     * @param normalizedBody 经 MacCmsNormalizer.normalize 的 body（含 routes 键）
     * @param book 书籍（bookUrl 用于章节归属）
     * @param baseUrl 目录页地址（章节 baseUrl）
     * @return 卷章列表（卷=线路 isVolume=true，章=集数），无 routes 结构时返回 null（交回通用解析）
     */
    fun buildFromMacCms(normalizedBody: String, book: Book, baseUrl: String): List<BookChapter>? {
        val json = kotlin.runCatching { JSONObject(normalizedBody) }.getOrNull() ?: return null
        val routes = json.optJSONArray(MacCmsNormalizer.KEY_ROUTES) ?: return null
        if (routes.length() == 0) return null
        val chapters = ArrayList<BookChapter>()
        var index = 0
        for (r in 0 until routes.length()) {
            val route = routes.optJSONObject(r) ?: continue
            val routeName = route.optString("name").trim().ifBlank { "线路${r + 1}" }
            // 卷行（线路）
            chapters.add(
                BookChapter(
                    bookUrl = book.bookUrl,
                    title = routeName,
                    url = "",
                    index = index++,
                    isVolume = true,
                    baseUrl = baseUrl
                ).apply {
                    // 卷行 url 置为 title+index，与 BookChapterList 既有卷 url 兜底惯例一致
                    url = "$title${index - 1}"
                }
            )
            // 章行（集数）
            val episodes = route.optJSONArray("episodes")
            for (e in 0 until (episodes?.length() ?: 0)) {
                val ep = episodes?.optJSONObject(e) ?: continue
                val title = ep.optString("title").trim()
                val url = ep.optString("url").trim()
                if (url.isBlank()) continue
                chapters.add(
                    BookChapter(
                        bookUrl = book.bookUrl,
                        title = title,
                        url = url,
                        index = index++,
                        isVolume = false,
                        baseUrl = baseUrl
                    )
                )
            }
        }
        AppLog.putDebugWithTag(
            AppLog.TAG_WEB_BOOK,
            "VideoBookChapterHelper: 卷章直产完成 卷数=${chapters.count { it.isVolume }} 总章数=${chapters.size}",
            level = AppLog.Level.INFO
        )
        return chapters.ifEmpty { null }
    }
}
