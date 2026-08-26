package io.legado.app.service

import io.legado.app.data.appDb
import io.legado.app.data.entities.DownloadTaskEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 下载任务状态（download-manager-maturity）
 *
 * 由 DownloadService 在任务 入队/进度/成功/失败/移除 时写入；
 * Room 为主存（进程被杀/崩溃后任务不丢、可续传），StateFlow 为展示缓存。
 *
 * 时序：
 * - id 由 Room 主键生成，保证持久化索引一致；
 * - 每次状态变更同步 upsert 落库，启动时通过 [resumeFromDb] 恢复未完成任务。
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
    val localPath: String? = null,
    /** 失败错误码（DownloadError 名），成功为 null */
    val errorCode: String? = null,
    /** 下载请求头 JSON（防盗链 Referer/Cookie 等），续传/恢复时复用 */
    val headersJson: String? = null,
    /** 直链断点续传点 JSON（.partN 已下字节），P2 填充 */
    val resumePointJson: String? = null,
    /** m3u8 已下分片序号清单 JSON，P2 填充 */
    val segmentsJson: String? = null
)

object DownloadState {

    @Volatile
    private var taskMap = MutableStateFlow<Map<Long, DownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<Long, DownloadTask>> = taskMap.asStateFlow()

    private val lastBytes = hashMapOf<Long, Long>()
    private val lastTime = hashMapOf<Long, Long>()

    /** 新增任务：先落库获得持久化 id，再回填内存缓存。返回任务 id。 */
    @Synchronized
    fun addTask(
        url: String,
        fileName: String,
        taskType: DownloadTaskType = DownloadTaskType.DIRECT,
        startTime: Long = System.currentTimeMillis(),
        headersJson: String? = null,
        resumePointJson: String? = null,
        segmentsJson: String? = null
    ): Long {
        val entity = DownloadTaskEntity(
            url = url,
            fileName = fileName,
            taskType = taskType.name,
            headersJson = headersJson,
            status = DownloadStatus.WAITING.name,
            startTime = startTime,
            resumePointJson = resumePointJson,
            segmentsJson = segmentsJson
        )
        val id = appDb.downloadTaskDao.insert(entity)
        val task = DownloadTask(
            id = id, url = url, fileName = fileName, startTime = startTime,
            taskType = taskType, status = DownloadStatus.WAITING
        )
        taskMap.value = taskMap.value + (id to task)
        return id
    }

    /** 更新任务（进度/状态/本地路径/错误码），同步落库。 */
    @Synchronized
    fun updateTask(
        id: Long,
        status: DownloadStatus? = null,
        progress: Int? = null,
        totalSize: Int? = null,
        downloadedSize: Int? = null,
        localPath: String? = null,
        errorCode: String? = null
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
        val task = old.copy(
            status = status ?: old.status,
            progress = progress ?: old.progress,
            totalSize = totalSize ?: old.totalSize,
            downloadedSize = newBytes,
            speed = speed,
            localPath = localPath ?: old.localPath,
            errorCode = errorCode ?: old.errorCode
        )
        taskMap.value = taskMap.value + (id to task)
        // 落库（Room 主存）：同步 upsert，保证进程被杀后进度不丢
        appDb.downloadTaskDao.update(
            DownloadTaskEntity(
                id = id, url = task.url, fileName = task.fileName,
                taskType = task.taskType.name,
                headersJson = task.headersJson,
                status = task.status.name,
                progress = task.progress,
                totalSize = task.totalSize.toLong(),
                downloadedSize = task.downloadedSize.toLong(),
                speed = task.speed,
                localPath = task.localPath,
                errorCode = task.errorCode,
                resumePointJson = task.resumePointJson,
                segmentsJson = task.segmentsJson,
                startTime = task.startTime
            )
        )
    }

    @Synchronized
    fun removeTask(id: Long) {
        taskMap.value = taskMap.value - id
        lastBytes.remove(id)
        lastTime.remove(id)
        appDb.downloadTaskDao.delete(id)
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
        appDb.downloadTaskDao.loadAll().forEach { appDb.downloadTaskDao.delete(it.id) }
    }

    @Synchronized
    fun cancelDownload(id: Long) {
        removeTask(id)
    }

    /** 返回当前全部任务（用于管理页轮询 + 过滤） */
    fun queryAllTaskStatus(): List<DownloadTask> = taskMap.value.values.toList()

    /**
     * 启动恢复：从 Room 加载全部任务（含已完成）**合并**进内存缓存。
     * 返回**需要自动续传**的任务 id 列表（内存缺失且原处于 RUNNING/WAITING 的任务），
     * 由调用方（DownloadService.onCreate / 管理页）重新入队调度，避免"正在下载的任务丢失"。
     *
     * 铁证（用户实测两轮 + live_logcat 22:45 会话）：
     * 1. 此前"无条件清空缓存 + 仅恢复未完成任务"导致任务完成后 Service stopSelf、
     *    用户新增任务触发 onCreate 时已完成任务从内存缓存被抹掉；
     * 2. 关键根因：Service 重建/进程被杀后，恢复的任务仅回填内存（RUNNING→PAUSED）
     *    而**不重新调度**，任务停在 PAUSED 从"下载中"消失，用户误以为丢失；
     * 3. 铁证（用户新增任务复测）：管理页打开时无条件用 DB 覆盖内存 taskMap 会把
     *    运行中任务 RUNNING 降级为 PAUSED，若该任务恰好完成，内存 COMPLETED 被 DB
     *    旧值覆盖 → 任务从"完成列表"消失、被重新调度重复下载，表现为"任务丢失"。
     * 现改为**合并而非覆盖**：
     * - 内存已有任务（正在运行/已展示）：保留内存最新状态，绝不降级、不覆盖；
     * - 内存缺失任务（进程重启/Service 重建后）：从 DB 重建补充；
     * - 原 RUNNING/WAITING 且内存缺失 → 重建为 PAUSED 待重新入队（避免重复线程），
     *   并计入返回值自动续传；原 PAUSED 保持暂停（用户主动暂停，不自动续传）。
     */
    @Synchronized
    fun resumeFromDb(): List<Long> {
        val all = appDb.downloadTaskDao.loadAll()
        val existing = taskMap.value
        val rebuilt = mutableMapOf<Long, DownloadTask>()
        val autoResume = mutableListOf<Long>()
        all.forEach { entity ->
            // 内存已存在（Service 运行中 / 管理页已展示）：保留内存状态，不覆盖不降级
            val memTask = existing[entity.id]
            if (memTask != null) {
                rebuilt[memTask.id] = memTask
                return@forEach
            }
            // 内存缺失（进程重启/Service 重建）：从 DB 重建
            val wasActive = entity.status == "RUNNING" || entity.status == "WAITING"
            val task = DownloadTask(
                id = entity.id,
                url = entity.url,
                fileName = entity.fileName,
                startTime = entity.startTime,
                taskType = runCatching { DownloadTaskType.valueOf(entity.taskType) }
                    .getOrDefault(DownloadTaskType.DIRECT),
                // FAILED 保留 FAILED 状态供用户手动重试；RUNNING 恢复为 PAUSED 待重新入队（避免重复线程）
                status = if (entity.status == "RUNNING") DownloadStatus.PAUSED
                else runCatching { DownloadStatus.valueOf(entity.status) }
                    .getOrDefault(DownloadStatus.PAUSED),
                progress = entity.progress,
                totalSize = entity.totalSize.toInt(),
                downloadedSize = entity.downloadedSize.toInt(),
                localPath = entity.localPath,
                errorCode = entity.errorCode,
                headersJson = entity.headersJson,
                resumePointJson = entity.resumePointJson,
                segmentsJson = entity.segmentsJson
            )
            rebuilt[task.id] = task
            if (wasActive) autoResume.add(task.id)
        }
        taskMap.value = rebuilt
        return autoResume
    }
}