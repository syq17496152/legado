package io.legado.app.ui.urlrecord

import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.UrlRecord
import io.legado.app.databinding.ActivityUrlRecordBinding
import io.legado.app.databinding.ItemUrlRecordBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.sendToClip
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UrlRecordActivity : BaseActivity<ActivityUrlRecordBinding>() {

    private val adapter by lazy { UrlRecordAdapter(this) }
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private var filterDomain: String? = null
    private var filterSourceName: String? = null
    private var filterMethod: String? = null
    private var filterSuccess: Boolean? = null

    override val binding by viewBinding(ActivityUrlRecordBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        initData()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.url_record, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_url_record_enable)?.isChecked = AppConfig.recordUrl
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_url_record_enable -> {
                AppConfig.recordUrl = !item.isChecked
                item.isChecked = !item.isChecked
            }

            R.id.menu_url_record_filter -> {
                showFilterDialog()
            }

            R.id.menu_url_record_clear_7d -> {
                clearOldRecords(7)
            }

            R.id.menu_url_record_clear_30d -> {
                clearOldRecords(30)
            }

            R.id.menu_url_record_clear_all -> {
                clearAllRecords()
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initView() {
        initSearchView()
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.search)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                searchView.clearFocus()
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                initData(newText)
                return false
            }
        })
    }

    private fun initData(searchKey: String? = null) {
        lifecycleScope.launch {
            val records = withContext(IO) {
                appDb.urlRecordDao.flowFilter(
                    domain = filterDomain,
                    sourceName = filterSourceName,
                    method = filterMethod,
                    success = filterSuccess,
                    keyword = searchKey?.ifBlank { null }
                ).first()
            }
            adapter.setItems(records)
        }
    }

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
                        filterDomain = domains[i]; initData()
                    }
                }

                1 -> lifecycleScope.launch {
                    val sources = withContext(IO) { appDb.urlRecordDao.flowAllSourceNames().first() }
                    selector(getString(R.string.url_record_filter_source), sources) { _, i ->
                        filterSourceName = sources[i]; initData()
                    }
                }

                2 -> lifecycleScope.launch {
                    val methods = withContext(IO) { appDb.urlRecordDao.flowAllMethods().first() }
                    selector(getString(R.string.url_record_filter_method), methods) { _, i ->
                        filterMethod = methods[i]; initData()
                    }
                }

                3 -> selector(
                    getString(R.string.url_record_filter_status),
                    listOf(getString(R.string.filter_success), getString(R.string.filter_failed))
                ) { _, i ->
                    filterSuccess = i == 0
                    initData()
                }

                4 -> {
                    filterDomain = null
                    filterSourceName = null
                    filterMethod = null
                    filterSuccess = null
                    initData()
                }
            }
        }
    }

    private fun clearOldRecords(days: Int) {
        alert(R.string.clear, R.string.sure_del) {
            yesButton {
                lifecycleScope.launch(IO) {
                    val boundary = System.currentTimeMillis() - days * 24 * 60 * 60 * 1000L
                    appDb.urlRecordDao.deleteOldRecords(boundary)
                }
                initData()
            }
            noButton()
        }
    }

    private fun clearAllRecords() {
        alert(R.string.clear, R.string.sure_del) {
            yesButton {
                lifecycleScope.launch(IO) {
                    appDb.urlRecordDao.deleteAll()
                }
                initData()
            }
            noButton()
        }
    }

    inner class UrlRecordAdapter(context: Context) :
        RecyclerAdapter<UrlRecord, ItemUrlRecordBinding>(context) {

        private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        override fun getViewBinding(parent: ViewGroup): ItemUrlRecordBinding {
            return ItemUrlRecordBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemUrlRecordBinding,
            item: UrlRecord,
            payloads: MutableList<Any>,
        ) {
            binding.apply {
                tvMethod.text = item.method
                tvMethod.setTextColor(
                    when (item.method) {
                        "GET" -> 0xFF1E88E5.toInt()
                        "POST" -> 0xFF8E24AA.toInt()
                        "PUT" -> 0xFFF57C00.toInt()
                        "DELETE" -> 0xFFE53935.toInt()
                        else -> primaryTextColor
                    }
                )
                tvStatus.text = if (item.errorMsg.isNullOrBlank()) {
                    "${item.responseCode}"
                } else {
                    getString(R.string.url_record_error)
                }
                tvStatus.setTextColor(
                    if (item.errorMsg.isNullOrBlank() && item.responseCode in 200..299) {
                        0xFF43A047.toInt()
                    } else if (item.errorMsg.isNullOrBlank() && item.responseCode in 400..499) {
                        0xFFFB8C00.toInt()
                    } else {
                        0xFFE53935.toInt()
                    }
                )
                tvDuration.text = "${item.duration}ms"
                tvTime.text = formatTime(item.timestamp)
                tvUrl.text = item.url
                tvDomain.text = item.domain
                item.sourceName?.takeIf { it.isNotBlank() }?.let {
                    tvSourceName.text = it
                    tvSourceName.visibility = android.view.View.VISIBLE
                } ?: run {
                    tvSourceName.visibility = android.view.View.GONE
                }
            }
        }

        private fun formatTime(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            if (diff < 60 * 1000) return getString(R.string.just_now)
            if (diff < 60 * 60 * 1000) return getString(R.string.minutes_ago, diff / (60 * 1000))
            if (diff < 24 * 60 * 60 * 1000) return getString(R.string.hours_ago, diff / (60 * 60 * 1000))
            if (diff < 48 * 60 * 60 * 1000) return getString(R.string.days_ago, diff / (24 * 60 * 60 * 1000))
            return timeFormat.format(Date(timestamp))
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemUrlRecordBinding) {
            binding.root.setOnClickListener {
                val item = getItem(holder.layoutPosition) ?: return@setOnClickListener
                showDetailDialog(item)
            }
        }

        private fun showDetailDialog(item: UrlRecord) {
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

}