package io.legado.app.ui.download

import android.content.DialogInterface
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.databinding.ActivityDownloadManageBinding
import io.legado.app.service.DownloadState
import io.legado.app.service.DownloadStatus
import io.legado.app.service.DownloadTask
import io.legado.app.lib.dialogs.selector
import io.legado.app.utils.IntentType
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.openFileUri
import io.legado.app.utils.sendToClip
import io.legado.app.utils.startService
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.systemservices.downloadManager

/**
 * 下载管理页（precise-manage：聚合 DownloadService 任务，500ms 轮询系统 DownloadManager）
 */
class DownloadManageActivity : BaseActivity<ActivityDownloadManageBinding>() {

    override val binding by viewBinding(ActivityDownloadManageBinding::inflate)
    private val adapter: DownloadTaskAdapter by lazy { DownloadTaskAdapter(this) }
    private var tabIndex = 0

    private enum class Tab(val labelRes: Int) {
        ALL(R.string.download_tab_all),
        RUNNING(R.string.download_tab_running),
        PAUSED(R.string.download_tab_paused),
        COMPLETED(R.string.download_tab_completed),
        FAILED(R.string.download_tab_failed)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        initData()
    }

    private fun initView() {
        adapter.callBack = object : DownloadTaskAdapter.CallBack {
            override fun onClick(task: DownloadTask) {
                showTaskMenu(task)
            }
        }
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
        Tab.entries.forEach { tab ->
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText(tab.labelRes))
        }
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tabIndex = tab?.position ?: 0
                initData()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    override fun onCompatCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.download_manage, menu)
        return true
    }

    override fun onCompatOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_clear_completed -> clearCompletedTasks()
        }
        return true
    }

    private fun initData() {
        lifecycleScope.launch {
            while (isActive) {
                val tasks = DownloadState.queryAllTaskStatus()
                adapter.setItems(filterTasks(tasks))
                delay(500)
            }
        }
    }

    private fun filterTasks(tasks: List<DownloadTask>): List<DownloadTask> {
        return when (Tab.entries.getOrNull(tabIndex)) {
            Tab.ALL -> tasks
            Tab.RUNNING -> tasks.filter { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.WAITING }
            Tab.PAUSED -> tasks.filter { it.status == DownloadStatus.PAUSED }
            Tab.COMPLETED -> tasks.filter { it.status == DownloadStatus.COMPLETED }
            Tab.FAILED -> tasks.filter { it.status == DownloadStatus.FAILED }
            null -> tasks
        }.sortedByDescending { it.startTime }
    }

    private fun showTaskMenu(task: DownloadTask) {
        val items = mutableListOf<CharSequence>()
        when (task.status) {
            DownloadStatus.WAITING, DownloadStatus.RUNNING -> {
                items.add(getString(R.string.download_delete_task))
            }
            DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                items.add(getString(R.string.download_retry))
                items.add(getString(R.string.download_delete_task))
            }
            DownloadStatus.COMPLETED -> {
                items.add(getString(R.string.download_open_file))
                items.add(getString(R.string.download_copy_path))
                items.add(getString(R.string.download_delete_task))
            }
        }
        selector("", items) { _: DialogInterface, which: Int ->
            when (which) {
                0 -> when (task.status) {
                    DownloadStatus.WAITING, DownloadStatus.RUNNING -> cancelTask(task.id)
                    DownloadStatus.PAUSED, DownloadStatus.FAILED -> retryTask(task)
                    DownloadStatus.COMPLETED -> openFile(task)
                    else -> Unit
                }
                1 -> when (task.status) {
                    DownloadStatus.PAUSED, DownloadStatus.FAILED -> cancelTask(task.id)
                    DownloadStatus.COMPLETED -> sendToClip(task.fileName)
                    else -> Unit
                }
                2 -> cancelTask(task.id)
            }
        }
    }

    private fun cancelTask(id: Long) {
        DownloadState.cancelDownload(id)
        startService<io.legado.app.service.DownloadService> {
            action = IntentAction.stop
            putExtra("downloadId", id)
        }
    }

    private fun retryTask(task: DownloadTask) {
        io.legado.app.model.Download.start(
            this,
            task.url,
            task.fileName
        )
    }

    private fun openFile(task: DownloadTask) {
        kotlin.runCatching {
            downloadManager.getUriForDownloadedFile(task.id)?.let { uri ->
                openFileUri(uri, IntentType.from(task.fileName))
            }
        }.onFailure {
            AppLog.put("打开下载文件${task.fileName}出错", it)
            toastOnUi("${getString(R.string.error)}: ${it.localizedMessage}")
        }
    }

    private fun clearCompletedTasks() {
        val tasks = DownloadState.queryAllTaskStatus()
        tasks.filter {
            it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED
        }.forEach {
            DownloadState.removeTask(it.id)
        }
        toastOnUi(R.string.clear_cache_success)
        initData()
    }
}