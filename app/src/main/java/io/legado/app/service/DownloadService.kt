package io.legado.app.service

import android.content.Intent
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.help.download.ChunkDownloader
import io.legado.app.help.download.DownloadError
import io.legado.app.help.download.HlsDownloader
import io.legado.app.help.download.HlsResult
import io.legado.app.help.video.engine.HeaderResolver
import io.legado.app.utils.IntentType
import io.legado.app.utils.getPrefString
import io.legado.app.utils.openFileUri
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import splitties.init.appCtx
import splitties.systemservices.connectivityManager
import splitties.systemservices.notificationManager
import java.io.File

/**
 * 下载文件服务（download-manager-maturity，扩展自 video-download-manager）
 *
 * 自研下载引擎调度，替换原系统 DownloadManager 方案：
 * - 直链：ChunkDownloader IDM 动态文件分段（DFS）单文件下载，断点按 .part/.seg 恢复
 * - m3u8：HlsDownloader 分片下载 + ts 转 mp4，断点续传跳过已下分片
 * - 调度：Semaphore 并发上限（注：kotlinx Semaphore 不保证 FIFO 公平，注释修正于 D6）；暂停/恢复单任务（保留临时文件续传）
 * - 失败：错误码落库 + 指数退避自动重试（ENCRYPT/UNSUPPORTED 等永久错误直接失败）
 * - 通知 id 与任务 id 稳定映射（id.toInt()），杜绝多任务错位
 * - 网络策略：仅 WiFi 下载时在移动网络下挂起、恢复 WiFi 自动继续（无限速）
 * - 状态写入 DownloadState（Room 持久化），前台 Service 保活
 */
class DownloadService : BaseService() {

    companion object {
        const val MAX_CONCURRENT = 3
        const val MAX_AUTO_RETRY = 3
        private const val BASE_RETRY_MS = 3_000L
        private const val KEY_ONLY_WIFI = "downloadOnlyWifi"
        /** 下载目标目录（用户可配置绝对路径，空 = 默认应用内置私有目录，无需权限） */
        const val KEY_TARGET_DIR = "downloadTargetDir"

        /** 默认内置私有下载目录：无需任何存储权限即可写入（应用专属），默认位置 */
        fun defaultPublicDir(): File =
            File(
                appCtx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appCtx.filesDir,
                "Legado"
            )

        /** 目标根目录：用户配置优先；未配置时默认内置私有目录（无需授权） */
        fun configuredTargetDir(): File {
            val configured = appCtx.getPrefString(KEY_TARGET_DIR)
            return if (configured.isNullOrBlank()) defaultPublicDir() else File(configured)
        }

        /** 下载目录是否落在公有存储区需授权：仅当用户显式填了公有路径才需要（默认私有无需授权） */
        fun targetDirNeedsPermission(): Boolean {
            val configured = appCtx.getPrefString(KEY_TARGET_DIR)
            if (configured.isNullOrBlank()) return false
            val publicParent = Environment.getExternalStorageDirectory().absolutePath
            return File(configured).absolutePath.startsWith(publicParent)
        }

        /** 分版本存储权限判定：Android 11+ 需 MANAGE_EXTERNAL_STORAGE；8-9 需 WRITE_EXTERNAL_STORAGE；Android 10 无 File 公有写能力 */
        fun storageWriteGranted(): Boolean = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> false
            else -> ContextCompat.checkSelfPermission(
                appCtx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

        /** B5 磁盘预检阈值：HLS 分片目录与目标目录双份空间需求（经验值，防写满产生坏文件） */
        private const val HLS_MIN_FREE_BYTES = 200L * 1024 * 1024
        private const val DIRECT_MARGIN_BYTES = 32L * 1024 * 1024

        /** 目标基础目录（静态版，实例 resolveTargetDir 与 UI 清理共用）：权限回退逻辑一致 */
        fun resolveBaseDir(ctx: android.content.Context): File {
            val base = if (targetDirNeedsPermission() && !storageWriteGranted()) {
                File(
                    ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir,
                    "Legado"
                )
            } else {
                configuredTargetDir()
            }
            return base
        }

        /** B5 磁盘空间预检：可用空间不足返回 false（探测失败视为通过，不误伤） */
        fun ensureDiskSpace(dir: File, needBytes: Long): Boolean =
            runCatching { dir.usableSpace > needBytes }.getOrDefault(true)

        /**
         * 清理任务本地产物（实例 deleteLocalFiles 与管理页 C3/C4 清除记录共用入口）：
         * 最终文件 + DIRECT 的 .part/.seg/存量 .partN + HLS 分片缓存目录
         */
        fun deleteTaskFiles(
            ctx: android.content.Context,
            taskType: DownloadTaskType,
            localPath: String?,
            fileName: String,
            taskId: Long
        ) {
            runCatching {
                val base = resolveBaseDir(ctx)
                val dir = if (taskType == DownloadTaskType.HLS) File(base, "m3u8") else base
                if (localPath != null) {
                    File(localPath).delete()
                    if (taskType == DownloadTaskType.DIRECT) {
                        File("$localPath.part").delete()
                        File("$localPath.part.seg").delete()
                        for (i in 0 until ChunkDownloader.DEFAULT_CHUNKS) {
                            File("$localPath.part$i").delete()
                        }
                    }
                } else {
                    File(dir, fileName).delete()
                    if (taskType == DownloadTaskType.DIRECT) {
                        File(dir, "$fileName.part").delete()
                        File(dir, "$fileName.part.seg").delete()
                        for (i in 0 until ChunkDownloader.DEFAULT_CHUNKS) {
                            File(dir, "$fileName.part$i").delete()
                        }
                    }
                }
                if (taskType == DownloadTaskType.HLS) {
                    File(ctx.cacheDir, "video_download_$taskId").deleteRecursively()
                }
            }
        }
    }

    private val groupKey = "${appCtx.packageName}.download"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(MAX_CONCURRENT)
    private val runJobs = hashMapOf<Long, Job>()
    private val downloadInfos = hashMapOf<Long, DownloadInfo>()

    override fun onCreate() {
        super.onCreate()
        // 启动恢复：从 Room 重建内存缓存，并把崩溃/重建前正在跑的任务重新入队自动续传。
        // 铁证（live_logcat 22:45 会话）：此前仅回填内存不重新调度，恢复的任务停在 PAUSED
        // 从"下载中"消失，用户误以为"正在下载的任务丢失"。
        resumeAllFromDb()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> {
                val url = intent.getStringExtra("url")
                    ?: return super.onStartCommand(intent, flags, startId)
                startDownload(
                    url,
                    intent.getStringExtra("fileName"),
                    intent.getStringExtra("taskType"),
                    intent.getStringExtra("headers"),
                    intent.getBooleanExtra("autoStart", true),
                    intent.getIntExtra("retry", MAX_AUTO_RETRY)
                )
            }

            IntentAction.play -> {
                val id = intent.getLongExtra("downloadId", 0)
                val task = DownloadState.tasks.value[id]
                if (task?.localPath != null) {
                    openDownload(task.localPath, task.fileName)
                } else {
                    toastOnUi(getString(R.string.download_unfinished_tip))
                }
            }

            IntentAction.stop -> {
                removeDownload(intent.getLongExtra("downloadId", 0))
            }

            IntentAction.pause -> {
                pauseDownload(intent.getLongExtra("downloadId", 0))
            }

            IntentAction.resume -> {
                resumeDownload(intent.getLongExtra("downloadId", 0))
            }

            IntentAction.resumeAll -> {
                // 管理页打开/进程重启后触发：自动续传所有未完成任务
                resumeAllFromDb()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        // 取消所有下载协程：防止旧实例 scope 的"幽灵协程"在 Service 重建后继续写同一文件、
        // 覆盖新实例对任务状态的控制（铁证：旧 scope 不 cancel，重建后 RUNNING 任务被覆盖成
        // PAUSED 时旧线程仍在跑，状态错乱）。取消后未完成任务保持 RUNNING 落库，下次恢复续传。
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 从 Room 恢复全部任务并自动续传未完成任务（崩溃/Service 重建/管理页打开时调用）。
     * resumeFromDb 返回需自动续传的任务 id；schedule 幂等（已调度的 id 直接跳过），
     * 避免 onCreate 与 resumeAll 动作重复调度产生重复下载线程。
     *
     * 除了内存缺失任务（autoResume），还必须把【内存中 RUNNING/WAITING 但无活跃 job】的任务
     * 一并重新调度：Service 重建时 onDestroy 已 scope.cancel() 取消所有下载协程，但任务状态
     * 停在 RUNNING/WAITING 不消失；若仅按"内存已有→保留"逻辑，这些任务永远不再被调度，
     * 表现为"正在下载的任务卡死 / 新增任务后原任务丢失"（铁证：用户实测新增任务后正在下载
     * 的任务消失）。
     */
    @Synchronized
    private fun resumeAllFromDb() {
        val autoResume = DownloadState.resumeFromDb()
        val activeIds = runJobs.keys.toSet()
        val toSchedule = LinkedHashSet<Long>()
        toSchedule.addAll(autoResume)
        DownloadState.queryAllTaskStatus().forEach { task ->
            if (task.id !in activeIds &&
                (task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.WAITING)
            ) {
                toSchedule.add(task.id)
            }
        }
        toSchedule.forEach { id ->
            val entity = appDb.downloadTaskDao.loadById(id) ?: return@forEach
            val taskType = runCatching { DownloadTaskType.valueOf(entity.taskType) }
                .getOrDefault(DownloadTaskType.DIRECT)
            // B11 注记：恢复路径 DownloadInfo 未持久化 maxRetry，重建后回落默认 MAX_AUTO_RETRY；
            // 实际语义为"重新获得完整自动重试预算"，对用户表现为恢复后重试次数重置，属可接受行为
            downloadInfos[id] = DownloadInfo(
                entity.url, entity.fileName, taskType, parseHeaders(entity.headersJson)
            )
            DownloadState.updateTask(id, DownloadStatus.WAITING)
            schedule(id)
        }
    }

    @Synchronized
    private fun startDownload(
        url: String,
        rawFileName: String?,
        taskTypeStr: String?,
        headersJson: String?,
        autoStart: Boolean = true,
        maxRetry: Int = MAX_AUTO_RETRY
    ) {
        // 防重复：同一 url 且未完成的任务已存在则提示
        val exists = DownloadState.queryAllTaskStatus().any {
            it.url == url && (it.status == DownloadStatus.RUNNING ||
                it.status == DownloadStatus.WAITING || it.status == DownloadStatus.PAUSED)
        }
        if (exists) {
            toastOnUi(R.string.download_already_in_list)
            return
        }
        val taskType = taskTypeStr?.let { runCatching { DownloadTaskType.valueOf(it) }.getOrNull() }
            ?: if (url.contains(".m3u8", ignoreCase = true)) DownloadTaskType.HLS else DownloadTaskType.DIRECT
        val headers = parseHeaders(headersJson)
        val fileName = resolveFileName(rawFileName, url, taskType)
        // id 由 Room 主键生成：进程被杀后可恢复续传，且作为通知 id 稳定映射
        // Phase 3 头持久化收口：任务创建时以 toJsonHeaders 落库最终解析头（恢复/续传直接还原完整头，不再依赖播放现场遗留头）
        val id = DownloadState.addTask(url, fileName, taskType = taskType, headersJson = HeaderResolver.toJsonHeaders(headers))
        downloadInfos[id] = DownloadInfo(
            url, fileName, taskType, headers, maxRetry = maxRetry.coerceAtLeast(0)
        )
        // autoStart=false：仅入队持久化，不自动开始，供后续手动恢复
        if (!autoStart) {
            DownloadState.updateTask(id, DownloadStatus.PAUSED)
            maybeStopSelf()
            return
        }
        schedule(id)
        toastOnUi(R.string.download_started)
    }

    /** 入队调度：Semaphore 并发控制，超出上限则 waiting 排队 */
    @Synchronized
    private fun schedule(id: Long) {
        if (runJobs[id]?.isActive == true) return
        val job = scope.launch {
            semaphore.withPermit {
                runTask(id)
            }
        }
        runJobs[id] = job
        job.invokeOnCompletion {
            synchronized(runJobs) { runJobs.remove(id) }
            maybeStopSelf()
        }
    }

    /** 单任务执行循环：网络策略等待 + 下载 + 退避重试 */
    private suspend fun runTask(id: Long) {
        val info = downloadInfos[id] ?: return
        var attempt = 0
        while (runJobs[id]?.isActive == true) {
            // 存储权限门禁（FR-11 公有下载目录）：目标为公有目录但无对应版本写入权限 → 挂起并引导授权
            // 避免无权限直接写导致任务失败，授权后自动继续
            if (targetDirNeedsPermission() && !storageWriteGranted()) {
                DownloadState.updateTask(id, DownloadStatus.PAUSED)
                maybeWarnStoragePermission()
                delay(3_000)
                continue
            }
            // 网络策略（FR-9）：仅 WiFi 开启且当前非 WiFi → 挂起轮询，恢复 WiFi 自动继续
            if (onlyWifiEnabled() && !isWifiNetwork()) {
                DownloadState.updateTask(id, DownloadStatus.PAUSED)
                delay(2_000)
                continue
            }
            DownloadState.updateTask(id, DownloadStatus.RUNNING)
            val attemptResult = try {
                executeAttempt(id, info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("下载执行异常 ${info.fileName}", e)
                DownloadAttempt(success = false, error = DownloadError.IO)
            }
            if (attemptResult.success) {
                handleSuccess(id, info, attemptResult.path ?: "", attemptResult.totalSize)
                return
            }
            val error = attemptResult.error ?: DownloadError.NETWORK
            // 永久性错误直接判失败，不重试
            if (error == DownloadError.ENCRYPT || error == DownloadError.UNSUPPORTED) {
                handleFail(id, info, error)
                return
            }
            if (attempt >= info.maxRetry) {
                handleFail(id, info, error)
                return
            }
            attempt++
            // 退避等待（指数），期间展示 WAITING，避免与 RUNNING/暂停混淆
            DownloadState.updateTask(id, DownloadStatus.WAITING)
            delay(BASE_RETRY_MS * (1L shl attempt))
        }
    }

    private suspend fun executeAttempt(id: Long, info: DownloadInfo): DownloadAttempt {
        val headers = info.headers
        return when (info.taskType) {
            DownloadTaskType.DIRECT -> executeDirect(id, info, headers)
            DownloadTaskType.HLS -> executeHls(id, info, headers)
        }
    }

    private suspend fun executeDirect(
        id: Long,
        info: DownloadInfo,
        headers: Map<String, String>
    ): DownloadAttempt {
        val dir = resolveTargetDir(DownloadTaskType.DIRECT)
        // B5 磁盘空间预检：已知预期体积（DB totalSize 真源）时，可用空间不足直接失败可重试
        val expectedTotal = runCatching { appDb.downloadTaskDao.loadById(id)?.totalSize ?: 0L }
            .getOrDefault(0L)
        if (expectedTotal > 0 && !ensureDiskSpace(dir, expectedTotal + DIRECT_MARGIN_BYTES)) {
            AppLog.put("磁盘空间不足，暂停下载 ${info.fileName}")
            return DownloadAttempt(success = false, error = DownloadError.IO)
        }
        val localFile = uniqueFile(dir, info.fileName)
        // R-4：attempt 起始即落库最终路径（含 "(n)" 变体），删除清理全程可靠；
        // .part/.seg 为其派生名，rename 前该路径不存在属预期（C1 存在性校验兜底）
        downloadInfos[id] = info.copy(localFile = localFile.path)
        DownloadState.updateTask(
            id, DownloadStatus.RUNNING, localPath = localFile.path, targetDir = dir.path
        )
        val result = ChunkDownloader.downloadDirect(
            info.url, localFile, headers, expectedTotal
        ) { done, total ->
            updateProgress(id, info.fileName, done, total)
        }
        if (!result.ok) {
            return DownloadAttempt(success = false, error = result.error ?: DownloadError.NETWORK)
        }
        // B9：体积全程 Long
        val size = localFile.length()
        // 完整性校验（FR-10）：直链下载成功但产物为空 → 判 INCOMPLETE 可重试
        if (size <= 0) {
            return DownloadAttempt(success = false, error = DownloadError.INCOMPLETE)
        }
        return DownloadAttempt(true, localFile.path, size)
    }

    private suspend fun executeHls(
        id: Long,
        info: DownloadInfo,
        headers: Map<String, String>
    ): DownloadAttempt {
        val mp4Name = if (info.fileName.endsWith(".mp4", ignoreCase = true)) info.fileName
        else "${info.fileName}.mp4"
        val mp4File = uniqueFile(resolveTargetDir(DownloadTaskType.HLS), mp4Name)
        downloadInfos[id] = info.copy(localFile = mp4File.path)
        // B5 磁盘空间预检：HLS 分片目录（cache）与目标目录双份空间需求
        if (!ensureDiskSpace(cacheDir, HLS_MIN_FREE_BYTES) ||
            !ensureDiskSpace(mp4File.parentFile ?: cacheDir, HLS_MIN_FREE_BYTES)
        ) {
            AppLog.put("磁盘空间不足，暂停 HLS 下载 ${info.fileName}")
            return DownloadAttempt(success = false, error = DownloadError.IO)
        }
        // R-4：attempt 起始即落库最终路径，删除清理全程可靠（与 executeDirect 同策略）
        DownloadState.updateTask(
            id, DownloadStatus.RUNNING, localPath = mp4File.path,
            targetDir = mp4File.parent
        )
        val tempDir = File(cacheDir, "video_download_$id")
        var mergedTsPath: String? = null
        val result = HlsDownloader.download(
            info.url, mp4File, tempDir, headers,
            onProgress = { done, total ->
                val pct = if (total > 0) (done * 100f / total).toInt() else 0
                DownloadState.updateTask(
                    id, status = DownloadStatus.RUNNING, progress = pct,
                    totalSize = total, downloadedSize = done
                )
            },
            // 分片合并为完整 ts 后立即落库"完成"（产物先指向 ts），再尝试 mp4 转码增强。
            // 即便转码触发 native 崩溃杀进程（Java 层捕获不住的场景），任务也已在完成列表且 ts 完整可播。
            onMerged = { tsFile ->
                val targetTs = uniqueFile(
                    resolveTargetDir(DownloadTaskType.HLS),
                    mp4File.nameWithoutExtension + ".ts"
                )
                tsFile.copyTo(targetTs, overwrite = true)
                mergedTsPath = targetTs.path
                val size = targetTs.length()
                DownloadState.updateTask(
                    id, DownloadStatus.COMPLETED, progress = 100,
                    totalSize = size, downloadedSize = size,
                    localPath = targetTs.path
                )
                downloadInfos[id] = info.copy(localFile = targetTs.path)
            }
        )
        return when (result) {
            is HlsResult.Mp4 -> {
                // mp4 转码成功：清理 onMerged 时落位的 ts 副本，避免目标目录残留重复文件
                mergedTsPath?.let { runCatching { File(it).delete() } }
                DownloadAttempt(
                    true, mp4File.path,
                    mp4File.length()
                )
            }
            is HlsResult.TsFallback -> {
                // onMerged 已把 ts 落位目标目录并落库完成，这里直接复用；兜底自行复制
                val targetTs = mergedTsPath ?: run {
                    val ts = File(tempDir, mp4File.nameWithoutExtension + ".ts")
                    val dst = uniqueFile(
                        resolveTargetDir(DownloadTaskType.HLS),
                        mp4File.nameWithoutExtension + ".ts"
                    )
                    ts.copyTo(dst, overwrite = true)
                    dst.path
                }
                DownloadAttempt(
                    true, targetTs,
                    File(targetTs).length()
                )
            }
            is HlsResult.UnsupportedCrypto -> DownloadAttempt(
                success = false, error = DownloadError.ENCRYPT
            )
            is HlsResult.Failed -> DownloadAttempt(
                success = false, error = result.error ?: DownloadError.NETWORK
            )
        }
    }

    private fun handleSuccess(id: Long, info: DownloadInfo, localPath: String, size: Long) {
        // B3：成功态显式清空历史 errorCode（失败→重试成功后不残留误导性错误码）
        DownloadState.updateTask(
            id, DownloadStatus.COMPLETED, progress = 100, totalSize = size,
            downloadedSize = size, localPath = localPath, clearError = true
        )
        upDownloadNotification(
            id, "${info.fileName} ${getString(R.string.download_success)}",
            size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        )
        maybeStopSelf()
    }

    private fun handleFail(id: Long, info: DownloadInfo, error: DownloadError) {
        DownloadState.updateTask(id, DownloadStatus.FAILED, errorCode = error.name)
        upDownloadNotification(
            id, "${info.fileName} ${getString(R.string.download_error)}", 1, 0
        )
        toastOnUi(getString(R.string.download_fail_tip))
        maybeStopSelf()
    }

    /** 暂停单任务：取消协程（Chunk/Hls 保留临时文件供续传），置 PAUSED */
    @Synchronized
    private fun pauseDownload(id: Long) {
        runJobs.remove(id)?.cancel()
        DownloadState.updateTask(id, DownloadStatus.PAUSED)
        maybeStopSelf()
    }

    /** 恢复单任务：从持久化任务重新入队续传 */
    @Synchronized
    private fun resumeDownload(id: Long) {
        val entity = appDb.downloadTaskDao.loadById(id) ?: return
        val taskType = runCatching { DownloadTaskType.valueOf(entity.taskType) }
            .getOrDefault(DownloadTaskType.DIRECT)
        downloadInfos[id] = DownloadInfo(
            entity.url, entity.fileName, taskType, parseHeaders(entity.headersJson)
        )
        DownloadState.updateTask(id, DownloadStatus.WAITING)
        schedule(id)
    }

    @Synchronized
    private fun removeDownload(downloadId: Long) {
        runJobs.remove(downloadId)?.cancel()
        // A4 孤儿治理：Service 重建后内存 downloadInfos 已清空，此时删除任务必须从 DB 实体
        // 重建产物信息再清理文件，否则只删记录留下孤儿文件（铁证：重建后删除任务文件残留）
        val info = downloadInfos.remove(downloadId) ?: appDb.downloadTaskDao.loadById(downloadId)?.let { entity ->
            val taskType = runCatching { DownloadTaskType.valueOf(entity.taskType) }
                .getOrDefault(DownloadTaskType.DIRECT)
            DownloadInfo(
                entity.url, entity.fileName, taskType, parseHeaders(entity.headersJson),
                localFile = entity.localPath
            )
        }
        info?.let { deleteLocalFiles(downloadId, it) }
        DownloadState.removeTask(downloadId)
        notificationManager.cancel(downloadId.toInt())
        maybeStopSelf()
    }

    /** 清理任务关联的本地产物：最终文件 + .part/.seg/存量 .partN + HLS 分片目录（1A.7 批次E 产物兼容） */
    private fun deleteLocalFiles(downloadId: Long, info: DownloadInfo) {
        deleteTaskFiles(
            this, info.taskType, info.localFile, info.fileName, downloadId
        )
    }

    // region D5 划掉最近任务不中断下载

    override val stopSelfOnTaskRemoved: Boolean = false

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // D5：划掉最近任务不再 stopSelf（任务已落库，进程存活则继续下载）；
        // 但队列空闲时正常退场，避免空闲前台服务+通知常驻
        maybeStopSelf()
    }

    // endregion

    @Synchronized
    private fun maybeStopSelf() {
        val hasRunning = runJobs.values.any { it.isActive } ||
            DownloadState.queryAllTaskStatus().any {
                it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.WAITING
            }
        if (!hasRunning) stopSelf()
    }

    private fun onlyWifiEnabled(): Boolean = appCtx.getPrefString(KEY_ONLY_WIFI) == "1"

    private fun isWifiNetwork(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val nc = connectivityManager.getNetworkCapabilities(network) ?: return false
        return nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun parseHeaders(json: String?): Map<String, String> {
        // Phase 3 收口：委托 HeaderResolver.fromJsonHeaders（非法/缺失返回空 map → 降级 ChunkDownloader 现状兜底，语义等价）
        val parsed = HeaderResolver.fromJsonHeaders(json)
        if (parsed.isNotEmpty()) return parsed
        return ChunkDownloader.resolveHeaders()
    }

    /** B9：进度全程 Long，去除 >2GB 截断 */
    @Synchronized
    private fun updateProgress(id: Long, fileName: String, done: Long, total: Long) {
        val pct = if (total > 0) (done * 100f / total).toInt() else 0
        DownloadState.updateTask(
            id, status = DownloadStatus.RUNNING, progress = pct,
            totalSize = total, downloadedSize = done
        )
        // 通知进度节流：仅整 5% 更新，降低省电（通知进度条仍为 Int 口径）
        if (pct % 5 == 0 || (done >= total && total > 0)) {
            upDownloadNotification(
                id, "${fileName} ${getString(R.string.downloading)}",
                total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                done.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
        }
    }

    /** 目标根目录：用户配置优先，否则默认公有 Downloads/Legado（FR-11） */
    private fun configuredTargetDir(): File = DownloadService.configuredTargetDir()

    /** 目标目录是否落在公有存储区（需存储权限才能 File 写入） */
    private fun targetDirNeedsPermission(): Boolean = DownloadService.targetDirNeedsPermission()

    /** 分版本存储权限判定：Android 11+ 需 MANAGE_EXTERNAL_STORAGE；8-9 需 WRITE_EXTERNAL_STORAGE；Android 10 无 File 公有写能力 */
    private fun storageWriteGranted(): Boolean = DownloadService.storageWriteGranted()

    /** 无存储权限时按去重逻辑提示，引导用户在管理页授权 */
    private var lastPermWarnTs = 0L
    private fun maybeWarnStoragePermission() {
        val now = System.currentTimeMillis()
        if (now - lastPermWarnTs < 30_000) return
        lastPermWarnTs = now
        toastOnUi(getString(R.string.download_need_storage_permission))
    }

    private fun resolveTargetDir(taskType: DownloadTaskType): File {
        // 默认内置私有目录时无需任何权限，保证下载必然成功（稳定性优先）；
        // 仅当用户显式配置了公有路径且未授权时，回落到应用私有外部目录，避免写入失败。
        val dir = if (taskType == DownloadTaskType.HLS) File(resolveBaseDir(this), "m3u8") else resolveBaseDir(this)
        if (!dir.exists()) {
            runCatching { dir.mkdirs() }
        }
        return dir
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

    /** 通知 id 与任务 id 稳定映射：notificationId = id.toInt() */
    private fun upDownloadNotification(taskId: Long, content: String, max: Int, progress: Int) {
        val notificationId = taskId.toInt()
        val builder = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setSubText(getString(R.string.action_download))
            .setContentTitle(content)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                servicePendingIntent<DownloadService>(IntentAction.play, notificationId) {
                    putExtra("downloadId", taskId)
                }
            )
            .setDeleteIntent(
                servicePendingIntent<DownloadService>(IntentAction.stop, notificationId) {
                    putExtra("downloadId", taskId)
                }
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(groupKey)
        if (max > 0 && progress < max) {
            builder.setProgress(max, progress, false)
        }
        notificationManager.notify(notificationId, builder.build())
    }

    /** B9：产物体积改 Long（>2GB 正确） */
    private data class DownloadAttempt(
        val success: Boolean,
        val path: String? = null,
        val totalSize: Long = 0L,
        val error: DownloadError? = null
    )

    private data class DownloadInfo(
        val url: String,
        val fileName: String,
        val taskType: DownloadTaskType,
        val headers: Map<String, String> = emptyMap(),
        val localFile: String? = null,
        val maxRetry: Int = MAX_AUTO_RETRY
    )
}