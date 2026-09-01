package io.legado.app.model.rss

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssEpisode
import io.legado.app.data.entities.RssSource
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.StrResponse
import io.legado.app.help.http.warmUpConnection
import io.legado.app.help.source.SourceLastHostHelper
import io.legado.app.help.source.SourceNetworkClient
import io.legado.app.help.source.SourcePreconnectHelper
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.CoroutineContext

@Suppress("MemberVisibilityCanBePrivate")
object Rss {

    fun getArticles(
        scope: CoroutineScope,
        sortName: String,
        sortUrl: String,
        rssSource: RssSource,
        page: Int,
        key: String? = null,
        context: CoroutineContext = Dispatchers.IO
    ): Coroutine<Pair<MutableList<RssArticle>, String?>> {
        return Coroutine.async(scope, context) {
            getArticlesAwait(sortName, sortUrl, rssSource, page, key)
        }
    }

    suspend fun getArticlesAwait(
        sortName: String,
        sortUrl: String,
        rssSource: RssSource,
        page: Int,
        key: String? = null
    ): Pair<MutableList<RssArticle>, String?> {
        AppLog.putDebugWithTag(AppLog.TAG_RSS, "开始获取RSS文章 sourceHash=${rssSource.sourceUrl.hashCode()} page=$page sortUrlLen=${sortUrl.length}", level = AppLog.Level.INFO)
        val ruleData = RuleData()
        val analyzeUrl = AnalyzeUrl(
            sortUrl,
            page = page,
            key = key,
            baseUrl = rssSource.sourceUrl,
            source = rssSource,
            ruleData = ruleData,
            coroutineContext = currentCoroutineContext(),
            hasLoginHeader = false
        )
        // M6 SourceNetworkClient 统一网络请求 + 登录检测 + 重定向检测 + lastHost 回填
        val res = SourceNetworkClient.requestWithLoginCheck(
            analyzeUrl = analyzeUrl,
            source = rssSource,
            checkJs = rssSource.loginCheckJs
        )
        Debug.log(rssSource.sourceUrl, "≡获取成功:${analyzeUrl.ruleUrl}")
        val articles = RssParserByRule.parseXML(sortName, sortUrl, res.url, res.body, rssSource, ruleData)
        // M4 SourcePreconnectHelper 统一预连接（F-P1-F 机制：列表加载后预连接前3篇）
        SourcePreconnectHelper.preconnectTopN(articles.first.mapNotNull { it.link }, 3)
        AppLog.putDebugWithTag(AppLog.TAG_RSS, "获取RSS文章成功 sourceHash=${rssSource.sourceUrl.hashCode()} 文章数=${articles.first.size}", level = AppLog.Level.INFO)
        return articles
    }

    fun getContent(
        scope: CoroutineScope,
        rssArticle: RssArticle,
        ruleContent: String,
        rssSource: RssSource,
        context: CoroutineContext = Dispatchers.IO
    ): Coroutine<String> {
        return Coroutine.async(scope, context) {
            getContentAwait(rssArticle, ruleContent, rssSource)
        }
    }

    suspend fun getContentAwait(
        rssArticle: RssArticle,
        ruleContent: String,
        rssSource: RssSource,
    ): String {
        // 多线路多集按需采集分支：仅 type=2 视频源 且 ruleRoutes/ruleEpisodes 非空时走新模式
        if (rssSource.type == 2
            && !rssSource.ruleRoutes.isNullOrBlank()
            && !rssSource.ruleEpisodes.isNullOrBlank()
        ) {
            AppLog.putDebugWithTag(AppLog.TAG_RSS, "进入多线路多集按需采集模式 sourceHash=${rssSource.sourceUrl.hashCode()}", level = AppLog.Level.INFO)
            return getRoutesContentAwait(rssArticle, rssSource.ruleRoutes!!, rssSource.ruleEpisodes!!, rssSource)
        }
        AppLog.putDebugWithTag(AppLog.TAG_RSS, "开始获取RSS内容 sourceHash=${rssSource.sourceUrl.hashCode()} articleHash=${rssArticle.link.hashCode()} linkLen=${rssArticle.link.length}", level = AppLog.Level.INFO)
        val analyzeUrl = AnalyzeUrl(
            rssArticle.link,
            baseUrl = rssArticle.origin,
            source = rssSource,
            ruleData = rssArticle,
            coroutineContext = currentCoroutineContext(),
            hasLoginHeader = false
        )
        // M6 SourceNetworkClient 统一网络请求 + 登录检测 + 重定向检测 + lastHost 回填
        val res = SourceNetworkClient.requestWithLoginCheck(
            analyzeUrl = analyzeUrl,
            source = rssSource,
            checkJs = rssSource.loginCheckJs
        )
        Debug.log(rssSource.sourceUrl, "≡获取成功:${rssSource.sourceUrl}")
        Debug.log(rssSource.sourceUrl, res.body ?: "", state = 20)
        val analyzeRule = AnalyzeRule(rssArticle, rssSource)
        analyzeRule.setContent(res.body)
            .setBaseUrl(NetworkUtils.getAbsoluteURL(rssArticle.origin, rssArticle.link))
            .setCoroutineContext(currentCoroutineContext())
            .setRedirectUrl(res.url)
        val content = analyzeRule.getString(ruleContent)
        AppLog.putDebugWithTag(AppLog.TAG_CONTENT, "RSS内容解析完成 sourceHash=${rssSource.sourceUrl.hashCode()} articleHash=${rssArticle.link.hashCode()} contentLen=${content.length}", level = AppLog.Level.INFO)
        return content
    }

    /**
     * 检测重定向
     */
    private fun checkRedirect(rssSource: RssSource, response: StrResponse) {
        response.raw.priorResponse?.let {
            if (it.isRedirect) {
                Debug.log(rssSource.sourceUrl, "≡检测到重定向(${it.code})")
                Debug.log(rssSource.sourceUrl, "┌重定向后地址")
                Debug.log(rssSource.sourceUrl, "└${response.url}")
            }
        }
    }

    /**
     * 多线路多集按需采集：采集线路列表 + 第一线路集数，返回嵌套JSON字符串
     * 其他线路集数在用户切换线路时由 VideoPlay.switchToRoute 调用 getEpisodesAwait 按需采集
     *
     * @param rssArticle 文章
     * @param ruleRoutes 多线路规则（CSS/JSONPath/XPath/JS）
     * @param ruleEpisodes 多集规则（CSS/JSONPath/XPath/JS，支持{routeIndex}/{routeIndex+1}占位符）
     * @param rssSource 订阅源
     * @return 嵌套JSON字符串 [{"name":"线路1","episodes":[{"title":"第1集","url":"..."}]},...]
     */
    suspend fun getRoutesContentAwait(
        rssArticle: RssArticle,
        ruleRoutes: String,
        ruleEpisodes: String,
        rssSource: RssSource
    ): String {
        AppLog.putDebugWithTag(AppLog.TAG_RSS, "多线路多集采集开始 sourceHash=${rssSource.sourceUrl.hashCode()}", level = AppLog.Level.INFO)
        val analyzeUrl = AnalyzeUrl(
            rssArticle.link,
            baseUrl = rssArticle.origin,
            source = rssSource,
            ruleData = rssArticle,
            coroutineContext = currentCoroutineContext(),
            hasLoginHeader = false
        )
        SourceLastHostHelper.fillBack(rssSource, analyzeUrl)
        val res = kotlin.runCatching {
            analyzeUrl.getStrResponseAwait()
        }.getOrElse { throw it }
        checkRedirect(rssSource, res)
        val analyzeRule = AnalyzeRule(rssArticle, rssSource)
        analyzeRule.setContent(normalizeMacCmsBody(res.body))
            .setBaseUrl(NetworkUtils.getAbsoluteURL(rssArticle.origin, rssArticle.link))
            .setCoroutineContext(currentCoroutineContext())
            .setRedirectUrl(res.url)
        // 采集线路名列表（列表范式优先：$.routes[*].name；结果逐项按 \n 展开以兼容旧写法 replaceRegex 转行产物；空则回落单字符串+\n 分割）
        val routeNames = analyzeRule.getStringList(ruleRoutes)
            ?.flatMap { it.split("\n") }
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: (analyzeRule.getString(ruleRoutes) ?: "")
                .split("\n").map { it.trim() }.filter { it.isNotBlank() }
        if (routeNames.isEmpty()) {
            AppLog.putDebugWithTag(AppLog.TAG_RSS, "ruleRoutes未匹配到线路 sourceHash=${rssSource.sourceUrl.hashCode()}", level = AppLog.Level.WARN)
            return ""
        }
        // 采集第一线路集数（routeIndex=0）
        val firstRouteEpisodes = getEpisodesListByIndex(analyzeRule, ruleEpisodes, 0, rssArticle)
        // 构造嵌套JSON返回
        val routeJson = JSONArray()
        // 第一线路含集数
        val firstRoute = JSONObject().apply {
            put("name", routeNames.getOrNull(0) ?: "线路1")
            put("episodes", JSONArray(firstRouteEpisodes.map { ep ->
                JSONObject().apply {
                    put("title", ep.title)
                    put("url", ep.url)
                }
            }))
        }
        routeJson.put(firstRoute)
        // 其他线路只含名称，集数在切换时按需采集
        for (i in 1 until routeNames.size) {
            routeJson.put(JSONObject().apply {
                put("name", routeNames[i])
                put("episodes", JSONArray())
            })
        }
        AppLog.putDebugWithTag(AppLog.TAG_RSS, "多线路采集完成 routeCount=${routeNames.size} firstRouteEpisodes=${firstRouteEpisodes.size}", level = AppLog.Level.INFO)
        return routeJson.toString()
    }

    /**
     * 按线路索引采集集数列表（占位符预处理）
     * CSS/JSONPath/XPath/JS 统一用 {routeIndex} 和 {routeIndex+1} 占位符
     *
     * @param analyzeRule 已setContent的AnalyzeRule实例
     * @param ruleEpisodes 多集规则（支持{routeIndex}/{routeIndex+1}占位符）
     * @param routeIndex 线路索引（0-based）
     * @param rssArticle 文章（用于URL补全）
     * @return 集数列表
     */
    suspend fun getEpisodesListByIndex(
        analyzeRule: AnalyzeRule,
        ruleEpisodes: String,
        routeIndex: Int,
        rssArticle: RssArticle
    ): List<RssEpisode> {
        // 占位符预处理（CSS/JSONPath/XPath/JS 统一处理）
        val processedRule = ruleEpisodes
            .replace("{routeIndex+1}", (routeIndex + 1).toString())
            .replace("{routeIndex}", routeIndex.toString())
        // 执行规则
        val result = analyzeRule.getString(processedRule)
        // 解析为 List<RssEpisode>（透传 routeIndex 供兜底隐式分组）
        val episodes = parseEpisodesResult(result, rssArticle, routeIndex)
        return episodes
    }

    /**
     * 解析集数规则执行结果为 List<RssEpisode>
     * 支持：嵌套JSON数组 [{"title":"第1集","url":"..."}] / 纯URL列表 / MacCMS 多线路串（兜底隐式分组）
     */
    private fun parseEpisodesResult(result: String, rssArticle: RssArticle, routeIndex: Int = 0): List<RssEpisode> {
        if (result.isBlank()) return emptyList()
        val trimmed = result.trim()
        // 尝试解析为JSON数组
        if (trimmed.startsWith("[")) {
            return try {
                val arr = JSONArray(trimmed)
                (0 until arr.length()).map { i ->
                    val item = arr.optJSONObject(i)
                    if (item != null) {
                        // 嵌套JSON格式 [{"title":"...","url":"..."}]
                        RssEpisode(
                            title = item.optString("title", "第${i + 1}集"),
                            url = NetworkUtils.getAbsoluteURL(rssArticle.origin, item.optString("url"))
                        )
                    } else {
                        // 纯URL列表 ["url1","url2"]
                        RssEpisode(
                            title = "第${i + 1}集",
                            url = NetworkUtils.getAbsoluteURL(rssArticle.origin, arr.optString(i))
                        )
                    }
                }.filter { it.url.isNotBlank() }
            } catch (e: Exception) {
                AppLog.putDebugWithTag(AppLog.TAG_RSS, "集数JSON解析失败,尝试多行URL解析", e)
                parseEpisodesByLines(trimmed, rssArticle)
            }
        }
        // 兜底：规范化层未触发时，含 $$$ 的多线路串隐式按线路分组取第 N 组（越界回落首组）
        if (trimmed.contains("\$\$\$")) {
            val groups = trimmed.split("\$\$\$")
            val group = groups.getOrNull(routeIndex) ?: run {
                AppLog.putDebugWithTag(
                    AppLog.TAG_RSS,
                    "routeIndex=$routeIndex 超出线路数(${groups.size})，回落首线路",
                    level = AppLog.Level.WARN
                )
                groups[0]
            }
            return parseEpisodesByLines(group, rssArticle)
        }
        // 多行URL格式
        return parseEpisodesByLines(trimmed, rssArticle)
    }

    /**
     * 多行URL格式解析为集数列表
     * 增强：行内含 $ 时按 MacCMS 段解析——先按 # 分集、再按 $(limit=2) 拆集名/地址（缺名补"第N集"）
     * 旧格式（整行即 URL）保持兼容
     */
    private fun parseEpisodesByLines(text: String, rssArticle: RssArticle): List<RssEpisode> {
        val pairs = text.split("\n", "\r")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .flatMap { line ->
                if (line.contains('$')) {
                    // CMS 段：集名$URL#集名$URL（limit=2 保留地址内 $ 字符）
                    line.split('#')
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .map { piece ->
                            val parts = piece.split('$', limit = 2)
                            (parts.getOrNull(0)?.trim().orEmpty()) to
                                (parts.getOrNull(1)?.trim() ?: parts[0].trim())
                        }
                } else {
                    // 旧格式兼容：整行即 URL，行为不变（回归安全）
                    listOf("" to line)
                }
            }
        var index = 0
        return pairs.mapNotNull { (title, url) ->
            if (url.isBlank()) return@mapNotNull null
            index++
            RssEpisode(
                title = title.ifBlank { "第${index}集" },
                url = NetworkUtils.getAbsoluteURL(rssArticle.origin, url)
            )
        }
    }

    /**
     * 切换线路时按需采集集数（供 VideoPlay.switchToRoute 调用）
     * 内部请求详情页 + 执行 ruleEpisodes
     *
     * @param rssArticle 文章
     * @param ruleEpisodes 多集规则
     * @param routeIndex 线路索引
     * @param rssSource 订阅源
     * @return 集数列表
     */
    suspend fun getEpisodesAwait(
        rssArticle: RssArticle,
        ruleEpisodes: String,
        routeIndex: Int,
        rssSource: RssSource
    ): List<RssEpisode> {
        val analyzeUrl = AnalyzeUrl(
            rssArticle.link,
            baseUrl = rssArticle.origin,
            source = rssSource,
            ruleData = rssArticle,
            coroutineContext = currentCoroutineContext(),
            hasLoginHeader = false
        )
        val res = kotlin.runCatching {
            analyzeUrl.getStrResponseAwait()
        }.getOrElse { throw it }
        val analyzeRule = AnalyzeRule(rssArticle, rssSource)
        analyzeRule.setContent(normalizeMacCmsBody(res.body))
            .setBaseUrl(NetworkUtils.getAbsoluteURL(rssArticle.origin, rssArticle.link))
            .setCoroutineContext(currentCoroutineContext())
            .setRedirectUrl(res.url)
        return getEpisodesListByIndex(analyzeRule, ruleEpisodes, routeIndex, rssArticle)
    }

    /**
     * MacCMS 扁平播放数据规范化：vod_play_from / vod_play_url 含 $$$ 时，
     * 在原 JSON 增量注入结构化 routes 字段（原字段不动），供列表范式规则随意消费。
     * 非 JSON body / 无 MacCMS 特征字段 / 已有 routes 时原样返回（零侵入）。
     */
    private fun normalizeMacCmsBody(body: String?): String? {
        if (body.isNullOrBlank()) return body
        val json = kotlin.runCatching { JSONObject(body) }.getOrNull() ?: return body
        val item = json.optJSONArray("list")?.optJSONObject(0) ?: json
        if (item == null || item.has("routes")) return body
        val from = item.optString("vod_play_from")
        val urls = item.optString("vod_play_url")
        if (!from.contains("\$\$\$") && !urls.contains("\$\$\$")) return body
        return kotlin.runCatching {
            val names = from.split("\$\$\$")
            val groups = urls.split("\$\$\$")
            val routes = JSONArray()
            names.forEachIndexed { i, name ->
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
                        }
                    }
                routes.put(JSONObject().put("name", name.trim()).put("episodes", eps))
            }
            // 注入到顶层（$.routes），与列表范式规则 $.routes[*].name 对齐；item(list[0]) 不重复注入
            json.put("routes", routes)
            AppLog.putDebugWithTag(
                AppLog.TAG_RSS,
                "MacCMS规范化完成 routeCount=${routes.length()} 首线路集数=${routes.optJSONObject(0)?.optJSONArray("episodes")?.length() ?: 0}",
                level = AppLog.Level.INFO
            )
            json.toString()
        }.getOrElse { body }
    }
}