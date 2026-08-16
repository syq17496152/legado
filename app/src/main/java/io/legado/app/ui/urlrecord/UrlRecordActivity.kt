package io.legado.app.ui.urlrecord

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.databinding.ActivityUrlRecordBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.sendToClip
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * URL 访问记录页（precise-manage：UrlRecord Room DAO 数据）
 * Compose 化：S2 列表族壳层 UrlRecordScreen，搜索/四维过滤/清除/详情逻辑保留 Activity
 */
class UrlRecordActivity : BaseActivity<ActivityUrlRecordBinding>() {

    override val binding by viewBinding(ActivityUrlRecordBinding::inflate)

    private var filterDomain: String? = null
    private var filterSourceName: String? = null
    private var filterMethod: String? = null
    private var filterSuccess: Boolean? = null

    // Compose 桥接状态
    private var searchKey by mutableStateOf("")
    private var composeItems by mutableStateOf(listOf<UrlRecordDisplayItem>())
    private var isLoading by mutableStateOf(true)
    private var recordEnabled by mutableStateOf(AppConfig.recordUrl)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeHost()
        loadData()
    }

    private fun initComposeHost() {
        binding.composeHost.setContent {
            LegadoTheme {
                UrlRecordScreen(
                    items = composeItems,
                    isLoading = isLoading,
                    recordEnabled = recordEnabled,
                    searchKey = searchKey,
                    onSearchChange = {
                        searchKey = it
                        loadData()
                    },
                    onToggleRecord = {
                        AppConfig.recordUrl = it
                        recordEnabled = it
                    },
                    onFilterClick = { showFilterDialog() },
                    onClear7d = { clearOldRecords(7) },
                    onClear30d = { clearOldRecords(30) },
                    onClearAll = { clearAllRecords() },
                    onItemClick = { showDetailDialog(it) },
                    onBack = { finish() }
                )
            }
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            isLoading = true
            val records = withContext(IO) {
                appDb.urlRecordDao.flowFilter(
                    domain = filterDomain,
                    sourceName = filterSourceName,
                    method = filterMethod,
                    success = filterSuccess,
                    keyword = searchKey.ifBlank { null }
                ).first()
            }
            composeItems = records.map { it.toDisplayItem() }
            isLoading = false
        }
    }

    private fun io.legado.app.data.entities.UrlRecord.toDisplayItem() = UrlRecordDisplayItem(
        id = id,
        url = url,
        domain = domain,
        method = method,
        sourceName = sourceName,
        timestamp = timestamp,
        responseCode = responseCode,
        duration = duration,
        errorMsg = errorMsg
    )

    private fun showFilterDialog() {
        selector(
            getString(R.string.url_record_filter),
            listOf(
                getString(R.string.url_record_filter_domain),
                getString(R.string.url_record_filter_source),
                getString(R.string.url_record_filter_method),
                getString(R.string.url_record_filter_status),
                getString(R.string.clear_filter)
            )
        ) { _, which ->
            when (which) {
                0 -> lifecycleScope.launch {
                    val domains = withContext(IO) { appDb.urlRecordDao.flowAllDomains().first() }
                    selector(getString(R.string.url_record_filter_domain), domains) { _, i ->
                        filterDomain = domains[i]; loadData()
                    }
                }

                1 -> lifecycleScope.launch {
                    val sources = withContext(IO) { appDb.urlRecordDao.flowAllSourceNames().first() }
                    selector(getString(R.string.url_record_filter_source), sources) { _, i ->
                        filterSourceName = sources[i]; loadData()
                    }
                }

                2 -> lifecycleScope.launch {
                    val methods = withContext(IO) { appDb.urlRecordDao.flowAllMethods().first() }
                    selector(getString(R.string.url_record_filter_method), methods) { _, i ->
                        filterMethod = methods[i]; loadData()
                    }
                }

                3 -> selector(
                    getString(R.string.url_record_filter_status),
                    listOf(getString(R.string.filter_success), getString(R.string.filter_failed))
                ) { _, i ->
                    filterSuccess = i == 0
                    loadData()
                }

                4 -> {
                    filterDomain = null
                    filterSourceName = null
                    filterMethod = null
                    filterSuccess = null
                    loadData()
                }
            }
        }
    }

    private fun clearOldRecords(days: Int) {
        lifecycleScope.launch(IO) {
            val boundary = System.currentTimeMillis() - days * 24 * 60 * 60 * 1000L
            appDb.urlRecordDao.deleteOldRecords(boundary)
        }
        loadData()
    }

    private fun clearAllRecords() {
        lifecycleScope.launch(IO) {
            appDb.urlRecordDao.deleteAll()
        }
        loadData()
    }

    private fun showDetailDialog(item: UrlRecordDisplayItem) {
        val domainText = item.domain.ifBlank { "—" }
        alert(R.string.url_record_detail) {
            setMessage(
                getString(
                    R.string.url_record_detail_message,
                    item.method,
                    if (item.errorMsg.isNullOrBlank()) "${item.responseCode}" else item.errorMsg!!,
                    item.duration,
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(item.timestamp)),
                    domainText,
                    item.url,
                    item.sourceName ?: "—"
                )
            )
            positiveButton(R.string.copy_url) {
                sendToClip(item.url)
            }
            negativeButton(R.string.cancel)
        }
    }
}
