package io.legado.app.help.download

import io.legado.app.help.http.videoStreamClient
import io.legado.app.model.VideoPlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

/** 直链下载结果（ok=false 时 error 非空） */
data class ChunkResult(val ok: Boolean, val error: DownloadError? = null) {
    companion object {
        val SUCCESS = ChunkResult(true, null)
    }
}

/**
 * 直链下载引擎（IDM 动态文件分段 / DFS，download-manager-optimize 批次E）
 *
 * 支持 Range(206) 且 total>0 的地址进入动态分段模式：
 * - 单文件 `{最终名}.part`，每连接独立 RandomAccessFile 按绝对偏移写入（区间互不重叠）
 * - 内存逻辑分段队列（@Synchronized 快照），初始按 min(连接数, total/最小段长) 均匀切分
 * - 空闲连接认领剩余最大的段：未开始整段认领，进行中则将其剩余区间对半取后半（IDM in-half 规则）
 * - 连接完成免重连直接循环认领（连接复用）
 * - 断点文件 `{最终名}.part.seg`（aria2 控制文件模式）：每 5s + 暂停/失败/终态前强制落盘，
 *   恢复时读 .seg 重建队列；.seg 为进度唯一真源（单文件绝对偏移写入中间可能有洞）
 * - 完成条件：所有段满且 sum==total（段级校验取代旧 .partN 合并前逐片校验），rename 为最终文件并删除 .seg
 *
 * 门禁（E6）：200 响应（不支持 Range）或 206 但 Content-Range 无有效总长（total<=0）一律回退单流且不写 .seg；
 * 切分前置门禁保证 min(maxConnections, total/MIN_SEGMENT_SIZE) >= 1。
 * 单流路径保留 A2 完整性校验（total>0 时 downloaded==total，提前 EOF 报 INCOMPLETE）。
 *
 * A3 续传一致性：expectedTotal（DB totalSize 真源）与 probe total 均>0 且不一致时，清空 .part/.seg/存量 .partN 按新 total 重下。
 * 存量兼容（E6）：存在 .partN 且无有效 .seg → 删全部 .partN 全新下载；存在 .part 但 .seg 缺失/损坏 → 删 .part+.seg 重下。
 * 错误分类不变：HTTP/IO/NETWORK/INCOMPLETE 可重试，ENCRYPT/UNSUPPORTED 永久。
 * 网络层复用 videoStreamClient（强制 HTTP/1.1），无新增依赖。
 */
object ChunkDownloader {

    /** 旧静态分片数量：仅用于存量 .partN 清理兼容，新引擎不再使用 */
    const val DEFAULT_CHUNKS = 3
    private const val MAX_CONNECTIONS = 6
    private const val MIN_SEGMENT_SIZE = 1024L * 1024L
    private const val BUFFER_SIZE = 64 * 1024
    private const val SEG_SAVE_INTERVAL_MS = 5000L
    private const val CLAIM_POLL_MS = 20L
    private const val CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    /** 逻辑分段（三字段均为 Long，>2GB 偏移安全） */
    private class Segment(val start: Long, var end: Long, var downloaded: Long) {
        val remain: Long get() = end - start + 1 - downloaded
    }

    /** 解析下载请求头：优先使用播放时的防盗链头，否则回退默认 UA */
    fun resolveHeaders(): Map<String, String> =
        VideoPlay.currentPlayHeaders ?: mapOf("User-Agent" to CHROME_UA)

    /**
     * 直链下载入口：probe 后按门禁选择动态分段或单流模式
     *
     * @param expectedTotal DB 落库 totalSize 真源（0 视为无记录跳过比对）
     * @return 成功返回 ok=true；失败返回 ok=false + 错误码（调用方负责标记 FAILED/仅删除记录）
     */
    suspend fun downloadDirect(
        url: String,
        localFile: File,
        headers: Map<String, String>,
        expectedTotal: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): ChunkResult {
        val target = localFile
        // 若目标最终文件已存在（上次合并完成但任务未结算），直接视为成功
        if (target.exists() && target.length() > 0) {
            return ChunkResult.SUCCESS
        }
        val (total, range) = probe(url, headers)
        // A3 续传一致性：DB totalSize 与 probe total 不一致 → CDN 内容已变，清空产物重下
        if (range && total > 0) {
            if (expectedTotal > 0 && expectedTotal != total) {
                cleanupArtifacts(target)
            }
            return downloadDynamic(url, target, headers, total, onProgress)
        }
        // E6 门禁：不支持 Range 或 total 未知 → 单流回退
        cleanupArtifacts(target)
        return downloadSingle(url, target, headers, onProgress)
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

    /** 清理全部临时产物：新引擎 .part/.seg + 存量 .partN */
    private fun cleanupArtifacts(target: File) {
        File(target.path + ".part").delete()
        File(target.path + ".part.seg").delete()
        for (i in 0 until DEFAULT_CHUNKS) {
            File(target.path + ".part$i").delete()
        }
    }

    // ---------------- 动态分段（IDM DFS） ----------------

    private suspend fun downloadDynamic(
        url: String,
        target: File,
        headers: Map<String, String>,
        total: Long,
        onProgress: (Long, Long) -> Unit
    ): ChunkResult = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val partFile = File(target.path + ".part")
        val segFile = File(target.path + ".part.seg")
        val lock = Any()
        val segments = ArrayList<Segment>()
        var completedBytes = 0L
        var savedAt = 0L

        /** 已完成字节 + 进行中段进度（同一快照读取点，禁止多连接独立累加） */
        fun snapshotSum(): Long = synchronized(lock) { completedBytes + segments.sumOf { it.downloaded } }

        fun saveSeg(force: Boolean) {
            val now = System.currentTimeMillis()
            if (!force && now - savedAt < SEG_SAVE_INTERVAL_MS) return
            savedAt = now
            runCatching {
                val json = JSONObject().put("total", total)
                val arr = synchronized(lock) {
                    org.json.JSONArray().apply {
                        segments.forEach { s ->
                            put(JSONObject().put("s", s.start).put("e", s.end).put("d", s.downloaded))
                        }
                    }
                }
                json.put("segments", arr)
                val tmp = File(segFile.path + ".tmp")
                tmp.writeText(json.toString())
                if (!tmp.renameTo(segFile)) {
                    tmp.copyTo(segFile, overwrite = true)
                    tmp.delete()
                }
            }
        }

        fun loadSeg(): Boolean {
            if (!segFile.exists()) return false
            return runCatching {
                val json = JSONObject(segFile.readText())
                if (json.optLong("total") != total) return false
                val arr = json.optJSONArray("segments") ?: return false
                val list = ArrayList<Segment>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val s = Segment(o.getLong("s"), o.getLong("e"), o.getLong("d"))
                    if (s.start < 0 || s.end < s.start || s.end >= total || s.downloaded < 0
                        || s.downloaded > s.end - s.start + 1
                    ) return false
                    list.add(s)
                }
                if (list.isEmpty()) return false
                synchronized(lock) {
                    segments.clear()
                    segments.addAll(list)
                }
                true
            }.getOrDefault(false)
        }

        // 断点恢复 / 存量兼容（E6）
        if (!loadSeg()) {
            if (partFile.exists()) partFile.delete()
            for (i in 0 until DEFAULT_CHUNKS) File(target.path + ".part$i").delete()
            // E2 初始切分：门禁保证 total>0；段数 = min(连接数, total/最小段长)，至少 1 段
            val segCount = minOf(MAX_CONNECTIONS.toLong(), total / MIN_SEGMENT_SIZE).toInt()
                .coerceAtLeast(1)
            val base = total / segCount
            synchronized(lock) {
                var start = 0L
                for (i in 0 until segCount) {
                    val end = if (i == segCount - 1) total - 1 else start + base - 1
                    segments.add(Segment(start, end, 0L))
                    start = end + 1
                }
            }
        }

        /** 认领工作：优先整段认领剩余最大者，进行中段剩余对半取后半；无可认领返回 null（调用方区分空队列与等待） */
        fun claim(): Segment? {
            synchronized(lock) {
                if (segments.isEmpty()) return null
                val target1 = segments.maxByOrNull { it.remain } ?: return null
                if (target1.remain < MIN_SEGMENT_SIZE) return null
                return if (target1.downloaded == 0L) {
                    segments.remove(target1)
                    target1
                } else {
                    val half = target1.remain / 2
                    val newSeg = Segment(
                        target1.start + target1.downloaded + half,
                        target1.end,
                        0L
                    )
                    target1.end = target1.start + target1.downloaded + half - 1
                    segments.add(newSeg)
                    newSeg
                }
            }
        }

        fun finishSegment(seg: Segment) {
            synchronized(lock) {
                segments.remove(seg)
                completedBytes += seg.end - seg.start + 1
            }
        }

        try {
            coroutineScope {
                repeat(MAX_CONNECTIONS) {
                    launch(Dispatchers.IO) {
                        var raf: RandomAccessFile? = null
                        try {
                            while (isActive) {
                                val seg = claim()
                                if (seg == null) {
                                    // 段队列空 → 退出；仅剩 <MIN 小段（尾局）→ 等待认领者完成后重试
                                    val empty = synchronized(lock) { segments.isEmpty() }
                                    if (empty) break
                                    delay(CLAIM_POLL_MS)
                                    continue
                                }
                                if (raf == null) {
                                    raf = RandomAccessFile(partFile, "rw")
                                }
                                downloadSegmentRange(url, headers, seg, raf) {
                                    onProgress(snapshotSum(), total)
                                }
                                finishSegment(seg)
                                saveSeg(false)
                            }
                        } finally {
                            runCatching { raf?.close() }
                        }
                    }
                }
            }
            // 段级完整性校验（取代旧 A1 逐片校验）：全部段满且 sum==total
            val sum = snapshotSum()
            if (sum != total) {
                saveSeg(true)
                throw DownloadException(DownloadError.INCOMPLETE, "分段不完整: $sum/$total")
            }
            // 完成：rename 最终文件 + 删除 .seg（防孤儿 sidecar）
            if (!partFile.renameTo(target)) {
                partFile.copyTo(target, overwrite = true)
                partFile.delete()
            }
            segFile.delete()
            ChunkResult.SUCCESS
        } catch (e: CancellationException) {
            // 暂停/取消：强制落盘 .seg 供续传，保留 .part
            saveSeg(true)
            throw e
        } catch (e: Exception) {
            saveSeg(true)
            if (snapshotSum() == 0L) {
                partFile.delete()
                segFile.delete()
            }
            ChunkResult(false, classify(e, fallbackIo = true))
        }
    }

    /** 单段下载：写入绝对偏移区间，EOF 早于区间尾时报 INCOMPLETE（A1 段级校验语义） */
    private suspend fun downloadSegmentRange(
        url: String,
        headers: Map<String, String>,
        seg: Segment,
        raf: RandomAccessFile,
        report: (Long) -> Unit
    ) {
        val segLen = seg.end - seg.start + 1
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", "bytes=${seg.start + seg.downloaded}-${seg.end}")
            .get()
            .build()
        videoStreamClient.newCall(request).execute().use { resp ->
            if (resp.code != 206) throw DownloadException(DownloadError.HTTP, "服务器不支持分段(HTTP ${resp.code})")
            val body = resp.body ?: throw DownloadException(DownloadError.IO, "响应体为空")
            val input = body.byteStream()
            val buf = ByteArray(BUFFER_SIZE)
            raf.seek(seg.start + seg.downloaded)
            var written = seg.downloaded
            while (written < segLen) {
                val read = input.read(buf, 0, minOf(BUFFER_SIZE.toLong(), segLen - written).toInt())
                if (read < 0) throw DownloadException(
                    DownloadError.INCOMPLETE,
                    "分段提前结束: ${seg.start + written}/${seg.end}"
                )
                coroutineContext.ensureActive()
                raf.write(buf, 0, read)
                written += read
                seg.downloaded = written
                report(read.toLong())
            }
        }
    }

    // ---------------- 单流回退（E6，保留 A2 校验 + B2 取消传播） ----------------

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
            // A2 完整性校验：total 已知时提前 EOF 报 INCOMPLETE（可重试）
            if (total > 0 && downloaded < total) {
                throw DownloadException(DownloadError.INCOMPLETE, "下载不完整: $downloaded/$total")
            }
            ChunkResult.SUCCESS
        }.getOrElse { e ->
            // B2：取消必须传播，不得吞为失败
            if (e is CancellationException) throw e
            localFile.delete()
            ChunkResult(false, classify(e, fallbackIo = false))
        }
    }
}
