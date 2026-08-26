package io.legado.app.help.download

import io.legado.app.help.http.videoStreamClient
import io.legado.app.model.VideoPlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** 直链下载结果（ok=false 时 error 非空） */
data class ChunkResult(val ok: Boolean, val error: DownloadError? = null) {
    companion object {
        val SUCCESS = ChunkResult(true, null)
    }
}

/**
 * 直链多线程分片下载（IDM 式）
 *
 * 支持 Range(206) 的地址按并发数切分并行下载到 .partN 临时文件，完成后按序合并；
 * 不支持 Range 时自动降级为单线程整段下载。
 *
 * 断点续传（download-manager-maturity FR-3）：分片模式以磁盘上 `.partN` 已存长度为续传点，
 * 续传时从该字节推进 Range 起点（追加写）。失败/取消**保留** .partN，供暂停恢复/重试续传，
 * 由调用方（DownloadService）在真删除任务时统一清理临时文件。
 *
 * 错误分类：HTTP 非成功 → HTTP；本地读写 → IO；网络异常 → NETWORK；其余 → 兜底分类。
 * 网络层复用 videoStreamClient（强制 HTTP/1.1），无新增依赖。
 */
object ChunkDownloader {

    const val DEFAULT_CHUNKS = 3
    private const val BUFFER_SIZE = 64 * 1024
    private const val CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    /** 解析下载请求头：优先使用播放时的防盗链头，否则回退默认 UA */
    fun resolveHeaders(): Map<String, String> =
        VideoPlay.currentPlayHeaders ?: mapOf("User-Agent" to CHROME_UA)

    /**
     * 直链下载入口：自动探测并选择分片或单线程模式
     *
     * @return 成功返回 ok=true；失败返回 ok=false + 错误码（调用方负责标记 FAILED/仅删除记录）
     */
    suspend fun downloadDirect(
        url: String,
        localFile: File,
        headers: Map<String, String>,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): ChunkResult {
        val target = localFile
        // 若目标最终文件已存在（上次合并完成但任务未结算），直接视为成功
        if (target.exists() && target.length() > 0) {
            return ChunkResult.SUCCESS
        }
        val (total, range) = probe(url, headers)
        return if (range && total > 0) {
            downloadChunked(url, target, headers, total, onProgress)
        } else {
            downloadSingle(url, target, headers, onProgress)
        }
    }

    private fun classify(e: Throwable, fallbackIo: Boolean): DownloadError = when (e) {
        is DownloadException -> e.code
        is java.net.SocketTimeoutException, is java.net.ConnectException,
        is java.net.UnknownHostException -> DownloadError.NETWORK
        is IOException -> if (fallbackIo) DownloadError.IO else DownloadError.NETWORK
        else -> DownloadError.NETWORK
    }

    private suspend fun probe(url: String, headers: Map<String, String>): Pair<Long, Boolean> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .header("Range", "bytes=0-0")
                .get()
                .build()
            runCatching {
                videoStreamClient.newCall(request).execute().use { resp ->
                    val total = resp.header("Content-Range")
                        ?.substringAfterLast('/')?.toLongOrNull() ?: 0L
                    total to (resp.code == 206 && total > 0)
                }
            }.getOrDefault(0L to false)
        }

    private suspend fun downloadChunked(
        url: String,
        localFile: File,
        headers: Map<String, String>,
        total: Long,
        onProgress: (Long, Long) -> Unit
    ): ChunkResult = withContext(Dispatchers.IO) {
        localFile.parentFile?.mkdirs()
        val chunkSize = total / DEFAULT_CHUNKS
        val parts = Array(DEFAULT_CHUNKS) { File(localFile.path + ".part$it") }
        // 已下字节：暂停/失败续传时 part 保留，以磁盘长度作为续传起点
        val existing = LongArray(DEFAULT_CHUNKS)
        var already = 0L
        for (i in parts.indices) {
            val len = parts[i].length()
            existing[i] = len
            already += len
        }
        val written = AtomicLong(0)
        val semaphore = Semaphore(DEFAULT_CHUNKS)
        try {
            coroutineScope {
                for (i in parts.indices) {
                    val baseStart = i * chunkSize
                    val baseEnd = if (i == parts.lastIndex) total - 1 else (i + 1) * chunkSize - 1
                    val rangeLen = baseEnd - baseStart + 1
                    async {
                        semaphore.withPermit {
                            // 该分片已下满则跳过（续传场景）
                            if (existing[i] >= rangeLen) return@withPermit
                            val start = baseStart + existing[i]
                            downloadRange(url, headers, start, baseEnd, parts[i]) { read ->
                                val acc = written.addAndGet(read)
                                onProgress(already + acc, total)
                            }
                        }
                    }
                }
            }
            // 合并完成校验：所有分片都应存在且非空
            for (part in parts) {
                if (!part.exists() || part.length() == 0L) throw DownloadException(
                    DownloadError.IO, "分片缺失: ${part.name}"
                )
            }
            // 按序合并分片为最终文件
            localFile.outputStream().use { out ->
                for (part in parts) {
                    part.inputStream().use { it.copyTo(out) }
                }
            }
            parts.forEach { it.delete() }
            ChunkResult.SUCCESS
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消（暂停/removeDownload）：保留 parts 供续传，不清理
            throw e
        } catch (e: Exception) {
            // 失败：保留 parts 供下次续传；仅当完全没有可续传数据时才清理
            val totalExisting = parts.sumOf { it.length() }
            if (totalExisting == 0L && !localFile.exists()) {
                parts.forEach { it.delete() }
            }
            ChunkResult(false, classify(e, fallbackIo = true))
        }
    }

    private suspend fun downloadRange(
        url: String,
        headers: Map<String, String>,
        start: Long,
        end: Long,
        partFile: File,
        report: (Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", "bytes=$start-$end")
            .get()
            .build()
        videoStreamClient.newCall(request).execute().use { resp ->
            if (resp.code != 206) throw DownloadException(DownloadError.HTTP, "服务器不支持分段(HTTP ${resp.code})")
            val body = resp.body ?: throw DownloadException(DownloadError.IO, "响应体为空")
            partFile.parentFile?.mkdirs()
            // 追加写：续传时从已有长度继续，不改写已下字节
            java.io.FileOutputStream(partFile, true).use { out ->
                val input = body.byteStream()
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    coroutineContext.ensureActive()
                    out.write(buf, 0, read)
                    report(read.toLong())
                }
            }
        }
    }

    private suspend fun downloadSingle(
        url: String,
        localFile: File,
        headers: Map<String, String>,
        onProgress: (Long, Long) -> Unit
    ): ChunkResult = withContext(Dispatchers.IO) {
        localFile.parentFile?.mkdirs()
        runCatching {
            val request = Request.Builder().url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .get()
                .build()
            var total = 0L
            var downloaded = 0L
            videoStreamClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw DownloadException(DownloadError.HTTP, "HTTP ${resp.code}")
                val body = resp.body ?: throw DownloadException(DownloadError.IO, "响应体为空")
                total = resp.header("Content-Length")?.toLongOrNull()
                    ?: body.contentLength().takeIf { it >= 0 } ?: 0L
                localFile.outputStream().use { out ->
                    val input = body.byteStream()
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        coroutineContext.ensureActive()
                        out.write(buf, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            ChunkResult.SUCCESS
        }.getOrElse { e ->
            if (!(e is kotlinx.coroutines.CancellationException)) localFile.delete()
            ChunkResult(false, classify(e, fallbackIo = false))
        }
    }
}