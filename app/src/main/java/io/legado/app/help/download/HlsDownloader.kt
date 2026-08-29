package io.legado.app.help.download

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import io.legado.app.constant.AppLog
import io.legado.app.help.http.videoStreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** m3u8 下载结果 */
sealed class HlsResult {
    /** 已成功重封装为 mp4 */
    object Mp4 : HlsResult()

    /** 转 mp4 失败，回退保留合并后的 .ts */
    object TsFallback : HlsResult()

    /** 非 AES-128 加密流（DRM/SAMPLE-AES 等）不支持（AES-128 已支持解密下载） */
    object UnsupportedCrypto : HlsResult()

    /** 下载/合并失败（携带错误码） */
    class Failed(val error: DownloadError) : HlsResult()
}

/**
 * m3u8/HLS 下载器：解析清单 → 并发下载 ts 分片（AES-128 解密）→ 合并为单个 .ts → 平台重封装为 mp4。
 *
 * 转换失败时保留 .ts，保证"下载成功不丢数据"。AES-128 加密支持解密（与播放器能力对齐），仅 DRM 拒绝。
 */
object HlsDownloader {

    private const val SEG_CONCURRENCY = 4

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
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onMerged: (tsFile: File) -> Unit = {}
    ): HlsResult {
        return runCatching {
            // 断点续传（用户2026-08-27反馈"杀进程重启后点继续直接下载失败"）：
            // 1) 每次 attempt 都会重新拉取 m3u8 清单，很多视频站的 m3u8 URL 带时效签名，
            //    进程被杀/重启后 URL 已失效（fetch 404/403）→ 整个任务失败。
            // 2) 因此首次下载成功后把【媒体清单文本 + 实际媒体 URL】保存到 tempDir；
            //    续传时若主清单拉取失败，回退使用已保存清单继续下载分片（分片路径通常无签名）。
            tempDir.mkdirs()
            val manifestFile = File(tempDir, "manifest_media.m3u8")
            val mediaUrlFile = File(tempDir, "manifest_media_url.txt")
            // 清理残留 .tmp（上次进程被强杀时正在写的半截分片）：不清理会干扰后续原子写
            tempDir.listFiles()
                ?.filter { it.name.endsWith(".tmp") }
                ?.forEach { it.delete() }

            val primaryFetch = runCatching {
                var p = fetch(m3u8Url, headers)
                var m = m3u8Url
                // master 清单：选择 BANDWIDTH 最高的主码流
                if (p.contains("#EXT-X-STREAM-INF")) {
                    val variant = pickBestVariant(p)
                        ?: throw IOException("master 清单无法解析码流")
                    m = resolve(m, variant)
                    p = fetch(m, headers)
                }
                p to m
            }
            val playlist: String
            val mediaUrl: String
            if (primaryFetch.isSuccess) {
                val (p, m) = primaryFetch.getOrThrow()
                playlist = p
                mediaUrl = m
                // 刷新保存的清单：续传成功后下次回退用最新内容
                runCatching { manifestFile.writeText(playlist) }
                runCatching { mediaUrlFile.writeText(mediaUrl) }
            } else {
                // 主清单拉取失败：回退到上次保存的清单（断点续传场景）
                val savedUrl = mediaUrlFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
                val savedManifest = manifestFile.takeIf { it.exists() }?.readText().orEmpty()
                val primaryErr = primaryFetch.exceptionOrNull()
                if (savedUrl.isNotBlank() && savedManifest.isNotBlank()) {
                    AppLog.put(
                        "Hls 主清单拉取失败(${primaryErr?.message?.take(60)}), " +
                            "回退已保存清单续传 urlPath=${savedUrl.take(8)}***"
                    )
                    mediaUrl = savedUrl
                    playlist = savedManifest
                } else {
                    throw (primaryErr ?: IOException("清单拉取失败"))
                }
            }

            // 加密流处理：AES-128（标准 HLS 加密）与播放器能力对齐——播放器能播的分片下载也支持解密；
            // 仅非 AES-128（DRM 等）才拒绝（与内置播放器能力保持一致，避免"能看不能下"的反常体验）。
            val crypto = parseCrypto(mediaUrl, playlist, headers)
            if (crypto is Crypto.Unsupported) return HlsResult.UnsupportedCrypto

            // 清单含 EXT-X-DISCONTINUITY（广告/多段不同编码拼接）：MediaExtractor 单轨连续模型
            // 在编码参数切换点会把后续样本视为新轨/结束当前轨，remux 会提前截断（铁证：用户反馈
            // "带广告视频下载后 mp4 只播放前面十几秒广告"）。命中则直接保留完整 ts，跳过 mp4 重封装。
            val hasDiscontinuity = playlist.contains("#EXT-X-DISCONTINUITY")
            val (segments, totalDurationMs) = parseSegments(playlist)
            if (segments.isEmpty()) throw IOException("未解析到分片")

            val segFiles = mutableListOf<File>()
            val segUrls = segments.map { resolve(mediaUrl, it) }
            // 进度回调用"字节"而非"分片数"表示：累计每个分片实际下载字节数，
            // 用已完成分片的平均大小估算总字节数（下载进行中无法预先知道总大小）。
            // 断点续传：tempDir 中已存在的 seg_*.ts（进程被杀/暂停残留）按"已下"跳过并计入已有字节。
            // 只统计正式分片（seg_*.ts），排除 .tmp 半截文件：半截分片续传时会重新下载，避免合并出损坏 ts。
            val totalSegments = segments.size
            fun existingSegFiles(): List<File> =
                tempDir.listFiles()?.filter { it.name.startsWith("seg_") && it.name.endsWith(".ts") } ?: emptyList()
            val existingBytes = existingSegFiles().sumOf { it.length() }
            val doneBytes = AtomicLong(existingBytes)
            val doneCount = AtomicInteger(existingSegFiles().size)
            val semaphore = Semaphore(SEG_CONCURRENCY)

            coroutineScope {
                for (i in segUrls.indices) {
                    val segFile = File(tempDir, String.format("seg_%05d.ts", i))
                    segFiles.add(segFile)
                    async {
                        semaphore.withPermit {
                            // 已下分片（长度>0）跳过，进度已计入 existingBytes
                            if (segFile.exists() && segFile.length() > 0) return@withPermit
                            downloadSegment(segUrls[i], headers, crypto, i, segFile)
                            val segSize = segFile.length()
                            doneBytes.addAndGet(segSize)
                            doneCount.incrementAndGet()
                            val count = doneCount.get()
                            val accumulated = doneBytes.get()
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

            // 分片合并成功即"下载实质完成"：先回调让上层立即落库 COMPLETED（产物指向完整 ts），
            // 再尝试 mp4 转码增强。即便转码触发 native 崩溃杀进程，用户也保有完成记录 + 完整 ts。
            onMerged(tsFile)

            // 广告/多段拼接流（EXT-X-DISCONTINUITY）：remux 单轨模型必然截断，直接保留完整 ts
            if (hasDiscontinuity) {
                AppLog.put("Hls 清单含 EXT-X-DISCONTINUITY（广告/编码切换），保留完整 ts，跳过 mp4 重封装")
                segFiles.forEach { it.delete() }
                return HlsResult.TsFallback
            }

            // ts → mp4 重封装（csd 严格校验不满足时跳过，保留 ts）
            val ok = TsToMp4Remuxer.remux(tsFile, outputMp4, expectedDurationMs = totalDurationMs)
            // 无论如何清理分片（网络/临时产物）；根据转换结果决定是否保留 ts
            segFiles.forEach { it.delete() }
            if (ok) {
                tsFile.delete()
                HlsResult.Mp4
            } else {
                HlsResult.TsFallback
            }
        }.getOrElse { e ->
            // 取消（暂停/删除）：重新抛出保留协程取消语义，绝不当失败处理
            if (e is kotlinx.coroutines.CancellationException) throw e
            HlsResult.Failed(when (e) {
                is DownloadException -> e.code
                is java.net.SocketTimeoutException, is java.net.ConnectException,
                is java.net.UnknownHostException -> DownloadError.NETWORK
                else -> DownloadError.NETWORK
            })
        }
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
        crypto: Crypto,
        segIndex: Int,
        destFile: File
    ) = withContext(Dispatchers.IO) {
        destFile.parentFile?.mkdirs()
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()
        videoStreamClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw DownloadException(DownloadError.HTTP, "分片 HTTP ${resp.code}")
            val body = resp.body ?: throw DownloadException(DownloadError.IO, "响应体为空")
            val raw = body.bytes()
            // AES-128 加密分片：下载原始密文后解密写入；明文分片直接落盘
            val data = if (crypto is Crypto.Aes128) crypto.decrypt(raw, segIndex) else raw
            // 原子写：先写 .tmp 临时文件，完整后再 rename 为正式分片。
            // 修复（用户2026-08-27 反馈断点续传失败）：进程被强杀时正在写的分片会留半截，
            // 若直接写正式文件名，续传时 exists && length>0 会把它当成"已下分片"跳过，
            // 合并的 ts 损坏（转 mp4 失败/内容残缺）。.tmp 残留不会被当分片，续传时重新下载。
            val tmpFile = File(destFile.parentFile, destFile.name + ".tmp")
            tmpFile.outputStream().use { out -> out.write(data) }
            if (!tmpFile.renameTo(destFile)) {
                // rename 失败（跨目录/被占用）：退化为复制
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }
        }
    }

    private sealed class Crypto {
        /** 无加密 */
        object None : Crypto()

        /** 非 AES-128（DRM 等，拒绝） */
        object Unsupported : Crypto()

        /** AES-128：key 已加载，按给定 IV（缺省用媒体序号 = MEDIA-SEQUENCE 起点 + 分片序号）CBC 解密 */
        class Aes128(
            private val key: ByteArray,
            private val explicitIv: ByteArray?,
            // B4：缺省 IV 必须用媒体序号（#EXT-X-MEDIA-SEQUENCE 起点 + 分片索引），
            // 而非清单内索引；非 0 起始清单按清单索引解密会得到乱码
            private val mediaSequenceBase: Long
        ) : Crypto() {

            /** AES-128-CBC 解密单个分片；IV 缺省时按 HLS 规范取媒体序号（大端 16 字节） */
            fun decrypt(cipherBytes: ByteArray, segIndex: Int): ByteArray {
                val iv = explicitIv ?: ByteArray(16).also { arr ->
                    var v = mediaSequenceBase + segIndex
                    for (i in 15 downTo 0) { arr[i] = (v and 0xFF).toByte(); v = v ushr 8 }
                }
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                return cipher.doFinal(cipherBytes)
            }
        }
    }

    /**
     * 解析清单中的加密信息（#EXT-X-KEY）。
     *
     * 仅支持标准 AES-128（METHOD=AES-128，key 为明文 URL）；显式 METHOD=NONE 视为明文；
     * 其余（SAMPLE-AES/DRM 等）判定 Unsupported 并记日志——与内置播放器（ExoPlayer）能力对齐：能播的分片即能下。
     */
    private suspend fun parseCrypto(
        mediaUrl: String,
        playlist: String,
        headers: Map<String, String>
    ): Crypto {
        val keyLine = playlist.lineSequence().firstOrNull { it.trim().startsWith("#EXT-X-KEY:") }
            ?: return Crypto.None
        // METHOD 值兼容带引号形式：METHOD="AES-128" 也是合法清单写法
        val method = Regex("""METHOD\s*=\s*"?([^",\s]+)""").find(keyLine)?.groupValues?.get(1)
            ?.uppercase()?.removeSurrounding("\"")
        if (method == null || method == "NONE") return Crypto.None
        if (method != "AES-128") {
            // SAMPLE-AES/DRM 等：播放器内部可解但不支持下载解密，记录方法名便于真机定位
            AppLog.put("Hls 加密方法不支持: METHOD=$method")
            return Crypto.Unsupported
        }
        val uri = Regex("""URI\s*=\s*"([^"]+)"""").find(keyLine)?.groupValues?.get(1)
            ?: return Crypto.Unsupported
        val keyUrl = resolve(mediaUrl, uri)
        val ivStr = Regex("""IV\s*=\s*0x([0-9a-fA-F]{32})""").find(keyLine)?.groupValues?.get(1)
        val iv = ivStr?.let { hexStr ->
            hexStr.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        // key 请求透传源站 headers（Referer/UA 等可能校验），避免 key 拉取被拒
        val keyBytes = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(keyUrl)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .get()
                .build()
            videoStreamClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("key HTTP ${resp.code}")
                resp.body?.bytes() ?: throw IOException("key 体为空")
            }
        }
        if (keyBytes.size != 16) throw IOException("AES-128 key 长度异常: ${keyBytes.size}")
        // B4：解析媒体序号起点（缺省 0），供缺省 IV = MEDIA-SEQUENCE + 分片索引
        val mediaSequenceBase = Regex("""#EXT-X-MEDIA-SEQUENCE:\s*(\d+)""")
            .find(playlist)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        AppLog.put(
            "Hls 加密流: METHOD=AES-128, IV=${if (iv != null) "显式" else "媒体序号($mediaSequenceBase)"}"
        )
        return Crypto.Aes128(keyBytes, iv, mediaSequenceBase)
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
                // B10：剥离 AVERAGE-BANDWIDTH 干扰（否则 find() 会命中其子串取错码流）
                val bw = Regex("""(?<![A-Z-])BANDWIDTH=(\d+)""")
                    .find(line.replace("AVERAGE-BANDWIDTH", "X-BW"))?.groupValues?.get(1)
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

    /**
     * 解析媒体清单中的分片（#EXTINF 后跟的 URI）并累加 EXTINF 声明的总时长（毫秒）。
     *
     * 总时长用于 ts→mp4 重封装后的完整性校验（B 方案）：MediaExtractor 单轨模型在
     * 编码参数切换点可能提前截断，mp4 实际时长会显著小于清单声明的总时长，据此回退保留 ts。
     */
    private fun parseSegments(playlist: String): Pair<List<String>, Long> {
        val result = mutableListOf<String>()
        var totalMs = 0L
        val lines = playlist.lineSequence().toList()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF")) {
                // #EXTINF:10.000, 或 #EXTINF:10,title，取冒号后逗号前的秒数
                Regex("""#EXTINF:\s*([0-9.]+)""").find(line)?.groupValues?.get(1)
                    ?.toDoubleOrNull()?.let { totalMs += (it * 1000).toLong() }
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
        return result to totalMs
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
     *
     * TS 容器 csd 预热：MediaExtractor 解析 MPEG-TS 时，视频轨 csd-0（SPS/PPS）需要
     * 推进到关键帧后才会由解析器注入；若未推进样本就 getTrackFormat，正常流也会拿到
     * 空/残缺 csd 而被误判为坏流回退 ts（铁证：真实下载产物全部只剩 .ts 无 .mp4）。
     * 因此先选中视频轨读到关键帧，再 seekTo(0) 从头正式重封装。
     */
    @SuppressLint("NewApi")
    object TsToMp4Remuxer {
        /**
         * 视频轨 csd-0 最小合法字节数（H264 SPS+PPS 约 20+ 字节，低于此值视为残缺流）
         */
        private const val MIN_VIDEO_CSD_BYTES = 24

        /** mp4 实际时长低于清单声明总时长的此比例时判定重封装不完整（MediaExtractor 单轨模型截断） */
        private const val DURATION_COVER_RATIO = 0.9

        fun remux(tsFile: File, mp4File: File, expectedDurationMs: Long = 0): Boolean {
            return runCatching {
                mp4File.delete()
                val extractor = MediaExtractor()
                var muxer: MediaMuxer? = null
                var started = false
                try {
                    extractor.setDataSource(tsFile.path)
                    Log.d("HlsRemux", "ts=${tsFile.length()} tracks=${extractor.trackCount}")
                    muxer = MediaMuxer(mp4File.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                    // —— csd 预热：推进视频轨样本，让 TS 解析器注入完整 SPS/PPS，并【保存】完整 csd ——
                    // 铁证1：此前"读到第一个关键帧就停"的预热，对部分流（SPS/PPS 不在首关键帧内）
                    // 仍拿不到完整 csd-0 → 误判坏流回退 ts（用户实测"下载后还是 ts"）。
                    // 铁证2（本次修复关键）：预热只设 warmed=true 不保存 csd，随后 unselectTrack +
                    // seekTo(0) 会**重置 TS 解析器状态**，正式 addTrack 时 getTrackFormat 拿到的
                    // csd-0 又被清空 → csdOk 校验失败 → 全部回退 TsFallback（用户实测"还是没转成 mp4"）。
                    // 现把预热到的完整 csd duplicate 保存，addTrack 时用拷贝 format 显式注入。
                    // 注意：getTrackFormat 返回同一 MediaFormat 实例，csd-0 随解析器推进实时更新。
                    var savedVideoCsd: ByteBuffer? = null
                    for (i in 0 until extractor.trackCount) {
                        val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                        if (!mime.startsWith("video/")) continue
                        extractor.selectTrack(i)
                        // 预热前定位到轨道开头：TS 解析器须从首个样本起推进，SPS/PPS 才被注入 csd-0
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        val probeBuf = ByteBuffer.allocate(1 shl 20)
                        var probe = 0
                        var warmed = false
                        while (probe < 500) {
                            val size = extractor.readSampleData(probeBuf, 0)
                            if (size < 0) break
                            extractor.advance()
                            probe++
                            val fmt = extractor.getTrackFormat(i)
                            val csd0 = fmt.getByteBuffer("csd-0")
                            if (csd0 != null && csd0.remaining() >= MIN_VIDEO_CSD_BYTES && hasPayload(csd0)) {
                                // 深拷贝而非 duplicate：duplicate 与原始 csd-0 共享底层字节数组，
                                // 后续 selectTrack + seekTo(0) 重置 TS 解析器可能**原地清空**该数组，
                                // 导致"保存的完整 csd"随之被清空 → addTrack 注入无效 → 转码仍回退 ts
                                //（铁证：用户实测"下载后 ts 还是没转成 mp4"）。
                                val arr = ByteArray(csd0.remaining())
                                val dup = csd0.duplicate()
                                dup.get(arr)
                                savedVideoCsd = ByteBuffer.wrap(arr)
                                warmed = true
                                break
                            }
                        }
                        extractor.unselectTrack(i)
                        Log.d("HlsRemux", "warmup track=$i mime=$mime probe=$probe warmed=$warmed csd=${savedVideoCsd?.remaining()}B")
                        break
                    }

                    val muxTrack = mutableMapOf<Int, Int>()
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        var csdDbg = "csd=null"
                        var trackFormat = format
                        if (mime.startsWith("video/")) {
                            if (savedVideoCsd != null) {
                                // 用预热保存的完整 csd 覆盖原生 format 的 csd-0（seekTo 后可能被清空）。
                                // getTrackFormat 返回同一 MediaFormat 实例，直接 setByteBuffer 即可注入。
                                format.setByteBuffer("csd-0", savedVideoCsd)
                                trackFormat = format
                                csdDbg = "csd=${savedVideoCsd.remaining()}B"
                            } else {
                                // 预热 500 样本内仍拿不到完整 csd：按原生 format 校验，残缺则回退 ts
                                val csd0 = format.getByteBuffer("csd-0")
                                csdDbg = if (csd0 == null) "csd=null" else "csd=${csd0.remaining()}B"
                                val csdOk = csd0 != null && csd0.remaining() >= MIN_VIDEO_CSD_BYTES &&
                                    hasPayload(csd0)
                                if (!csdOk) return@runCatching false
                            }
                        } else {
                            Log.d("HlsRemux", "other track=$i mime=$mime $csdDbg")
                        }
                        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                            muxTrack[i] = muxer.addTrack(trackFormat)
                        }
                    }
                    if (muxTrack.isEmpty()) throw IOException("无可用音视频轨道")
                    Log.d("HlsRemux", "mux tracks=$muxTrack")

                    for ((src, dst) in muxTrack) {
                        extractor.selectTrack(src)
                        // 关键：每轨写入前必须 seekTo(0) 重新定位。MediaExtractor 样本游标全局共享，
                        // 前一轨道读到末尾后 selectTrack 切换轨道不会自动回到该轨道开头，直接
                        // readSampleData 会返回 -1（无样本）→ 视频轨 0 字节 → mp4 只有音频/极小甚至失败。
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
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

                    // B 方案：重封装完整性校验——mp4 实际时长须覆盖清单声明的总时长（比例下限）。
                    // 否则判定 MediaExtractor 在编码参数切换点（广告/多段拼接）提前截断，
                    // 返回 false 让上层回退保留完整 ts，避免"mp4 只播前面十几秒广告"。
                    if (expectedDurationMs > 0) {
                        val actual = mp4DurationMs(mp4File)
                        if (actual > 0 && actual < expectedDurationMs * DURATION_COVER_RATIO) {
                            Log.d(
                                "HlsRemux",
                                "mp4 duration too short: actual=${actual}ms expected=${expectedDurationMs}ms, fallback ts"
                            )
                            return@runCatching false
                        }
                        Log.d(
                            "HlsRemux",
                            "mp4 duration ok: actual=${actual}ms expected=${expectedDurationMs}ms"
                        )
                    }
                    mp4File.exists() && mp4File.length() > 0
                } finally {
                    extractor.release()
                    if (started) runCatching { muxer?.stop() }
                    muxer?.release()
                }
            }.onFailure { Log.d("HlsRemux", "remux exception: ${it.message}", it) }
                .getOrDefault(false)
        }

        /** csd-0 数据段是否含非零 payload（全零视为无有效 NAL 数据的残缺流） */
        private fun hasPayload(csd: ByteBuffer): Boolean {
            val dup = csd.duplicate()
            var i = 0
            while (i < dup.remaining() && i < 32) {
                if (dup.get() != 0.toByte()) return true
                i++
            }
            return false
        }

        /** 读取 mp4 容器时长（毫秒）；非 mp4（如 ts）或读取失败返回 0 */
        private fun mp4DurationMs(f: File): Long {
            return runCatching {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(f.path)
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                } finally {
                    mmr.release()
                }
            }.getOrDefault(0L)
        }
    }
}