package io.legado.app.help.video

import io.legado.app.constant.AppLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * MacCMS 扁平播放数据规范化（video-booksource-multiroute AD-05 共享层）
 *
 * vod_play_from / vod_play_url 含 $$$ 时，在原 JSON 增量注入双结构（原字段不动）：
 * - routes:   [{name, episodes:[{title,url}]}]  权威源，与订阅源列表范式对齐（$.routes[*]）
 * - chapters: [{title,url,isVolume}...]         派生扁平卷章，专供书源目录范式消费
 *             （卷行 {title:线路名, url:"", isVolume:true}，章行 {title:集名, url, isVolume:false}）
 *
 * 非 JSON body / 无 MacCMS 特征字段 / 已有 routes|chapters 时原样返回（零侵入）。
 * 冲突检测对称：顶层与 list[0] item 均检查。
 */
object MacCmsNormalizer {

    const val KEY_ROUTES = "routes"
    const val KEY_CHAPTERS = "chapters"

    fun normalize(body: String?): String? {
        if (body.isNullOrBlank()) return body
        val json = kotlin.runCatching { JSONObject(body) }.getOrNull() ?: return body
        val item = json.optJSONArray("list")?.optJSONObject(0) ?: json
        // 对称冲突检测：注入目标在顶层，item 若已有同名键同样视为已规范化
        if (item == null
            || json.has(KEY_ROUTES) || json.has(KEY_CHAPTERS)
            || item.has(KEY_ROUTES) || item.has(KEY_CHAPTERS)
        ) {
            return body
        }
        val from = item.optString("vod_play_from")
        val urls = item.optString("vod_play_url")
        if (!from.contains("\$\$\$") && !urls.contains("\$\$\$")) return body
        return kotlin.runCatching {
            val names = from.split("\$\$\$")
            val groups = urls.split("\$\$\$")
            val routes = JSONArray()
            val chapters = JSONArray()
            names.forEachIndexed { i, name ->
                val routeName = name.trim()
                // 卷行（线路）：url 留空，卷不参与播放
                chapters.put(
                    JSONObject()
                        .put("title", routeName)
                        .put("url", "")
                        .put("isVolume", true)
                )
                val eps = JSONArray()
                groups.getOrNull(i)?.split('#')
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.forEach { piece ->
                        val parts = piece.split('$', limit = 2)
                        val title = parts.getOrNull(0)?.trim().orEmpty()
                        val url = (parts.getOrNull(1) ?: parts.getOrNull(0))?.trim().orEmpty()
                        if (url.isNotBlank()) {
                            eps.put(JSONObject().put("title", title).put("url", url))
                            // 章行（集数）：isVolume=false
                            chapters.put(
                                JSONObject()
                                    .put("title", title)
                                    .put("url", url)
                                    .put("isVolume", false)
                            )
                        }
                    }
                routes.put(JSONObject().put("name", routeName).put("episodes", eps))
            }
            // 注入到顶层（$.routes / $.chapters），与列表范式规则 $.routes[*]/$.chapters[*] 对齐
            json.put(KEY_ROUTES, routes)
            json.put(KEY_CHAPTERS, chapters)
            AppLog.putDebugWithTag(
                AppLog.TAG_RSS,
                "MacCMS规范化完成 routeCount=${routes.length()} chapterCount=${chapters.length()}",
                level = AppLog.Level.INFO
            )
            json.toString()
        }.getOrElse { body }
    }
}
