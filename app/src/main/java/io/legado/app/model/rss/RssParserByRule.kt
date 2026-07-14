package io.legado.app.model.rss

import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setRuleData
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import splitties.init.appCtx
import java.util.Locale

@Keep
object RssParserByRule {

    @Throws(Exception::class)
    suspend fun parseXML(
        sortName: String,
        sortUrl: String,
        redirectUrl: String,
        body: String?,
        rssSource: RssSource,
        ruleData: RuleData
    ): Pair<MutableList<RssArticle>, String?> {
        val sourceUrl = rssSource.sourceUrl
        var nextUrl: String? = null
        if (body.isNullOrBlank()) {
            throw NoStackTraceException(
                appCtx.getString(R.string.error_get_web_content, rssSource.sourceUrl)
            )
        }
        Debug.log(sourceUrl, body, state = 10)
        var ruleArticles = rssSource.ruleArticles
        if (ruleArticles.isNullOrBlank()) {
            Debug.log(sourceUrl, "⇒列表规则为空, 使用默认规则解析")
            return RssParserDefault.parseXML(sortName, body, sourceUrl)
        } else {
            // 循环外的 analyzeRule 用于获取列表集合/下一页/规则拆分（串行执行，结果不可变，可安全共享）
            val analyzeRule = AnalyzeRule(ruleData, rssSource)
            analyzeRule.setCoroutineContext(currentCoroutineContext())
            analyzeRule.setContent(body).setBaseUrl(sortUrl)
            analyzeRule.setRedirectUrl(redirectUrl)
            var reverse = false
            if (ruleArticles.startsWith("-")) {
                reverse = true
                ruleArticles = ruleArticles.substring(1)
            }
            Debug.log(sourceUrl, "┌获取列表")
            val collections = analyzeRule.getElements(ruleArticles)
            Debug.log(sourceUrl, "└列表大小:${collections.size}")
            if (!rssSource.ruleNextPage.isNullOrEmpty()) {
                Debug.log(sourceUrl, "┌获取下一页链接")
                if (rssSource.ruleNextPage!!.uppercase(Locale.getDefault()) == "PAGE") {
                    nextUrl = sortUrl
                } else {
                    nextUrl = analyzeRule.getString(rssSource.ruleNextPage)
                    if (nextUrl.isNotEmpty()) {
                        nextUrl = NetworkUtils.getAbsoluteURL(sortUrl, nextUrl)
                    }
                }
                Debug.log(sourceUrl, "└$nextUrl")
            }
            val ruleTitle = analyzeRule.splitSourceRule(rssSource.ruleTitle)
            val rulePubDate = analyzeRule.splitSourceRule(rssSource.rulePubDate)
            val ruleDescription = analyzeRule.splitSourceRule(rssSource.ruleDescription)
            val ruleImage = analyzeRule.splitSourceRule(rssSource.ruleImage)
            val ruleLink = analyzeRule.splitSourceRule(rssSource.ruleLink)
            val variable = ruleData.getVariable()
            // P1-1 并行化：for 循环改 async{}.awaitAll() + Semaphore(6) 限流
            // 🔴 硬性前提1：每item独立 AnalyzeRule 实例（getItem内setRuleData/setContent修改实例状态，复用会并发数据错乱；AnalyzeRule evalJSCallCount++/topScopeRef/scriptCache非线程安全）
            // 🔴 硬性前提2：articleList不在并行块内add（mutableListOf非线程安全），awaitAll后批量收集
            // 顺序保证：mapIndexed+awaitAll保持发起顺序，filterNotNull保持顺序，与原for循环结果一致
            Debug.log(sourceUrl, "┌并行解析列表项(共${collections.size}项,限流6)")
            val parseSemaphore = Semaphore(6)
            val articleList = coroutineScope {
                collections.mapIndexed { index, item ->
                    async(Dispatchers.IO) {
                        parseSemaphore.withPermit {
                            // 硬性前提1：独立 AnalyzeRule 实例
                            val itemRule = AnalyzeRule(ruleData, rssSource)
                            itemRule.setCoroutineContext(currentCoroutineContext())
                            itemRule.setBaseUrl(sortUrl)
                            itemRule.setRedirectUrl(redirectUrl)
                            try {
                                getItem(
                                    sourceUrl, item, itemRule, variable, rssSource.type, index == 0,
                                    ruleTitle, rulePubDate, ruleDescription, ruleImage, ruleLink
                                )
                            } catch (e: Exception) {
                                AppLog.put("RSS列表项解析失败 index=$index", e)
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull().toMutableList()
            }
            // 硬性前提2：统一设置 sort 和 origin（原在for循环内逐项设置，现批量设置）
            articleList.forEach {
                it.sort = sortName
                it.origin = sourceUrl
            }
            Debug.log(sourceUrl, "└并行解析完成(成功${articleList.size}/${collections.size}项)")
            if (reverse) {
                articleList.reverse()
            }
            return Pair(articleList, nextUrl)
        }
    }

    private fun getItem(
        sourceUrl: String,
        item: Any,
        analyzeRule: AnalyzeRule,
        variable: String?,
        type: Int,
        log: Boolean,
        ruleTitle: List<AnalyzeRule.SourceRule>,
        rulePubDate: List<AnalyzeRule.SourceRule>,
        ruleDescription: List<AnalyzeRule.SourceRule>,
        ruleImage: List<AnalyzeRule.SourceRule>,
        ruleLink: List<AnalyzeRule.SourceRule>
    ): RssArticle? {
        val rssArticle = RssArticle(variable = variable)
        analyzeRule.setRuleData(rssArticle)
        analyzeRule.setContent(item)
        Debug.log(sourceUrl, "┌获取标题", log)
        rssArticle.title = analyzeRule.getString(ruleTitle)
        Debug.log(sourceUrl, "└${rssArticle.title}", log)
        Debug.log(sourceUrl, "┌获取时间", log)
        rssArticle.pubDate = analyzeRule.getString(rulePubDate)
        Debug.log(sourceUrl, "└${rssArticle.pubDate}", log)
        Debug.log(sourceUrl, "┌获取描述", log)
        if (ruleDescription.isEmpty()) {
            rssArticle.description = null
            Debug.log(sourceUrl, "└描述规则为空，将会解析内容页", log)
        } else {
            rssArticle.description = analyzeRule.getString(ruleDescription)
            Debug.log(sourceUrl, "└${rssArticle.description}", log)
        }
        Debug.log(sourceUrl, "┌获取图片url", log)
        try {
            analyzeRule.getString(ruleImage).let {
                if (it.isNotEmpty()) {
                    rssArticle.image = NetworkUtils.getAbsoluteURL(sourceUrl, it)
                }
            }
            Debug.log(sourceUrl, "└${rssArticle.image ?: ""}", log)
        } catch (e: Exception) {
            Debug.log(sourceUrl, "└${e.localizedMessage}", log)
        }
        Debug.log(sourceUrl, "┌获取文章链接", log)
        rssArticle.link = analyzeRule.getString(ruleLink, isUrl = true)
        Debug.log(sourceUrl, "└${rssArticle.link}", log)
        rssArticle.type = type
        if (rssArticle.title.isBlank()) {
            return null
        }
        return rssArticle
    }
}