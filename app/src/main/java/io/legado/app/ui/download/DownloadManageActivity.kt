package io.legado.app.ui.download

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.databinding.ActivityDownloadManageBinding
import io.legado.app.service.DownloadState
import io.legado.app.service.DownloadStatus
import io.legado.app.service.DownloadTask
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.IntentType
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
 * Compose 化：S2 列表族壳层 DownloadManageScreen，轮询/过滤/任务操作逻辑保留 Activity
 */
class DownloadManageActivity : BaseActivity<ActivityDownloadManageBinding>() {

    override val binding by viewBinding(ActivityDownloadManageBinding::inflate)

    private enum class Tab(val labelRes: Int) {
        ALL(R.string.download_tab_all),
        RUNNING(R.string.download_tab_running),
        PAUSED(R.string.download_tab_paused),
        COMPLETED(R.string.download_tab_completed),
        FAILED(R.string.download_tab_failed)
    }

    // Compose 桥接状态
    private var composeItems by mutableStateOf(listOf<DownloadDisplayItem>())
    private var tabIndex by mutableStateOf(0)
    private var isLoading by mutableStateOf(true)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeHost()
        initData()
    }

    private fun initComposeHost() {
        binding.composeHost.setContent {
            LegadoTheme {
                DownloadManageScreen(
                    items = composeItems,
                    tabIndex = tabIndex,
                    isLoading = isLoading,
                    onTabChange = { index ->
                        tabIndex = index
                    },
                    onCancelTask = { cancelTask(it) },
                    onRetryTask = { retryTask(it) },
                    onOpenFile = { openFile(it) },
                    onCopyPath = { sendToClip(it.fileName) },
                    onClearCompleted = { clearCompletedTasks() },
                    onBack = { finish() }
                )
            }
        }
    }

    private fun initData() {
        lifecycleScope.launch {
            while (isActive) {
                val tasks = DownloadState.queryAllTaskStatus()
                composeItems = filterTasks(tasks).map { it.toDisplayItem() }
                isLoading = false
                delay(500)
            }
        }
    }

    private fun DownloadTask.toDisplayItem() = DownloadDisplayItem(
        id = id,
        fileName = fileName,
        url = url,
        status = status,
        totalSize = totalSize,
        downloadedSize = downloadedSize
    )

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

    private fun cancelTask(item: DownloadDisplayItem) {
        DownloadState.cancelDownload(item.id)
        startService<io.legado.app.service.DownloadService> {
            action = IntentAction.stop
            putExtra("downloadId", item.id)
        }
    }

    private fun retryTask(item: DownloadDisplayItem) {
        io.legado.app.model.Download.start(
            this,
            item.url,
            item.fileName
        )
    }

    private fun openFile(item: DownloadDisplayItem) {
        kotlin.runCatching {
            downloadManager.getUriForDownloadedFile(item.id)?.let { uri ->
                openFileUri(uri, IntentType.from(item.fileName))
            }
        }.onFailure {
            AppLog.put("打开下载文件${item.fileName}出错", it)
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
    }
}
