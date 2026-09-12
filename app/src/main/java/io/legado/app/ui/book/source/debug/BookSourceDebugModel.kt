package io.legado.app.ui.book.source.debug

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.Debug

/**
 * debug-page-redesign：书源调试 VM（不再实现 Debug.Callback——UI 改为 collectAsState 订阅
 * Debug.events 结构化事件流；响应源码即事件卡片点击全文，无需 printLog 分流缓存）。
 */
class BookSourceDebugModel(application: Application) : BaseViewModel(application) {

    var bookSource: BookSource? = null
        private set

    var sourceName: String by mutableStateOf("")
        private set

    /** 示例快捷项：checkKeyWord（缺省"我的"）+ "系统" + 发现分类（AD-04/MD3 对标） */
    var examples: List<DebugExample> by mutableStateOf(emptyList())
        private set

    fun init(sourceUrl: String?, finally: () -> Unit) {
        sourceUrl?.let {
            execute {
                bookSource = appDb.bookSourceDao.getBookSource(sourceUrl)
                sourceName = bookSource?.bookSourceName.orEmpty()
                buildExamples()
            }.onFinally {
                finally.invoke()
            }
        }
    }

    private suspend fun buildExamples() {
        val source = bookSource ?: return
        val list = mutableListOf<DebugExample>()
        val keyword = source.ruleSearch?.checkKeyWord?.takeIf { it.isNotBlank() } ?: "我的"
        list.add(DebugExample(keyword, BookDebugTarget.SEARCH, keyword))
        list.add(DebugExample("系统", BookDebugTarget.SEARCH, "系统"))
        runCatching {
            source.exploreKinds()
                .filter { !it.url.isNullOrBlank() }
                .forEach { kind ->
                    val label = kind.title.ifBlank { "分类" }
                    list.add(DebugExample(label, BookDebugTarget.EXPLORE, kind.url.orEmpty()))
                }
        }
        examples = list.distinctBy { it.target to it.value }
    }

    /** 新会话开始：清事件流后启动调试链路（事件经 Debug.events 下发给 Screen） */
    fun startDebug(key: String, onError: () -> Unit) {
        val source = bookSource ?: run {
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

    /** FAB 停止：cancelDebug → tasks.clear() 逐个 cancel（CompositeCoroutine 语义已核实） */
    fun stopDebug() {
        Debug.cancelDebug()
    }

    /** 顶栏「清空」：事件流 + 会话文本缓冲一并清空 */
    fun clearLogs() {
        Debug.clearEvents()
        Debug.clearSessionLogs()
    }

    override fun onCleared() {
        super.onCleared()
        Debug.cancelDebug(true)
    }
}
