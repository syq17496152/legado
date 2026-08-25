package io.legado.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 下载任务状态（video-download-manager：自研下载引擎后的纯内存任务状态源）
 *
 * 由 DownloadService 在任务 入队/进度/成功/失败/移除 时写入；
 * 进程重启后内存清空（任务不持久化，避免与系统 DownloadManager 耦合）。
 */
enum class DownloadStatus {
    WAITING, RUNNING, PAUSED, COMPLETED, FAILED
}

enum class DownloadTaskType {
    /** 直链 mp4/mkv/flv 等 */
    DIRECT,
    /** m3u8/HLS 流 */
    HLS
}

data class DownloadTask(
    val id: Long,
    val url: String,
    val fileName: String,
    val startTime: Long,
    val taskType: DownloadTaskType = DownloadTaskType.DIRECT,
    val status: DownloadStatus = DownloadStatus.WAITING,
    val progress: Int = 0,
    val totalSize: Int = 0,
    val downloadedSize: Int = 0,
    val speed: Long = 0,
    /** 完成后的本地文件绝对路径 */
    val localPath: String? = null
)

object DownloadState {
    private val taskMap = MutableStateFlow<Map<Long, DownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<Long, DownloadTask>> = taskMap.asStateFlow()

    private val lastBytes = hashMapOf<Long, Long>()
    private val lastTime = hashMapOf<Long, Long>()

    @Synchronized
    fun addTask(
        id: Long,
        url: String,
        fileName: String,
        startTime: Long = System.currentTimeMillis(),
        taskType: DownloadTaskType = DownloadTaskType.DIRECT
    ) {
        taskMap.value = taskMap.value + (id to DownloadTask(id, url, fileName, startTime, taskType))
    }

    @Synchronized
    fun updateTask(
        id: Long,
        status: DownloadStatus? = null,
        progress: Int? = null,
        totalSize: Int? = null,
        downloadedSize: Int? = null,
        localPath: String? = null
    ) {
        val old = taskMap.value[id] ?: return
        val newBytes = downloadedSize ?: old.downloadedSize
        val now = System.currentTimeMillis()
        val prevBytes = lastBytes[id] ?: 0L
        val prevTime = lastTime[id] ?: now
        val elapsed = now - prevTime
        val speed = if (elapsed > 0 && newBytes >= prevBytes) {
            (newBytes - prevBytes) * 1000L / elapsed
        } else {
            old.speed
        }
        lastBytes[id] = newBytes.toLong()
        lastTime[id] = now
        taskMap.value = taskMap.value + (id to old.copy(
            status = status ?: old.status,
            progress = progress ?: old.progress,
            totalSize = totalSize ?: old.totalSize,
            downloadedSize = newBytes,
            speed = speed,
            localPath = localPath ?: old.localPath
        ))
    }

    @Synchronized
    fun removeTask(id: Long) {
        taskMap.value = taskMap.value - id
        lastBytes.remove(id)
        lastTime.remove(id)
    }

    @Synchronized
    fun clearTask(id: Long) {
        removeTask(id)
    }

    @Synchronized
    fun clear() {
        taskMap.value = emptyMap()
        lastBytes.clear()
        lastTime.clear()
    }

    @Synchronized
    fun cancelDownload(id: Long) {
        removeTask(id)
    }

    /** 返回当前全部任务（用于管理页轮询 + 过滤） */
    fun queryAllTaskStatus(): List<DownloadTask> = taskMap.value.values.toList()
}