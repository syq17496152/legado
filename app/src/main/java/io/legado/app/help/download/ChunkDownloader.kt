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

/**
 * 直链多线程分片下载（IDM 式）
 *
 * 支持 Range(206) 的地址按并发数切分并行下载到 .partN 临时文件，完成后按序合并；
 * 不支持 Range 时自动降级为单线程整段下载。
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
     * @return 成功返回 true；失败返回 false（调用方负责清理/标记 FAILED）
     */
    suspend fun downloadDirect(
        url: String,
        localFile: File,
        headers: Map<String, String>,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Boolean {
        val (total, range) = probe(url, headers)
        return if (range && total > 0) {
            downloadChunked(url, localFile, headers, total, onProgress)
        } else {
            downloadSingle(url, localFile, headers, onProgress)
        }
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
    ): Boolean = withContext(Dispatchers.IO) {
        localFile.parentFile?.mkdirs()
        val chunkSize = total / DEFAULT_CHUNKS
        val parts = Array(DEFAULT_CHUNKS) { File(localFile.path + ".part$it") }
        val downloaded = AtomicLong(0)
        val semaphore = Semaphore(DEFAULT_CHUNKS)
        try {
            coroutineScope {
                for (i in parts.indices) {
                    async {
                        semaphore.withPermit {
                            val start = i * chunkSize
                            val end = if (i == parts.lastIndex) total - 1 else (i + 1) * chunkSize - 1
                            downloadRange(url, headers, start, end, parts[i]) { read ->
                                val acc = downloaded.addAndGet(read)
                                onProgress(acc, total)
                            }
                        }
                    }
                }
            }
            // 按序合并分片为最终文件
            localFile.outputStream().use { out ->
                for (part in parts) {
                    if (part.exists()) part.inputStream().use { it.copyTo(out) }
                    else throw IOException("分片缺失: ${part.name}")
                }
            }
            parts.forEach { it.delete() }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            if (!localFile.exists()) {
                parts.forEach { it.delete() }
            }
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
            if (resp.code != 206) throw IOException("服务器不支持分段(HTTP ${resp.code})")
            val body = resp.body ?: throw IOException("响应体为空")
            partFile.parentFile?.mkdirs()
            partFile.outputStream().use { out ->
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
    ): Boolean = withContext(Dispatchers.IO) {
        localFile.parentFile?.mkdirs()
        runCatching {
            val request = Request.Builder().url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .get()
                .build()
            var total = 0L
            var downloaded = 0L
            videoStreamClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("响应体为空")
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
            true
        }.getOrElse { e ->
            if (!(e is kotlinx.coroutines.CancellationException)) localFile.delete()
            false
        }
    }
}