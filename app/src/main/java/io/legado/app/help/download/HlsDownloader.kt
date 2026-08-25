package io.legado.app.help.download

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import io.legado.app.help.http.videoStreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** m3u8 下载结果 */
sealed class HlsResult {
    /** 已成功重封装为 mp4 */
    object Mp4 : HlsResult()

    /** 转 mp4 失败，回退保留合并后的 .ts */
    object TsFallback : HlsResult()

    /** 加密流（AES-128/DRM）不支持 */
    object UnsupportedCrypto : HlsResult()

    /** 下载/合并失败 */
    object Failed : HlsResult()
}

/**
 * m3u8/HLS 下载器：解析清单 → 并发下载 ts 分片 → 合并为单个 .ts → 平台重封装为 mp4。
 *
 * 转换失败时保留 .ts，保证"下载成功不丢数据"。加密(AES-128)流直接拒绝。
 */
object HlsDownloader {

    private const val SEG_CONCURRENCY = 4
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * 下载 m3u8 并尝试转 mp4
     *
     * @param outputMp4 目标 mp4 文件（转换成功时产出）
     * @param tempDir   分片/临时 ts 存放目录（完成后清理）
     */
    suspend fun download(
        m3u8Url: String,
        outputMp4: File,
        tempDir: File,
        headers: Map<String, String>,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): HlsResult {
        return runCatching {
            var playlist = fetch(m3u8Url, headers)
            var mediaUrl = m3u8Url

            // master 清单：选择 BANDWIDTH 最高的主码流
            if (playlist.contains("#EXT-X-STREAM-INF")) {
                val variant = pickBestVariant(playlist)
                    ?: throw IOException("master 清单无法解析码流")
                mediaUrl = resolve(mediaUrl, variant)
                playlist = fetch(mediaUrl, headers)
            }

            if (playlist.contains("#EXT-X-KEY:")) {
                return HlsResult.UnsupportedCrypto
            }

            val segments = parseSegments(playlist)
            if (segments.isEmpty()) throw IOException("未解析到分片")

            tempDir.mkdirs()
            val segFiles = mutableListOf<File>()
            val segUrls = segments.map { resolve(mediaUrl, it) }
            // 进度回调用"字节"而非"分片数"表示：累计每个分片实际下载字节数，
            // 用已完成分片的平均大小估算总字节数（下载进行中无法预先知道总大小）。
            val doneBytes = AtomicLong(0)
            val doneCount = AtomicInteger(0)
            val totalSegments = segments.size
            val semaphore = Semaphore(SEG_CONCURRENCY)

            coroutineScope {
                for (i in segUrls.indices) {
                    val segFile = File(tempDir, String.format("seg_%05d.ts", i))
                    segFiles.add(segFile)
                    async {
                        semaphore.withPermit {
                            downloadSegment(segUrls[i], headers, segFile)
                            val segSize = segFile.length()
                            val accumulated = doneBytes.addAndGet(segSize)
                            val count = doneCount.incrementAndGet()
                            val avg = if (count > 0) accumulated / count else 0L
                            val estimatedTotal = avg * totalSegments
                            onProgress(accumulated, estimatedTotal)
                        }
                    }
                }
            }

            // 合并分片为单个 .ts（按文件名序 seg_00000~seg_N，保证顺序正确）。
            // 必须用同一个输出流 + 追加写入：若在 forEach 内每次重新打开 outputStream()，
            // 默认覆盖模式会把前面已合并的分片清掉，最终 ts 只含最后一个分片，转出的 mp4 会严重缩水。
            val tsFile = File(tempDir, outputMp4.nameWithoutExtension + ".ts")
            tsFile.outputStream().use { out ->
                tempDir.listFiles()
                    ?.filter { it.name.startsWith("seg_") }
                    ?.sortedBy { it.name }
                    ?.forEach { f ->
                        f.inputStream().use { it.copyTo(out) }
                    }
            }
            tsFileLenCheck(tsFile)

            // ts → mp4 重封装
            val ok = TsToMp4Remuxer.remux(tsFile, outputMp4)
            // 无论如何清理分片（网络/临时产物）；根据转换结果决定是否保留 ts
            segFiles.forEach { it.delete() }
            if (ok) {
                tsFile.delete()
                HlsResult.Mp4
            } else {
                HlsResult.TsFallback
            }
        }.getOrElse { _ -> HlsResult.Failed }
    }

    private fun tsFileLenCheck(f: File) {
        if (!f.exists() || f.length() == 0L) throw IOException("合并后的 ts 为空")
    }

    private suspend fun fetch(url: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .get()
                .build()
            videoStreamClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                resp.body?.string() ?: throw IOException("清单体为空")
            }
        }

    private suspend fun downloadSegment(
        url: String,
        headers: Map<String, String>,
        destFile: File
    ) = withContext(Dispatchers.IO) {
        destFile.parentFile?.mkdirs()
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()
        videoStreamClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.byteStream()?.use { input ->
                destFile.outputStream().use { out ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        coroutineContext.ensureActive()
                        out.write(buf, 0, read)
                    }
                }
            }
        }
    }

    private fun pickBestVariant(playlist: String): String? {
        val uriRegex = """^[^#].*""".toRegex()
        val lines = playlist.lineSequence().toList()
        var bestUri: String? = null
        var bestBandwidth = -1L
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)
                    ?.toLongOrNull() ?: 0L
                // 下一行是非 # 开头的码流 URI
                var j = i + 1
                var candidate: String? = null
                while (j < lines.size && lines[j].trim().startsWith("#")) j++
                if (j < lines.size) candidate = lines[j].trim()
                uriRegex.matchEntire(candidate ?: "")?.let {
                    if (bw > bestBandwidth || bestUri == null) {
                        bestBandwidth = bw
                        bestUri = candidate
                    }
                }
                i = j
            } else {
                i++
            }
        }
        return bestUri
    }

    private fun parseSegments(playlist: String): List<String> {
        val result = mutableListOf<String>()
        val lines = playlist.lineSequence().toList()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF")) {
                var j = i + 1
                while (j < lines.size && lines[j].trim().startsWith("#")) j++
                if (j < lines.size) {
                    val uri = lines[j].trim()
                    if (uri.isNotEmpty() && !uri.startsWith("#")) result.add(uri)
                }
                i = j
            } else {
                i++
            }
        }
        return result
    }

    private fun resolve(base: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return runCatching { URI(base).resolve(path).toString() }
            .getOrDefault(trimToBase(base) + path)
    }

    private fun trimToBase(url: String): String {
        val idx = url.lastIndexOf('/')
        return if (idx >= 0) url.substring(0, idx + 1) else url
    }

    /**
     * ts → mp4 重封装（仅 remux，不转码）
     *
     * 用平台 MediaExtractor 解封装 + MediaMuxer 重封装。为规避 HLS 分片 PTS 每片重启，
     * 对每一条音/视频轨做 PTS 单调递增矫正。
     */
    @SuppressLint("NewApi")
    object TsToMp4Remuxer {
        fun remux(tsFile: File, mp4File: File): Boolean {
            return runCatching {
                mp4File.delete()
                val extractor = MediaExtractor()
                var muxer: MediaMuxer? = null
                var started = false
                try {
                    extractor.setDataSource(tsFile.path)
                    muxer = MediaMuxer(mp4File.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                    val muxTrack = mutableMapOf<Int, Int>()
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        if (mime.startsWith("video/")) {
                            // 视频轨必须有完整的 codec-specific data（SPS/PPS/AAC 头）。
                            // MediaMuxer.addTrack 对 csd 过小/缺失可能触发 native SIGABRT（实测：MP4WtrVidTrkThr Fatal signal 6），
                            // 该崩溃 Java 层捕不住会直接杀进程，导致已在下载的任务随之丢失。此处提前校验并回退 ts。
                            val csd0 = format.getByteBuffer("csd-0")
                            val csd1 = format.getByteBuffer("csd-1")
                            val hasCsd = (csd0 != null && csd0.remaining() >= 4) ||
                                (csd1 != null && csd1.remaining() >= 4)
                            if (!hasCsd) return@runCatching false
                        }
                        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                            muxTrack[i] = muxer.addTrack(format)
                        }
                    }
                    if (muxTrack.isEmpty()) throw IOException("无可用音视频轨道")

                    for ((src, dst) in muxTrack) {
                        extractor.selectTrack(src)
                        var lastTime = Long.MIN_VALUE
                        var shift = 0L
                        val buffer: ByteBuffer = ByteBuffer.allocate(1 shl 20)
                        val info = MediaCodec.BufferInfo()
                        while (true) {
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) break
                            var pts = extractor.sampleTime
                            if (lastTime != Long.MIN_VALUE && pts < lastTime) {
                                shift += lastTime - pts
                            }
                            pts += shift
                            lastTime = pts
                            val key = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            info.set(0, size, pts, key)
                            if (!started) {
                                muxer.start()
                                started = true
                            }
                            muxer.writeSampleData(dst, buffer, info)
                            extractor.advance()
                        }
                        extractor.unselectTrack(src)
                    }
                    mp4File.exists() && mp4File.length() > 0
                } finally {
                    extractor.release()
                    if (started) runCatching { muxer?.stop() }
                    muxer?.release()
                }
            }.getOrDefault(false)
        }
    }
}