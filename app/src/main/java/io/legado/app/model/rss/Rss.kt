package io.legado.app.model.rss

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.StrResponse
import io.legado.app.help.http.warmUpConnection
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
        val checkJs = rssSource.loginCheckJs
        val res = kotlin.runCatching {
            analyzeUrl.getStrResponseAwait().let {
                if (!checkJs.isNullOrBlank()) { //检测源是否已登录
                    analyzeUrl.evalJS(checkJs, it) as StrResponse
                } else {
                    it
                }
            }
        }.getOrElse { throwable ->
            if (!checkJs.isNullOrBlank()) {
                val errResponse = analyzeUrl.getErrStrResponse(throwable)
                try {
                    (analyzeUrl.evalJS(checkJs, errResponse) as StrResponse).also {
                        if (it.code() == 500) {
                            throw throwable
                        }
                    }
                } catch (_: Throwable) {
                    throw throwable
                }
            } else {
                throw throwable
            }
        }
        checkRedirect(rssSource, res)
        Debug.log(rssSource.sourceUrl, "≡获取成功:${analyzeUrl.ruleUrl}")
        val articles = RssParserByRule.parseXML(sortName, sortUrl, res.url, res.body, rssSource, ruleData)
        // F-P1-F 预连接：列表加载完成后，对前 3 篇文章域名发起 HEAD 预连接，点击时减少 300-1000ms
        // coroutineScope{}.async{}.awaitAll() 并行执行，kotlin.runCatching 捕获异常，失败不影响列表显示
        kotlin.runCatching {
            kotlinx.coroutines.coroutineScope {
                articles.first.take(3).mapIndexed { index, article ->
                    async(Dispatchers.IO) {
                        if (!article.link.isNullOrBlank()) {
                            AppLog.put("Rss 预连接: 第${index + 1}篇")
                            warmUpConnection(article.link)
                        }
                    }
                }.awaitAll()
            }
        }
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
        val analyzeUrl = AnalyzeUrl(
            rssArticle.link,
            baseUrl = rssArticle.origin,
            source = rssSource,
            ruleData = rssArticle,
            coroutineContext = currentCoroutineContext(),
            hasLoginHeader = false
        )
        val checkJs = rssSource.loginCheckJs
        val res = kotlin.runCatching {
            analyzeUrl.getStrResponseAwait().let {
                if (!checkJs.isNullOrBlank()) { //检测源是否已登录
                    analyzeUrl.evalJS(checkJs, it) as StrResponse
                } else {
                    it
                }
            }
        }.getOrElse { throwable ->
            if (!checkJs.isNullOrBlank()) {
                val errResponse = analyzeUrl.getErrStrResponse(throwable)
                try {
                    (analyzeUrl.evalJS(checkJs, errResponse) as StrResponse).also {
                        if (it.code() == 500) {
                            throw throwable
                        }
                    }
                } catch (_: Throwable) {
                    throw throwable
                }
            } else {
                throw throwable
            }
        }
        checkRedirect(rssSource, res)
        Debug.log(rssSource.sourceUrl, "≡获取成功:${rssSource.sourceUrl}")
        Debug.log(rssSource.sourceUrl, res.body ?: "", state = 20)
        val analyzeRule = AnalyzeRule(rssArticle, rssSource)
        analyzeRule.setContent(res.body)
            .setBaseUrl(NetworkUtils.getAbsoluteURL(rssArticle.origin, rssArticle.link))
            .setCoroutineContext(currentCoroutineContext())
            .setRedirectUrl(res.url)
        return analyzeRule.getString(ruleContent)
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
}