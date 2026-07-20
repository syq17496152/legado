package io.legado.app.ui.rss.search

import android.app.Application
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.entities.SearchRssArticle
import io.legado.app.help.config.AppConfig
import io.legado.app.model.rss.RssSearchModel
import io.legado.app.model.rss.RssSearchScope
import io.legado.app.utils.ConflateLiveData
import io.legado.app.utils.toastOnUi

/**
 * 订阅源统一搜索 ViewModel（rss-unified-search 新增）
 *
 * 参考 [io.legado.app.ui.book.search.SearchViewModel] 的设计：
 * - 持有 [RssSearchModel] 实例
 * - [searchRssLiveData] 防抖 1000ms（与书源搜索一致）
 * - [isSearchLiveData] / [searchFinishLiveData] 搜索状态 LiveData
 *
 * 与 SearchViewModel 的差异（遗漏点 34 修复）：
 * - 删除 upAdapterLiveData（订阅源搜索无书架概念，AD-11 已删除书架搜索区域）
 * - saveSearchKey/clearHistory/deleteHistory 显式传 type=1（订阅源搜索历史）
 */
class RssSearchViewModel(application: Application) : BaseViewModel(application) {

    var searchRssLiveData = ConflateLiveData<List<SearchRssArticle>>(1000)
    val searchScope: RssSearchScope = RssSearchScope(AppConfig.rssSearchScope)
    var searchFinishLiveData = MutableLiveData<Boolean>()
    var isSearchLiveData = MutableLiveData<Boolean>()
    var searchKey: String = ""
    private var searchID = 0L

    private val searchModel = RssSearchModel(viewModelScope, object : RssSearchModel.CallBack {

        override fun getSearchScope(): RssSearchScope {
            return searchScope
        }

        override fun onSearchStart() {
            isSearchLiveData.postValue(true)
        }

        override fun onSearchSuccess(articles: List<SearchRssArticle>) {
            searchRssLiveData.postValue(articles)
        }

        override fun onSearchFinish(isEmpty: Boolean) {
            isSearchLiveData.postValue(false)
            searchFinishLiveData.postValue(isEmpty)
        }

        override fun onSearchCancel(exception: Throwable?) {
            isSearchLiveData.postValue(false)
            exception?.let {
                context.toastOnUi(it.localizedMessage)
            }
        }

    })

    /**
     * 开始搜索
     */
    fun search(key: String) {
        execute {
            if ((searchKey == key) || key.isNotEmpty()) {
                searchModel.cancelSearch()
                searchID = System.currentTimeMillis()
                searchRssLiveData.postValue(emptyList())
                searchKey = key
            }
            if (searchKey.isEmpty()) {
                return@execute
            }
            searchModel.search(searchID, searchKey)
        }
    }

    /**
     * 停止搜索
     */
    fun stop() {
        searchModel.cancelSearch()
    }

    fun pause() {
        searchModel.pause()
    }

    fun resume() {
        searchModel.resume()
    }

    /**
     * 保存搜索关键字（订阅源搜索历史，type=1）
     */
    fun saveSearchKey(key: String) {
        execute {
            appDb.searchKeywordDao.get(key, 1)?.let {
                it.usage += 1
                it.lastUseTime = System.currentTimeMillis()
                appDb.searchKeywordDao.update(it)
            } ?: appDb.searchKeywordDao.insert(SearchKeyword(word = key, usage = 1, type = 1))
        }
    }

    /**
     * 清空订阅源搜索关键字（仅 type=1，不影响书源搜索历史 type=0）
     */
    fun clearHistory() {
        execute {
            appDb.searchKeywordDao.deleteAll(1)
        }
    }

    fun deleteHistory(searchKeyword: SearchKeyword) {
        execute {
            appDb.searchKeywordDao.delete(searchKeyword)
        }
    }

    override fun onCleared() {
        super.onCleared()
        searchModel.close()
    }

}
