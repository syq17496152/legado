package io.legado.app.ui.about

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.databinding.ActivityReadRecordBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.about.ReadRecordWidgetStore
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.cnCompare
import io.legado.app.utils.formatDuring
import io.legado.app.utils.getInt
import io.legado.app.utils.putInt
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读记录页（L-C20，S2 列表管理页）
 * Compose 化：ReadRecordScreen 壳层，搜索/排序/记录开关/清除/查书/删除逻辑保留 Activity
 */
class ReadRecordActivity : BaseActivity<ActivityReadRecordBinding>() {

    override val binding by viewBinding(ActivityReadRecordBinding::inflate)

    private var sortMode
        get() = LocalConfig.getInt("readRecordSort")
        set(value) {
            LocalConfig.putInt("readRecordSort", value)
        }

    // Compose 桥接状态
    private var searchKey by mutableStateOf("")
    private var composeItems by mutableStateOf(listOf<ReadRecordShow>())
    private var totalReadTime by mutableStateOf("")
    private var isLoading by mutableStateOf(true)
    private var recordEnabled by mutableStateOf(AppConfig.enableReadRecord)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeHost()
        initAllTime()
        loadData()
    }

    private fun initComposeHost() {
        binding.scrollView.visibility = android.view.View.GONE
        binding.topBar.visibility = android.view.View.GONE
        binding.titleBar.visibility = android.view.View.GONE
        binding.composeHost.visibility = android.view.View.VISIBLE
        binding.composeHost.setContent {
            LegadoTheme {
                ReadRecordScreen(
                    records = composeItems,
                    totalReadTime = totalReadTime,
                    isLoading = isLoading,
                    recordEnabled = recordEnabled,
                    sortMode = sortMode,
                    searchKey = searchKey,
                    onSearchChange = {
                        searchKey = it
                        loadData()
                    },
                    onToggleRecord = {
                        AppConfig.enableReadRecord = it
                        recordEnabled = it
                    },
                    onSort = {
                        sortMode = it
                        loadData()
                    },
                    onClearAll = { clearAll() },
                    onItemClick = { openBook(it) },
                    onDeleteItem = { deleteOne(it) },
                    onBack = { finish() }
                )
            }
        }
    }

    private fun initAllTime() {
        lifecycleScope.launch {
            val allTime = withContext(IO) {
                appDb.readRecordDao.allTime
            }
            totalReadTime = formatDuring(allTime)
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            isLoading = true
            val readRecords = withContext(IO) {
                appDb.readRecordDao.search(searchKey).let { records ->
                    when (sortMode) {
                        1 -> records.sortedByDescending { it.readTime }
                        2 -> records.sortedByDescending { it.lastRead }
                        else -> records.sortedWith { o1, o2 ->
                            o1.bookName.cnCompare(o2.bookName)
                        }
                    }
                }
            }
            composeItems = readRecords
            isLoading = false
        }
    }

    private fun clearAll() {
        lifecycleScope.launch(IO) {
            appDb.readRecordDao.clear()
            appDb.readRecordDailyDao.clear()
            appDb.readRecentBookDao.clear()
            ReadRecordWidgetStore.clearRecentSnapshots()
        }
        loadData()
    }

    private fun deleteOne(item: ReadRecordShow) {
        lifecycleScope.launch(IO) {
            appDb.readRecordDao.deleteByName(item.bookName)
        }
        loadData()
    }

    private fun openBook(item: ReadRecordShow) {
        lifecycleScope.launch {
            val book = withContext(IO) {
                appDb.bookDao.findByName(item.bookName).firstOrNull()
            }
            if (book == null) {
                SearchActivity.start(this@ReadRecordActivity, item.bookName)
            } else {
                startActivityForBook(book)
            }
        }
    }
}
