package io.legado.app.ui.rss.article

import android.app.Application
import android.content.Intent
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.removeSortCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


class RssSortViewModel(application: Application) : BaseViewModel(application) {
    var url: String? = null
    var sortUrl: String? = null
    var rssSource: RssSource? = null
    var order = System.currentTimeMillis()
    val articleStyle get() = rssSource?.articleStyle
    var searchKey: String? = null
    var sourceName: String? = null

    /** classic Compose 宿主消费（design-b3-d4-flagship §5.2）：initData 读源 + switchLayout 成功回调更新 */
    private val _articleStyleFlow = MutableStateFlow(0)
    val articleStyleFlow: StateFlow<Int> = _articleStyleFlow.asStateFlow()

    fun initData(intent: Intent, finally: () -> Unit) {
        execute {
            url = intent.getStringExtra("sourceUrl")
            url?.let { url ->
                rssSource = appDb.rssSourceDao.getByKey(url)
                rssSource?.let {
                    sourceName = it.sourceName
                    _articleStyleFlow.value = it.articleStyle
                } ?: let {
                    rssSource = RssSource(sourceUrl = url)
                    _articleStyleFlow.value = 0
                }
            }
            sortUrl = intent.getStringExtra("sortUrl") ?: sortUrl
            searchKey = intent.getStringExtra("key")
        }.onFinally {
            finally()
        }
    }

    fun switchLayout() {
        rssSource?.let { source ->
            if (source.articleStyle < 4) {
                source.articleStyle += 1
            } else {
                source.articleStyle = 0
            }
            execute {
                appDb.rssSourceDao.update(source)
            }.onSuccess {
                _articleStyleFlow.value = source.articleStyle
            }
        }
    }

    fun clearArticles() {
        execute {
            url?.let {
                appDb.rssArticleDao.delete(it)
            }
            order = System.currentTimeMillis()
        }.onSuccess {

        }
    }

    fun clearSortCache(onFinally: () -> Unit) {
        execute {
            rssSource?.removeSortCache()
        }.onFinally {
            onFinally.invoke()
        }
    }

    fun getRecords(origin: String? = null): List<RssReadRecord> {
        origin?.let {
            return appDb.rssReadRecordDao.getRecordsByOrigin(it)
        }
        return appDb.rssReadRecordDao.getRecords()
    }

    fun countRecords(origin: String? = null) : Int {
        origin?.let {
            return appDb.rssReadRecordDao.countRecordsByOrigin(it)
        }
        return appDb.rssReadRecordDao.countRecords
    }

    fun deleteAllRecord(origin: String? = null) {
        execute {
            origin?.let {
                appDb.rssReadRecordDao.deleteRecordsByOrigin(it)
                return@execute
            }
            appDb.rssReadRecordDao.deleteAllRecord()
        }
    }

}