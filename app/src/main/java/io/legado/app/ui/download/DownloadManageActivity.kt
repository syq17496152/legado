package io.legado.app.ui.download

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.io.File

/**
 * 下载管理页（precise-manage：聚合 DownloadService 任务）
 * Compose 化：S2 列表族壳层 DownloadManageScreen。
 *
 * A5（download-manager-optimize）：订阅 DownloadState.tasks StateFlow（≤2Hz 节流发射）替代
 * 旧 500ms 轮询；repeatOnLifecycle(STARTED) 后台自动停止收集（不白耗电）。
 * C2：onResume 校准 RUNNING 残留（幂等合并恢复）。
 * C5：DB 读写全部下沉 IO 线程。
 */
class DownloadManageActivity : BaseActivity<ActivityDownloadManageBinding>() {

    companion object {
        private const val KEY_ONLY_WIFI = "downloadOnlyWifi"
    }

    override val binding by viewBinding(ActivityDownloadManageBinding::inflate)

    // Compose 桥接状态
    private var composeItems by mutableStateOf(listOf<DownloadDisplayItem>())
    private var tabIndex by mutableStateOf(0)
    private var isLoading by mutableStateOf(true)
    private var onlyWifi by mutableStateOf(false)
    // D6：目录状态化（原实现每次重组重读 Pref+构造 File）
    private var currentDir by mutableStateOf("")

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        currentDir = currentTargetDir()
        initComposeHost()
        onlyWifi = appCtx.getPrefString(KEY_ONLY_WIFI) == "1"
        initData()
    }

    override fun onResume() {
        super.onResume()
        // C2：回到本页时校准——进程被杀后 RUNNING 残留的触发点不止 onCreate；
        // resumeFromDb 合并式幂等（内存优先不降级），重复调用无副作用
        lifecycleScope.launch { calibrateTasks() }
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
                    onPauseTask = { pauseTask(it) },
                    onResumeTask = { resumeTask(it) },
                    onDeleteTask = { item, deleteFiles -> deleteTask(item, deleteFiles) },
                    onOpenFile = { openFile(it) },
                    onOpenWithPlayer = { openWithPlayer(it) },
                    onCopyPath = { sendToClip(it.localPath ?: it.fileName) },
                    currentDir = currentDir,
                    onSaveTargetDir = { saveTargetDir(it) },
                    onClearCompleted = { clearCompletedTasks() },
                    onBack = { finish() }
                )
            }
        }
    }

    /** A5：StateFlow 订阅 + STARTED 生命周期感知（后台停止收集） */
    private fun initData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                calibrateTasks()
                isLoading = false
                DownloadState.tasks.collect { tasks ->
                    composeItems = filterTasks(tasks.values.toList()).map { it.toDisplayItem() }
                }
            }
        }
    }

    /** C2/C5：恢复 Room 持久化任务（IO 线程），有未完成则启动 Service 自动续传 */
    private suspend fun calibrateTasks() = withContext(Dispatchers.IO) {
        val autoResume = DownloadState.resumeFromDb()
        if (autoResume.isNotEmpty()) {
            startService<DownloadService> {
                action = IntentAction.resumeAll
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
        speed = speed,
        taskType = taskType,
        localPath = localPath,
        errorCode = errorCode
    )

    /** C6：Tab 过滤基于单源枚举 DownloadTab */
    private fun filterTasks(tasks: List<DownloadTask>): List<DownloadTask> {
        return when (DownloadTab.entries.getOrNull(tabIndex)) {
            DownloadTab.ALL -> tasks
            DownloadTab.RUNNING -> tasks.filter {
                it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.WAITING
            }
            DownloadTab.PAUSED -> tasks.filter { it.status == DownloadStatus.PAUSED }
            DownloadTab.COMPLETED -> tasks.filter { it.status == DownloadStatus.COMPLETED }
            DownloadTab.FAILED -> tasks.filter { it.status == DownloadStatus.FAILED }
            null -> tasks
        }.sortedByDescending { it.startTime }
    }

    /** 删除任务并清理文件 */
    private fun pauseThenStop(item: DownloadDisplayItem) {
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
     * 时序契约（O-UI-12）：先异步 pause 再同步 removeTask —— 服务端 pauseDownload 的
     * updateTask 会因内存/DB 记录已删而无害 no-op，正确性依赖该先后顺序，勿对调。
     */
    private fun deleteTask(item: DownloadDisplayItem, deleteFiles: Boolean) {
        if (deleteFiles) {
            pauseThenStop(item)
            return
        }
        // 仅删记录：可选取消仍在运行的协程（保留已下文件/临时分片）
        if (item.status == DownloadStatus.RUNNING || item.status == DownloadStatus.WAITING) {
            startService<DownloadService> {
                action = IntentAction.pause
                putExtra("downloadId", item.id)
            }
        }
        // C5：DB 写下沉 IO
        lifecycleScope.launch(Dispatchers.IO) {
            DownloadState.removeTask(item.id)
        }
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
            // C1：文件存在性校验（外部删除/系统清理后不白进播放器报错）
            if (!file.exists()) {
                toastOnUi(R.string.download_file_missing)
                return@runCatching
            }
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

    /** C6 单源：视频扩展名与 Screen 共用 DOWNLOAD_VIDEO_EXTS */
    private fun isVideoFile(fileName: String): Boolean =
        fileName.substringAfterLast(".", "").lowercase() in DOWNLOAD_VIDEO_EXTS

    /** 软件内调用内置视频播放器播放下载产物（mp4/ts 等，ExoPlayer 按容器自识别） */
    private fun openWithPlayer(item: DownloadDisplayItem) {
        kotlin.runCatching {
            val path = item.localPath
            if (path.isNullOrBlank()) {
                toastOnUi(R.string.download_file_not_found)
                return@runCatching
            }
            val file = File(path)
            // C1：文件存在性校验
            if (!file.exists()) {
                toastOnUi(R.string.download_file_missing)
                return@runCatching
            }
            val intent = Intent(this, VideoPlayerActivity::class.java).apply {
                putExtra("isNew", true)
                putExtra("videoUrl", Uri.fromFile(file).toString())
                putExtra("videoTitle", item.fileName)
            }
            startActivity(intent)
        }.onFailure {
            AppLog.put("内置播放器播放下载文件${item.fileName}出错", it)
            toastOnUi("${getString(R.string.error)}: ${it.localizedMessage}")
        }
    }

    /**
     * 清除已完成/失败记录（C7：文案与行为对齐，实际含失败任务）。
     * C3：FAILED 记录联动清理临时产物（.part/.seg/.partN/HLS tempDir），防孤儿累积；
     * COMPLETED 仅删记录保留最终文件。C4：逐项取消对应通知。
     */
    private fun clearCompletedTasks() {
        lifecycleScope.launch(Dispatchers.IO) {
            val tasks = DownloadState.queryAllTaskStatus()
            tasks.filter {
                it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED
            }.forEach { task ->
                if (task.status == DownloadStatus.FAILED) {
                    DownloadService.deleteTaskFiles(
                        appCtx, task.taskType, task.localPath, task.fileName, task.id
                    )
                }
                DownloadState.removeTask(task.id)
                notificationManager.cancel(task.id.toInt())
            }
            toastOnUi(R.string.download_clear_success)
        }
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
        currentDir = currentTargetDir()
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
