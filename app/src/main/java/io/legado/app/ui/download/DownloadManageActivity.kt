package io.legado.app.ui.download

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.databinding.ActivityDownloadManageBinding
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.service.DownloadService
import io.legado.app.service.DownloadState
import io.legado.app.service.DownloadStatus
import io.legado.app.service.DownloadTask
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.utils.IntentType
import io.legado.app.utils.getPrefString
import io.legado.app.utils.openFileUri
import io.legado.app.utils.putPrefString
import io.legado.app.utils.sendToClip
import io.legado.app.utils.startService
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.io.File

/**
 * 下载管理页（precise-manage：聚合 DownloadService 任务，500ms 轮询系统 DownloadManager）
 * Compose 化：S2 列表族壳层 DownloadManageScreen，轮询/过滤/任务操作逻辑保留 Activity
 */
class DownloadManageActivity : BaseActivity<ActivityDownloadManageBinding>() {

    companion object {
        private const val KEY_ONLY_WIFI = "downloadOnlyWifi"

        /** 本地视频扩展名：软件内调用内置播放器播放 */
        private val VIDEO_EXTS = setOf(
            "mp4", "mkv", "webm", "avi", "mov", "flv", "wmv",
            "3gp", "m4v", "m2ts", "ts", "rmvb", "rm", "f4v"
        )
    }

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
    private var onlyWifi by mutableStateOf(false)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeHost()
        onlyWifi = appCtx.getPrefString(KEY_ONLY_WIFI) == "1"
        initData()
    }

    private fun initComposeHost() {
        binding.composeHost.setContent {
            LegadoTheme {
                DownloadManageScreen(
                    items = composeItems,
                    tabIndex = tabIndex,
                    isLoading = isLoading,
                    onlyWifi = onlyWifi,
                    onOnlyWifiChange = { enable ->
                        onlyWifi = enable
                        appCtx.putPrefString(KEY_ONLY_WIFI, if (enable) "1" else "0")
                    },
                    onTabChange = { index ->
                        tabIndex = index
                    },
                    onCancelTask = { cancelTask(it) },
                    onPauseTask = { pauseTask(it) },
                    onResumeTask = { resumeTask(it) },
                    onDeleteTask = { item, deleteFiles -> deleteTask(item, deleteFiles) },
                    onOpenFile = { openFile(it) },
                    onOpenWithPlayer = { openWithPlayer(it) },
                    onCopyPath = { sendToClip(it.localPath ?: it.fileName) },
                    currentDir = currentTargetDir(),
                    onSaveTargetDir = { saveTargetDir(it) },
                    onClearCompleted = { clearCompletedTasks() },
                    onBack = { finish() }
                )
            }
        }
    }

    private fun initData() {
        lifecycleScope.launch {
            // 进程重启/Service 重建后内存 taskMap 可能为空：先恢复 Room 持久化任务，
            // 否则任务列表"丢失"（铁证：管理页只读内存缓存，此前不触发恢复）。
            // 有未完成任务时启动 Service 自动续传（resumeAll 与 onCreate 幂等，不会重复线程）。
            val autoResume = DownloadState.resumeFromDb()
            if (autoResume.isNotEmpty()) {
                startService<DownloadService> {
                    action = IntentAction.resumeAll
                }
            }
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
        downloadedSize = downloadedSize,
        taskType = taskType,
        localPath = localPath,
        errorCode = errorCode
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
        // 删除任务并清理文件
        startService<DownloadService> {
            action = IntentAction.stop
            putExtra("downloadId", item.id)
        }
    }

    /** 暂停单任务：服务端 cancel 协程并保留临时文件，供续传 */
    private fun pauseTask(item: DownloadDisplayItem) {
        startService<DownloadService> {
            action = IntentAction.pause
            putExtra("downloadId", item.id)
        }
    }

    /** 恢复/重试：从持久化任务重新入队续传 */
    private fun resumeTask(item: DownloadDisplayItem) {
        startService<DownloadService> {
            action = IntentAction.resume
            putExtra("downloadId", item.id)
        }
    }

    /**
     * 删除任务二分（FR-12）：
     * - deleteFiles=true → 删任务并清理下载文件（服务端 removeDownload）
     * - deleteFiles=false → 仅删任务记录（先取消协程保留文件，再仅删记录）
     */
    private fun deleteTask(item: DownloadDisplayItem, deleteFiles: Boolean) {
        if (deleteFiles) {
            cancelTask(item)
            return
        }
        // 仅删记录：可选取消仍在运行的协程（保留已下文件/临时分片）
        if (item.status == DownloadStatus.RUNNING || item.status == DownloadStatus.WAITING) {
            startService<DownloadService> {
                action = IntentAction.pause
                putExtra("downloadId", item.id)
            }
        }
        DownloadState.removeTask(item.id)
        notificationManager.cancel(item.id.toInt())
    }

    private fun openFile(item: DownloadDisplayItem) {
        kotlin.runCatching {
            val path = item.localPath
            if (path.isNullOrBlank()) {
                toastOnUi(R.string.download_file_not_found)
                return@runCatching
            }
            val file = File(path)
            if (isVideoFile(item.fileName)) {
                // 软件内调用内置播放器播放（本地 ts/mp4 等，ExoPlayer 原生支持）
                val intent = Intent(this, VideoPlayerActivity::class.java).apply {
                    putExtra("isNew", true)
                    putExtra("videoUrl", Uri.fromFile(file).toString())
                    putExtra("videoTitle", item.fileName)
                }
                startActivity(intent)
            } else {
                openFileUri(Uri.fromFile(file), IntentType.from(item.fileName))
            }
        }.onFailure {
            AppLog.put("打开下载文件${item.fileName}出错", it)
            toastOnUi("${getString(R.string.error)}: ${it.localizedMessage}")
        }
    }

    private fun isVideoFile(fileName: String): Boolean =
        fileName.substringAfterLast(".", "").lowercase() in VIDEO_EXTS

    /** 软件内调用内置视频播放器播放下载产物（mp4/ts 等，ExoPlayer 按容器自识别） */
    private fun openWithPlayer(item: DownloadDisplayItem) {
        kotlin.runCatching {
            val path = item.localPath
            if (path.isNullOrBlank()) {
                toastOnUi(R.string.download_file_not_found)
                return@runCatching
            }
            val intent = Intent(this, VideoPlayerActivity::class.java).apply {
                putExtra("isNew", true)
                putExtra("videoUrl", Uri.fromFile(File(path)).toString())
                putExtra("videoTitle", item.fileName)
            }
            startActivity(intent)
        }.onFailure {
            AppLog.put("内置播放器播放下载文件${item.fileName}出错", it)
            toastOnUi("${getString(R.string.error)}: ${it.localizedMessage}")
        }
    }

    private fun clearCompletedTasks() {
        val tasks = DownloadState.queryAllTaskStatus()
        tasks.filter {
            it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED
        }.forEach {
            // clearTask 仅隐藏记录，保留已下载文件（downloadManager.remove 会连文件一起删）
            DownloadState.clearTask(it.id)
        }
        toastOnUi(R.string.clear_cache_success)
    }

    /** 当前下载目标目录：用户配置优先，否则默认应用内置私有目录（无需授权） */
    private fun currentTargetDir(): String =
        appCtx.getPrefString(DownloadService.KEY_TARGET_DIR)
            ?.takeIf { it.isNotBlank() }
            ?: DownloadService.defaultPublicDir().absolutePath

    /** 保存下载目标目录：空输入回退默认内置私有目录；填了公有路径才需要授权（4.7 可配置路径） */
    private fun saveTargetDir(path: String) {
        val trimmed = path.trim()
        appCtx.putPrefString(DownloadService.KEY_TARGET_DIR, trimmed)
        if (trimmed.isBlank()) {
            toastOnUi(R.string.download_dir_reset_default)
            return
        }
        toastOnUi(R.string.download_dir_saved)
        // 填了公有路径且未授权 → 主动引导申请存储权限（Android 11+ 跳系统设置，8-9 走运行时对话框）
        if (DownloadService.targetDirNeedsPermission() && !DownloadService.storageWriteGranted()) {
            requestStoragePermission()
        }
    }

    /** 申请存储权限：Android 11+ 跳系统设置（MANAGE），Android 8-9 走运行时对话框（WRITE），Android 10 仅提示 */
    private fun requestStoragePermission() {
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.download_dir_setting)
            .onGranted { runCatching { toastOnUi(R.string.download_storage_permission_granted) } }
            .request()
    }
}
