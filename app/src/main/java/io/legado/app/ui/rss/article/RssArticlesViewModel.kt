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
import io.legado.app.model.rss.Rss
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Compose 侧聚合分页态（design-b3-d4-flagship §3.1）。
 * LiveData 保留一条过渡期共存，B5 收官拆除。
 */
data class RssArticlesUiState(
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val page: Int = 1,
    val error: String? = null,
)

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

    private val _uiState = MutableStateFlow(RssArticlesUiState())
    val uiState: StateFlow<RssArticlesUiState> = _uiState.asStateFlow()

    /**
     * Room Flow 直通（design §3.1）：flowByOriginSort 不 select image（CursorWindow 2MB 红线，R1），
     * 200ms 防抖对齐原 collect 后 delay 语义（design §3.2）。
     * origin 由宿主在 init 后经 [bindOrigin] 绑定（modern 嵌入=sortHostViewModel.url / classic pager=RssSortViewModel.url）。
     */
    @OptIn(FlowPreview::class)
    var articlesFlow: Flow<List<RssArticle>> = MutableStateFlow(emptyList())
        private set

    private var boundOrigin: String? = null

    /** 绑定 origin 并构建列表流（幂等：同 origin 重复调用不重建） */
    @OptIn(FlowPreview::class)
    fun bindOrigin(origin: String?) {
        if (origin == null || origin == boundOrigin) return
        boundOrigin = origin
        articlesFlow = appDb.rssArticleDao.flowByOriginSort(origin, sortName)
            .debounce(200)
            .flowOn(IO)
    }

    fun init(bundle: Bundle?) {
        bundle?.let {
            sortName = it.getString("sortName") ?: ""
            sortUrl = it.getString("sortUrl") ?: ""
            searchKey = it.getString("searchKey")
        }
    }

    /** classic HorizontalPager 页 Compose 接线用（无 arguments Bundle 场景，design §5.4 注意①） */
    fun configure(sortName: String, sortUrl: String, searchKey: String?) {
        this.sortName = sortName
        this.sortUrl = sortUrl
        this.searchKey = searchKey
    }

    fun loadArticles(rssSource: RssSource) = loadArticles(rssSource, 1)

    fun loadArticles(rssSource: RssSource, targetPage: Int) {
        isLoading = true
        page = targetPage.coerceAtLeast(1)
        order = System.currentTimeMillis()
        nextPageUrl = null
        pageLiveData.postValue(page)
        _uiState.update {
            it.copy(isRefreshing = true, isLoadingMore = false, page = page, error = null)
        }
        Rss.getArticles(viewModelScope, sortName, sortUrl, rssSource, page, searchKey).onSuccess(IO) {
            nextPageUrl = it.second
            val articles = it.first
            articles.forEach { rssArticle ->
                rssArticle.order = order--
            }
            appDb.rssArticleDao.insert(*articles.toTypedArray())
            if (!rssSource.ruleNextPage.isNullOrEmpty()) {
                appDb.rssArticleDao.clearOld(rssSource.sourceUrl, sortName, order)
            }
            val hasMore = articles.isNotEmpty() && !rssSource.ruleNextPage.isNullOrEmpty()
            loadFinallyLiveData.postValue(hasMore)
            _uiState.update { state -> state.copy(isRefreshing = false, hasMore = hasMore) }
            isLoading = false
        }.onError {
            loadFinallyLiveData.postValue(false)
            AppLog.put("rss获取内容失败", it)
            loadErrorLiveData.postValue(it.stackTraceStr)
            _uiState.update { state ->
                state.copy(isRefreshing = false, hasMore = false, error = it.stackTraceStr)
            }
        }
    }

    fun loadMore(rssSource: RssSource) {
        isLoading = true
        page++
        val pageUrl = nextPageUrl
        _uiState.update { it.copy(isLoadingMore = true, page = page) }
        if (pageUrl.isNullOrEmpty()) {
            loadFinallyLiveData.postValue(false)
            // 原实现终止后 isLoading 保持 true（hasMore=false 已封锁全部重入路径）；
            // Compose 态机要求终态一致，此处同步复位
            isLoading = false
            _uiState.update { it.copy(isLoadingMore = false, hasMore = false) }
            return
        }
        Rss.getArticles(viewModelScope, sortName, pageUrl, rssSource, page, searchKey).onSuccess(IO) {
            nextPageUrl = it.second
            loadMoreSuccess(it.first)
            isLoading = false
            _uiState.update { state -> state.copy(isLoadingMore = false) }
        }.onError {
            loadFinallyLiveData.postValue(false)
            AppLog.put("rss获取内容失败", it)
            loadErrorLiveData.postValue(it.stackTraceStr)
            _uiState.update { state ->
                state.copy(isLoadingMore = false, hasMore = false, error = it.stackTraceStr)
            }
        }
    }

    private fun loadMoreSuccess(articles: MutableList<RssArticle>) {
        if (articles.isEmpty()) {
            loadFinallyLiveData.postValue(false)
            _uiState.update { it.copy(isLoadingMore = false, hasMore = false) }
            return
        }
        val firstArticle = articles.first()
        val dbFirstArticle = appDb.rssArticleDao.get(firstArticle.origin, firstArticle.link, firstArticle.sort)
        val lastArticle = articles.last()
        val dbLastArticle = appDb.rssArticleDao.get(lastArticle.origin, lastArticle.link, firstArticle.sort)
        if (dbFirstArticle != null && dbLastArticle != null) {
            loadFinallyLiveData.postValue(false)
            _uiState.update { it.copy(isLoadingMore = false, hasMore = false) }
        } else {
            articles.forEach {
                it.order = order--
            }
            appDb.rssArticleDao.append(*articles.toTypedArray())
            _uiState.update { it.copy(isLoadingMore = false) }
        }
    }

}
