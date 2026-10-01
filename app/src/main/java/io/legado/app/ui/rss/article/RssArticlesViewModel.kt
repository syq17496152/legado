package io.legado.app.ui.rss.article

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.autoNextPageEnabled
import io.legado.app.model.rss.Rss
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.Dispatchers.IO


class RssArticlesViewModel(application: Application) : BaseViewModel(application) {
    val loadFinallyLiveData = MutableLiveData<Boolean>()
    val loadErrorLiveData = MutableLiveData<String>()
    val pageLiveData = MutableLiveData<Int>()
    var isLoading = true
    var order = System.currentTimeMillis()
    /** 阶段8：暴露给 VideoPlay 传递分页上下文（仅读取，不外部修改） **/
    var nextPageUrl: String? = null
    var sortName: String = ""
    var sortUrl: String = ""
    var searchKey: String? = null
    var page = 1

    fun init(bundle: Bundle?) {
        bundle?.let {
            sortName = it.getString("sortName") ?: ""
            sortUrl = it.getString("sortUrl") ?: ""
            searchKey = it.getString("searchKey")
        }
    }

    fun loadArticles(rssSource: RssSource) = loadArticles(rssSource, 1)

    fun loadArticles(rssSource: RssSource, targetPage: Int) {
        isLoading = true
        page = targetPage.coerceAtLeast(1)
        order = System.currentTimeMillis()
        nextPageUrl = null
        pageLiveData.postValue(page)
        Rss.getArticles(viewModelScope, sortName, sortUrl, rssSource, page, searchKey).onSuccess(IO) {
            nextPageUrl = it.second
            val articles = it.first
            articles.forEach { rssArticle ->
                rssArticle.order = order--
            }
            appDb.rssArticleDao.insert(*articles.toTypedArray())
            // 分页源刷新首页时清理旧页数据（含隐式PAGE模式：未填下一页规则但URL含{{page}}）
            if (!rssSource.ruleNextPage.isNullOrEmpty() || rssSource.autoNextPageEnabled(sortUrl)) {
                appDb.rssArticleDao.clearOld(rssSource.sourceUrl, sortName, order)
            }
            val hasMore = articles.isNotEmpty() &&
                    (!rssSource.ruleNextPage.isNullOrEmpty() || rssSource.autoNextPageEnabled(sortUrl))
            loadFinallyLiveData.postValue(hasMore)
            isLoading = false
        }.onError {
            // 失败必须复位在途标记：isLoading 是「有加载在飞」的闸，漏复位会让
            // scrollToBottom() 的首行守卫恒真 ⇒ 页脚重试与触底翻页**永久失效**（既有缺陷）
            isLoading = false
            loadFinallyLiveData.postValue(false)
            AppLog.put("rss获取内容失败", it)
            loadErrorLiveData.postValue(it.stackTraceStr)
        }
    }

    fun loadMore(rssSource: RssSource) {
        isLoading = true
        page++
        val pageUrl = nextPageUrl
        if (pageUrl.isNullOrEmpty()) {
            // 未真正发起请求 ⇒ 回退页码，避免「页码已前进但实页未取」的漂移
            page--
            isLoading = false
            loadFinallyLiveData.postValue(false)
            return
        }
        // 缺陷②修复（2026-10-01 用户报障「右上角页码不跟着动」）：滑动翻页也必须同步页码 chip。
        // 此前仅 loadArticles（首页 / 手动选页）会 postValue ⇒ 滑动翻页时 chip 停在旧页。
        pageLiveData.postValue(page)
        Rss.getArticles(viewModelScope, sortName, pageUrl, rssSource, page, searchKey).onSuccess(IO) {
            nextPageUrl = it.second
            loadMoreSuccess(it.first)
            isLoading = false
        }.onError {
            // 缺陷②修复：失败回退页码（与 VideoPlay.loadMoreArticles 既有正确口径一致）⇒ 重试不跳页
            page--
            pageLiveData.postValue(page)
            isLoading = false
            loadFinallyLiveData.postValue(false)
            AppLog.put("rss获取内容失败", it)
            loadErrorLiveData.postValue(it.stackTraceStr)
        }
    }

    private fun loadMoreSuccess(articles: MutableList<RssArticle>) {
        val hasMore: Boolean
        if (articles.isEmpty()) {
            hasMore = false
        } else {
            val firstArticle = articles.first()
            val lastArticle = articles.last()
            val firstInDb = appDb.rssArticleDao.get(firstArticle.origin, firstArticle.link, firstArticle.sort) != null
            // 注意：末条必须用**自身**的 sort 查库（此前误用 firstArticle.sort）；同页 sort 同名时等价，但语义应自洽
            val lastInDb = appDb.rssArticleDao.get(lastArticle.origin, lastArticle.link, lastArticle.sort) != null
            hasMore = RssLoadMoreOutcome.hasMore(
                articlesEmpty = false,
                firstItemInDb = firstInDb,
                lastItemInDb = lastInDb
            )
            if (hasMore) {
                articles.forEach {
                    it.order = order--
                }
                appDb.rssArticleDao.append(*articles.toTypedArray())
            }
        }
        // 缺陷①修复（2026-10-01 用户报障「最多只能翻两页 / 页脚一直刷新翻不动」）：
        // 三个出口（空结果 / 首末条重复 / **追加成功**）都必须发完成信号。此前「追加成功」分支静默返回
        // ⇒ 宿主在途闸 isLoadingState 永为 true ⇒ scrollToBottom() 首行守卫恒真（后续翻页全被拦）
        // 且 loadMoreView 停在转圈态。本函数**只有这一处 postValue 且无提前 return**，
        // 该不变式由 RssPagingSignalContractTest 以源码契约钉死（防同类漏发信号复发）。
        loadFinallyLiveData.postValue(hasMore)
    }

}