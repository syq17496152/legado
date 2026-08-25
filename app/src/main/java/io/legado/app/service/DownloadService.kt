package io.legado.app.service

import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.help.download.ChunkDownloader
import io.legado.app.help.download.HlsDownloader
import io.legado.app.help.download.HlsResult
import io.legado.app.utils.IntentType
import io.legado.app.utils.openFileUri
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * 下载文件服务（video-download-manager）
 *
 * 自研下载引擎调度，替换原系统 DownloadManager 方案：
 * - 直链：ChunkDownloader 多线程 Range 分片下载（IDM 式）
 * - m3u8：HlsDownloader 分片下载 + ts 转 mp4
 * - 状态写入 DownloadState（内存），前台 Service 保活，任务级通知展示进度
 */
class DownloadService : BaseService() {

    private val groupKey = "${appCtx.packageName}.download"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloads = hashMapOf<Long, DownloadInfo>()
    private val downloadJobs = hashMapOf<Long, Job>()
    private val idGenerator = AtomicLong(1)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> {
                val url = intent.getStringExtra("url") ?: return super.onStartCommand(intent, flags, startId)
                startDownload(
                    url,
                    intent.getStringExtra("fileName"),
                    intent.getStringExtra("taskType"),
                    intent.getStringExtra("headers")
                )
            }

            IntentAction.play -> {
                val id = intent.getLongExtra("downloadId", 0)
                val info = downloads[id]
                if (info?.localPath != null) {
                    openDownload(info.localPath, info.fileName)
                } else {
                    toastOnUi(getString(R.string.download_unfinished_tip))
                }
            }

            IntentAction.stop -> {
                val downloadId = intent.getLongExtra("downloadId", 0)
                removeDownload(downloadId)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    @Synchronized
    private fun startDownload(
        url: String,
        rawFileName: String?,
        taskTypeStr: String?,
        headersJson: String?
    ) {
        if (downloads.values.any { it.url == url }) {
            toastOnUi(R.string.download_already_in_list)
            return
        }
        val taskType = taskTypeStr?.let { runCatching { DownloadTaskType.valueOf(it) }.getOrNull() }
            ?: if (url.contains(".m3u8", ignoreCase = true)) DownloadTaskType.HLS else DownloadTaskType.DIRECT
        val headers = parseHeaders(headersJson)
        val fileName = resolveFileName(rawFileName, url, taskType)
        val id = idGenerator.getAndIncrement()
        val notificationId = NotificationId.Download + downloads.size
        DownloadState.addTask(id, url, fileName, taskType = taskType)
        downloads[id] = DownloadInfo(url, fileName, notificationId, null, taskType)

        val job = scope.launch {
            DownloadState.updateTask(id, DownloadStatus.RUNNING)
            try {
                val localPath = when (taskType) {
                    DownloadTaskType.HLS -> downloadHls(url, fileName, headers, id, notificationId)
                    DownloadTaskType.DIRECT -> downloadDirect(url, fileName, headers, id, notificationId)
                }
                val size = runCatching { File(localPath).length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
                    .getOrDefault(0)
                downloads[id]?.let { downloads[id] = it.copy(localPath = localPath) }
                DownloadState.updateTask(
                    id, DownloadStatus.COMPLETED, progress = 100, totalSize = size, downloadedSize = size,
                    localPath = localPath
                )
                upDownloadNotification(
                    notificationId, "${fileName} ${getString(R.string.download_success)}",
                    size, size, downloads[id]?.startTime ?: 0L
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("下载失败 $fileName", e)
                DownloadState.updateTask(id, DownloadStatus.FAILED)
                upDownloadNotification(
                    notificationId, "$fileName ${getString(R.string.download_error)}",
                    1, 0, downloads[id]?.startTime ?: 0L
                )
                toastOnUi(getString(R.string.download_fail_tip))
            } finally {
                downloadJobs.remove(id)
                downloads.remove(id)
                // 仅当所有任务都结束（downloads 为空）时才停止服务；
                // 否则一个任务失败时误停服务会把其他正在下载的任务一并中断。
                if (downloads.isEmpty()) stopSelf()
            }
        }
        downloadJobs[id] = job
        toastOnUi(R.string.download_started)
    }

    private suspend fun downloadDirect(
        url: String,
        fileName: String,
        headers: Map<String, String>,
        id: Long,
        notificationId: Int
    ): String {
        val localFile = uniqueFile(resolveTargetDir(DownloadTaskType.DIRECT), fileName)
        val ok = ChunkDownloader.downloadDirect(url, localFile, headers) { done, total ->
            updateProgress(id, notificationId, fileName, done, total)
        }
        if (!ok) throw IOException("直链下载失败")
        return localFile.path
    }

    private suspend fun downloadHls(
        url: String,
        fileName: String,
        headers: Map<String, String>,
        id: Long,
        notificationId: Int
    ): String {
        val mp4Name = if (fileName.endsWith(".mp4", ignoreCase = true)) fileName else "$fileName.mp4"
        val mp4File = uniqueFile(resolveTargetDir(DownloadTaskType.HLS), mp4Name)
        val tempDir = File(cacheDir, "video_download_$id")
        val result = HlsDownloader.download(url, mp4File, tempDir, headers) { done, total ->
            val pct = if (total > 0) (done * 100f / total).toInt() else 0
            DownloadState.updateTask(
                id, status = DownloadStatus.RUNNING, progress = pct,
                totalSize = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                downloadedSize = done.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
        }
        return when (result) {
            is HlsResult.Mp4 -> mp4File.path
            is HlsResult.TsFallback -> {
                val ts = File(tempDir, mp4File.nameWithoutExtension + ".ts")
                // 转换失败保留 ts，移动到目标目录
                val targetTs = uniqueFile(resolveTargetDir(DownloadTaskType.HLS), mp4File.nameWithoutExtension + ".ts")
                ts.copyTo(targetTs, overwrite = true)
                targetTs.path
            }
            is HlsResult.UnsupportedCrypto -> {
                tempDir.deleteRecursively()
                throw IOException(getString(R.string.download_encrypt_unsupported))
            }
            HlsResult.Failed -> {
                tempDir.deleteRecursively()
                throw IOException(getString(R.string.download_hls_failed))
            }
        }
    }

    private fun resolveTargetDir(taskType: DownloadTaskType): File {
        // Android 11+ 写公有 Downloads 目录需要 MANAGE_EXTERNAL_STORAGE 特殊权限，
        // 该权限无法用运行时对话框申请（只能跳系统设置页），无授权时写入会直接抛异常导致任务全部失败。
        // 统一改写入 app 专属外部下载目录：scoped storage 下无需任何权限，保证下载必然成功。
        val base = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        val dir = if (taskType == DownloadTaskType.HLS) File(base, "m3u8") else base
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @Synchronized
    private fun updateProgress(id: Long, notificationId: Int, fileName: String, done: Long, total: Long) {
        val totalI = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val doneI = done.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val pct = if (totalI > 0) (doneI * 100f / totalI).toInt() else 0
        DownloadState.updateTask(
            id, status = DownloadStatus.RUNNING, progress = pct,
            totalSize = totalI, downloadedSize = doneI
        )
        // 通知进度节流：仅整 5% 更新，降低省电
        if (pct % 5 == 0 || doneI >= totalI && totalI > 0) {
            upDownloadNotification(
                notificationId, "${fileName} ${getString(R.string.downloading)}",
                totalI, doneI, downloads[id]?.startTime ?: 0L
            )
        }
    }

    @Synchronized
    private fun removeDownload(downloadId: Long) {
        downloadJobs.remove(downloadId)?.cancel()
        downloads.remove(downloadId)?.let { info ->
            val dir = resolveTargetDir(info.taskType)
            File(dir, info.fileName).delete()
            File(cacheDir, "video_download_$downloadId").deleteRecursively()
        }
        DownloadState.removeTask(downloadId)
        notificationManager.cancel(downloadId.toInt())
    }

    private fun openDownload(localPath: String, fileName: String?) {
        kotlin.runCatching {
            openFileUri(Uri.fromFile(File(localPath)), IntentType.from(fileName ?: localPath))
        }.onFailure {
            AppLog.put("打开下载文件${fileName}出错", it)
        }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").trim()

    private fun resolveFileName(raw: String?, url: String, taskType: DownloadTaskType): String {
        val rawName = raw?.takeIf { it.isNotBlank() } ?: ""
        if (rawName.isNotBlank()) {
            return sanitizeFileName(rawName)
        }
        // URL 推断文件名
        val path = url.substringBefore('?').substringBefore('#')
        var last = path.substringAfterLast('/')
        if (last.isBlank() || last.startsWith("#") || last.startsWith("http")) last = "video"
        if (!last.contains('.')) last += ".mp4"
        return sanitizeFileName(last)
    }

    private fun uniqueFile(dir: File, name: String): File {
        dir.mkdirs()
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").takeIf { it.isNotBlank() && it != name }
        var n = 1
        while (f.exists()) {
            val renamed = if (ext == null) "($n)" else "($n).$ext"
            f = File(dir, "$base$renamed")
            n++
        }
        return f
    }

    private fun parseHeaders(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return ChunkDownloader.resolveHeaders()
        return runCatching {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, String>()
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                map[k] = obj.optString(k)
            }
            map
        }.getOrDefault(ChunkDownloader.resolveHeaders())
    }

    override fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setSubText(getString(R.string.action_download))
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setOngoing(true)
            .build()
        startForeground(NotificationId.DownloadService, notification)
    }

    private fun upDownloadNotification(
        notificationId: Int,
        content: String,
        max: Int,
        progress: Int,
        startTime: Long
    ) {
        val builder = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setSubText(getString(R.string.action_download))
            .setContentTitle(content)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                servicePendingIntent<DownloadService>(IntentAction.play, notificationId) {
                    putExtra("downloadId", downloadJobIdFor(notificationId))
                }
            )
            .setDeleteIntent(
                servicePendingIntent<DownloadService>(IntentAction.stop, notificationId) {
                    putExtra("downloadId", downloadJobIdFor(notificationId))
                }
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(groupKey)
            .setWhen(startTime)
        if (max > 0 && progress < max) {
            builder.setProgress(max, progress, false)
        }
        notificationManager.notify(notificationId, builder.build())
    }

    /** notificationId → 任务 id 反向映射（notificationId 不稳定复用，用 index 反查） */
    private fun downloadJobIdFor(notificationId: Int): Long {
        val offset = notificationId - NotificationId.Download
        val ids = downloads.keys.toList()
        return ids.getOrNull(offset) ?: 0L
    }

    private data class DownloadInfo(
        val url: String,
        val fileName: String,
        val notificationId: Int,
        val localPath: String?,
        val taskType: DownloadTaskType,
        val startTime: Long = System.currentTimeMillis()
    )
}