package io.legado.app.ui.rss.source.debug

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.sortUrls
import io.legado.app.model.Debug

/**
 * debug-page-redesign：订阅源调试 VM（不再实现 Debug.Callback——UI 订阅 Debug.events；
 * 响应源码即事件卡片点击全文）。示例快捷项 = 分类列表（sortUrls，key 为 name::url）。
 */
class RssSourceDebugModel(application: Application) : BaseViewModel(application) {

    var rssSource: RssSource? = null
        private set

    var sourceName: String by mutableStateOf("")
        private set

    var examples: List<RssDebugExample> by mutableStateOf(emptyList())
        private set

    fun initData(sourceUrl: String?, finally: () -> Unit) {
        sourceUrl?.let {
            execute {
                rssSource = appDb.rssSourceDao.getByKey(sourceUrl)
                sourceName = rssSource?.sourceName.orEmpty()
                buildExamples()
            }.onFinally {
                finally()
            }
        }
    }

    private suspend fun buildExamples() {
        val source = rssSource ?: return
        runCatching {
            examples = source.sortUrls()
                .filter { it.second.isNotBlank() }
                .take(20)
                .map { RssDebugExample(it.first, RssDebugTarget.CLASSIFY, "${it.first}::${it.second}") }
        }
    }

    fun startDebug(key: String, onError: () -> Unit) {
        val source = rssSource ?: run {
            onError.invoke()
            return
        }
        Debug.clearEvents()
        execute {
            Debug.startDebug(this, source, key)
        }.onError {
            onError.invoke()
        }
    }

    fun stopDebug() {
        Debug.cancelDebug()
    }

    fun clearLogs() {
        Debug.clearEvents()
        Debug.clearSessionLogs()
    }

    override fun onCleared() {
        super.onCleared()
        Debug.cancelDebug(true)
    }
}
