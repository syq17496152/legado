package io.legado.app.help.exoplayer

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.google.gson.reflect.TypeToken
import io.legado.app.constant.AppLog
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.VideoPlay
import io.legado.app.utils.GSON
import io.legado.app.utils.externalCache
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import okhttp3.CacheControl
import okhttp3.Request
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.TimeUnit


@Suppress("unused")
@SuppressLint("UnsafeOptInUsageError")
object ExoPlayerHelper {

    private const val SPLIT_TAG = "\uD83D\uDEA7"

    /**
     * R4-T6: 浏览器 User-Agent（模拟 Chrome 120 移动版）
     *
     * 替换原 `Util.getUserAgent(context, "Legado")` 生成的 `Legado/1.0 (Linux; U; Android 13)`，
     * 部分站点 CDN 拒绝非浏览器 UA（403/401），改用浏览器 UA 提升抓取成功率。
     */
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val mapType by lazy {
        object : TypeToken<Map<String, String>>() {}.type
    }

    /**
     * R4-T5: 嗅探结果数据类（7 维度交叉验证产物）
     *
     * @property contentType ExoPlayer 内容类型（C.TYPE_HLS / C.TYPE_DASH / C.TYPE_SS / C.TYPE_OTHER / TYPE_UNKNOWN）
     * @property mimeType 嗅探得到的 MIME 类型（可能为 null，表示未识别）
     * @property moovPosition MP4 moov box 位置（仅 MP4 有效，其他格式为 UNKNOWN）
     * @property supportsRange 是否支持 Range 请求（Accept-Ranges: bytes）
     * @property finalUrl 重定向后的最终 URL（未重定向则等于原 URL）
     */
    data class SniffResult(
        val contentType: Int,
        val mimeType: String?,
        val moovPosition: MoovPosition = MoovPosition.UNKNOWN,
        val supportsRange: Boolean = false,
        val finalUrl: String = ""
    ) {
        companion object {
            /** 未知内容类型，进入降级链 */
            const val TYPE_UNKNOWN = -1

            /** 嗅探失败时的占位结果 */
            val UNKNOWN = SniffResult(TYPE_UNKNOWN, null)
        }
    }

    /**
     * R4-T4: 按嗅探结果智能选择 MediaSource（对齐浏览器五层架构）
     *
     * 核心改造：从"一律用 ProgressiveMediaSource"改为"按 contentType 分发 HLS/DASH/SS/Progressive"。
     * 这是"为什么有的能播有的播不了"的根本原因——HLS/DASH 不走 Extractor.sniff 路径，
     * 必须显式选择对应 MediaSource 才能正确解析 m3u8/mpd 清单。
     *
     * @param sniff 嗅探结果（来自 [sniffVideoType]）
     * @param url 视频 URL（优先用重定向后的 finalUrl）
     * @param dataSourceFactory 数据源工厂
     * @return 对应类型的 MediaSource
     * @throws IllegalArgumentException 当 contentType 为 UNKNOWN 时抛出，触发降级链
     */
    fun createMediaSource(
        sniff: SniffResult,
        url: String,
        dataSourceFactory: DataSource.Factory
    ): MediaSource {
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .apply { sniff.mimeType?.let { setMimeType(it) } }
            .build()
        return when (sniff.contentType) {
            C.TYPE_HLS -> HlsMediaSource.Factory(dataSourceFactory)
                // R4-T10: AES-128 加密流由 ExoPlayer 内置支持
                // dataSourceFactory 已注入 Referer/Cookie/UA 防盗链头，
                // ExoPlayer 内部会用此 factory 获取 #EXT-X-KEY 标签的密钥
                .createMediaSource(mediaItem)
            C.TYPE_DASH -> DashMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
            C.TYPE_SS -> SsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
            C.TYPE_OTHER -> ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
            else -> throw IllegalArgumentException(
                "Sniff failed, contentType=UNKNOWN, url=${sanitizeUrl(url)}"
            )
        }
    }

    /**
     * R4-T5: 7 维度交叉验证嗅探视频类型（对齐浏览器五层架构）
     *
     * 7 维度：
     * 1. Content-Type 提示（弱信号）
     * 2. 最终 URL 后缀提示（弱信号，重定向后）
     * 3. 初始 URL 后缀提示（弱信号）
     * 4. Magic Number 匹配（强信号，17 项完整签名表）
     * 5. 主动 Probe 清单内容（强信号，HLS/DASH）
     * 6. MP4 moov 位置检测（FRONT/BACK/UNKNOWN）
     * 7. Accept-Ranges 检测（断点续传支持）
     *
     * 优先级：强信号（4/5）> 弱信号（1/2/3）> 兜底（返回 UNKNOWN）
     *
     * @param url 视频 URL（完整，含 query）
     * @param headers 请求头（如 Referer/User-Agent/Cookie）
     * @return SniffResult 含 contentType + mimeType + moovPosition + supportsRange + finalUrl
     */
    suspend fun sniffVideoType(url: String, headers: Map<String, String>): SniffResult {
        val startTime = System.currentTimeMillis()
        return withTimeoutOrNull(SNIFF_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                sniffWithRangeRequestR4(url, headers)
            }
        } ?: run {
            val elapsed = System.currentTimeMillis() - startTime
            AppLog.putDebug("sniffVideoType: timeout (${elapsed}ms), urlPath=${sanitizeUrl(url)}")
            SniffResult.UNKNOWN
        }
    }

    /**
     * R4-T5+T9: Range 请求 + 7 维度交叉验证 + 重定向感知
     *
     * 关键改造：
     * - Range 从 1KB 提升到 8KB（[MimeSniffer.SNIFF_LENGTH]）
     * - 跟随重定向，记录最终 URL（finalUrl）
     * - 7 维度交叉验证返回 SniffResult
     */
    private suspend fun sniffWithRangeRequestR4(url: String, headers: Map<String, String>): SniffResult {
        val startTime = System.currentTimeMillis()
        return try {
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-${MimeSniffer.SNIFF_LENGTH - 1}")
                .header("Accept", "video/*, application/x-mpegURL, application/dash+xml, */*")
                .header("User-Agent", BROWSER_UA)
            headers.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }
            val request = requestBuilder.build()

            okHttpClient.newCall(request).execute().use { response ->
                // T1.4: execute() 返回后检查 isActive，协程取消时立即返回 UNKNOWN（解决 Bug-3）
                // 注意：execute() 本身无法中断，isActive 检查只能在其返回后生效
                // use 块内 this 变为 Response，需用 coroutineContext.isActive 显式访问协程上下文
                if (!coroutineContext.isActive) {
                    AppLog.putDebug("sniffWithRangeRequestR4 cancelled after execute, urlPath=${sanitizeUrl(url)}")
                    return@use SniffResult.UNKNOWN
                }
                if (!response.isSuccessful && response.code != 206 && response.code != 200) {
                    AppLog.putDebug("sniffVideoType: non-200 response: code=${response.code}, urlPath=${sanitizeUrl(url)}")
                    return@use SniffResult.UNKNOWN
                }

                val finalUrl = response.request.url.toString()
                val contentType = response.header("Content-Type")
                val acceptRanges = response.header("Accept-Ranges")
                val supportsRange = acceptRanges?.equals("bytes", ignoreCase = true) == true

                // 维度1: Content-Type 提示（弱信号）
                val hintByCt = parseContentTypeToContentType(contentType)

                // 维度2: 最终 URL 后缀提示（弱信号，重定向后）
                val hintByFinalExt = inferContentTypeByExtension(finalUrl)

                // 维度3: 初始 URL 后缀提示（弱信号）
                val hintByInitExt = inferContentTypeByExtension(url)

                // L3: magic number 检测（强信号，读 body 前 8KB）
                val body = response.body ?: run {
                    AppLog.putDebug("sniffVideoType: empty body, urlPath=${sanitizeUrl(url)}")
                    return@use SniffResult.UNKNOWN
                }
                val bodyBytes = readLimitedBytes(body, MimeSniffer.SNIFF_LENGTH)

                // 维度4: Magic Number 匹配（强信号）
                val magicMime = MimeSniffer.sniff(bodyBytes)

                // 维度5: 主动 Probe 清单内容（强信号，HLS/DASH）
                val probedContentType = when {
                    MimeSniffer.isReallyM3u8(bodyBytes) -> C.TYPE_HLS
                    MimeSniffer.isReallyMpd(bodyBytes) -> C.TYPE_DASH
                    else -> C.TYPE_OTHER
                }

                // 维度6: MP4 moov 位置检测
                val moovPosition = if (magicMime == MimeTypes.VIDEO_MP4) {
                    MimeSniffer.detectMoovPosition(bodyBytes)
                } else {
                    MoovPosition.UNKNOWN
                }

                // 综合判定：强信号优先
                val (finalContentType, finalMimeType) = when {
                    // 强信号1：主动 Probe 清单内容
                    probedContentType == C.TYPE_HLS -> C.TYPE_HLS to MimeTypes.APPLICATION_M3U8
                    probedContentType == C.TYPE_DASH -> C.TYPE_DASH to MimeTypes.APPLICATION_MPD
                    // 强信号2：Magic Number 匹配
                    magicMime != null -> inferContentTypeByMimeType(magicMime) to magicMime
                    // 弱信号1：Content-Type 提示
                    hintByCt != null -> hintByCt to contentType
                    // 弱信号2：最终 URL 后缀提示（重定向后）
                    hintByFinalExt != null -> hintByFinalExt to null
                    // 弱信号3：初始 URL 后缀提示
                    hintByInitExt != null -> hintByInitExt to null
                    // 全部失败
                    else -> SniffResult.TYPE_UNKNOWN to null
                }

                val elapsed = System.currentTimeMillis() - startTime
                AppLog.putDebug(
                    "sniffVideoType: success, contentType=$finalContentType, mimeType=$finalMimeType, " +
                        "moov=$moovPosition, range=$supportsRange, magic=$magicMime, " +
                        "ct=$contentType, elapsed=${elapsed}ms, urlPath=${sanitizeUrl(url)}"
                )

                SniffResult(
                    contentType = finalContentType,
                    mimeType = finalMimeType,
                    moovPosition = moovPosition,
                    supportsRange = supportsRange,
                    finalUrl = finalUrl
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // 重新抛出，保留协程取消语义（项目铁证）
        } catch (e: java.io.IOException) {
            AppLog.put("sniffVideoType: range request io failed: ${e.javaClass.simpleName}, urlPath=${sanitizeUrl(url)}")
            SniffResult.UNKNOWN
        }
    }

    /**
     * R4-T5: 根据 MIME 类型推断 ExoPlayer contentType
     *
     * HLS: application/x-mpegURL → C.TYPE_HLS
     * DASH: application/dash+xml → C.TYPE_DASH
     * 其他视频/音频 → C.TYPE_OTHER（走 ProgressiveMediaSource）
     */
    private fun inferContentTypeByMimeType(mimeType: String?): Int {
        if (mimeType == null) return SniffResult.TYPE_UNKNOWN
        return when (mimeType) {
            MimeTypes.APPLICATION_M3U8 -> C.TYPE_HLS
            MimeTypes.APPLICATION_MPD -> C.TYPE_DASH
            else -> C.TYPE_OTHER
        }
    }

    /**
     * R4-T5: 根据 URL 后缀推断 ExoPlayer contentType
     *
     * .m3u8 → C.TYPE_HLS
     * .mpd → C.TYPE_DASH
     * .ism/.ismv → C.TYPE_SS（Smooth Streaming）
     * 其他 → null（未识别）
     */
    private fun inferContentTypeByExtension(url: String): Int? {
        val path = url.lowercase().substringBefore("?").substringBefore("#")
        return when {
            path.endsWith(".m3u8") -> C.TYPE_HLS
            path.endsWith(".mpd") -> C.TYPE_DASH
            path.endsWith(".ism") || path.endsWith(".ismv") -> C.TYPE_SS
            else -> null
        }
    }

    /**
     * R4-T5: 解析 Content-Type 头为 ExoPlayer contentType
     *
     * 与 [parseContentType] 不同，本函数返回 ExoPlayer contentType（C.TYPE_HLS 等），
     * 而非 mimeType 字符串。用于 7 维度交叉验证的维度1。
     */
    private fun parseContentTypeToContentType(contentType: String?): Int? {
        if (contentType.isNullOrBlank()) return null
        val lower = contentType.lowercase().substringBefore(";").trim()
        return when {
            lower == "application/x-mpegurl" || lower == "application/vnd.apple.mpegurl" -> C.TYPE_HLS
            lower == "application/dash+xml" -> C.TYPE_DASH
            lower.startsWith("video/") || lower.startsWith("audio/") -> C.TYPE_OTHER
            else -> null
        }
    }

    fun createMediaItem(
        url: String,
        headers: Map<String, String>,
        sniffedMimeType: String? = null
    ): MediaItem {
        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        // exoplayer-resilience Layer 1：优先级链 sniffedMimeType > getMimeType(url) > null
        // - sniffedMimeType 来自 sniffMimeType 预嗅探（Content-Type + magic number）
        // - getMimeType(url) 是 URL 后缀检测（含 format=m3u8 query 标识）
        // - null 让 ExoPlayer 内置 Extractor.sniff() 自动推断
        val suffixMime = getMimeType(url)
        val mimeType = sniffedMimeType ?: suffixMime
        if (mimeType != null) {
            mediaItemBuilder.setMimeType(mimeType)
        }
        // headers 通过 setDefaultHeaders 注入 okhttpDataFactory（等价原 SPLIT_TAG 路径的行为）
        if (headers.isNotEmpty()) {
            setDefaultHeaders(headers)
        }
        val source = when {
            sniffedMimeType != null -> "sniffed"
            suffixMime != null -> "suffix"
            else -> "auto"
        }
        AppLog.putDebug("createMediaItem: mimeType=$mimeType, source=$source, headerKeys=${headers.keys}, urlPath=${sanitizeUrl(url)}")
        return mediaItemBuilder.build()
    }

    /**
     * exoplayer-resilience Layer 1：预嗅探 mimeType
     *
     * 5 级识别优先级链（参考 Chromium 多级识别策略）：
     * - L1: 缓存命中（[MimeSnifferCache]）→ 直接返回（0 延迟）
     * - L2: 服务端 Content-Type 有效（video 子类型 或 application/x-mpegURL）→ 使用
     * - L3: magic number 检测（[MimeSniffer.sniff] 读 body 前 1KB）
     * - L4: URL 后缀检测 [getMimeType]（调用方做，本函数不返回 L4 结果）
     * - L5: 默认推断 → 返回 null 让 ExoPlayer 内置 Extractor.sniff() 兜底
     *
     * 超时控制：3 秒（withTimeoutOrNull），避免阻塞 UI
     * 协程调度：网络请求在 [Dispatchers.IO] 执行
     *
     * @param url 视频 URL（完整，含 query）
     * @param headers 请求头（如 Referer/User-Agent/Cookie，用于防盗链场景）
     * @return 嗅探得到的 mimeType（如 "application/x-mpegURL"），或 null 表示未识别
     */
    suspend fun sniffMimeType(url: String, headers: Map<String, String>): String? {
        val startTime = System.currentTimeMillis()
        // L1: 缓存命中检查
        MimeSnifferCache.get(url)?.let { cacheValue ->
            // 缓存命中（含"嗅探过但未识别"的 null 标记，避免重复嗅探失败）
            AppLog.putDebug("SniffingMime: cache hit, mimeType=${cacheValue.mimeType}, urlPath=${sanitizeUrl(url)}")
            return cacheValue.mimeType
        }

        // L2-L3: Range 请求 + Content-Type + magic number（前置帧分析，优先）
        // 设计理由（用户2026-07-26 11:11反馈强化嗅探能力）：
        // - URL 后缀可被伪造（如 .php 返回 m3u8 流）或动态 URL（play.php?id=xxx 返回 mp4）
        // - 前置帧分析（magic number）读取实际内容前 1KB，更可靠
        // - 嗅探准确性 > 性能（200-500ms 延迟可接受）
        val sniffedMime = withTimeoutOrNull(SNIFF_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                sniffWithRangeRequest(url, headers)
            }
        }  // 超时返回 null

        // 前置帧分析成功 → 缓存并返回
        if (sniffedMime != null) {
            MimeSnifferCache.put(url, sniffedMime)
            val elapsed = System.currentTimeMillis() - startTime
            AppLog.putDebug("SniffingMime: sniffed mimeType=$sniffedMime, elapsed=${elapsed}ms, urlPath=${sanitizeUrl(url)}")
            return sniffedMime
        }

        // L4: URL 后缀检测（仅作为前置帧分析失败/超时时的兜底）
        // 设计理由：前置帧分析失败可能是网络问题或服务端不支持 Range，URL 后缀作为最后兜底
        val suffixMime = getMimeType(url)
        if (suffixMime != null) {
            // P0-2 修复（2026-07-26）：URL 后缀兜底结果不缓存
            // 原因：前置帧分析失败可能是临时网络问题，若缓存 URL 后缀结果，1小时内即使网络恢复也不会重新嗅探
            // 权衡：确实无法识别的视频每次都走 URL 后缀兜底，但避免临时失败后错误缓存导致无法播放
            AppLog.putDebug("SniffingMime: suffix fallback (range failed/timeout), mimeType=$suffixMime, urlPath=${sanitizeUrl(url)}")
            return suffixMime
        }

        // L5: 默认推断 → null（让 ExoPlayer 内置 Extractor.sniff() 兜底）
        // P0-2 修复（2026-07-26）：不缓存 null，避免临时网络失败后1小时内无法重试嗅探
        // 用户核心诉求是"能播放"，宁可多嗅探一次也不要因缓存null导致无法播放
        val elapsed = System.currentTimeMillis() - startTime
        AppLog.putDebug("SniffingMime: sniff failed (null), elapsed=${elapsed}ms, urlPath=${sanitizeUrl(url)}")
        return null
    }

    /**
     * 发送 Range: bytes=0-1023 请求，按 L2→L3→L5 顺序识别 mimeType
     *
     * 安全性：用 [readLimitedBytes] 最多读取 1024 字节，避免服务端不支持 Range 时返回完整大文件导致 OOM
     *
     * P0-5 修复（2026-07-26）：原用 runCatching 会吞掉 CancellationException，破坏协程结构化取消
     * （项目铁证：runCatching会吞CancellationException导致协程取消误报，必须重新抛出）
     * 改用显式 try/catch：CancellationException 重新抛出，IOException 记录日志返回 null
     */
    private suspend fun sniffWithRangeRequest(url: String, headers: Map<String, String>): String? {
        return try {
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-1023")
                .header("Accept", "video/*, application/x-mpegURL, */*")
            // 注入用户请求头（如 Referer/User-Agent/Cookie，用于防盗链场景）
            headers.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }
            val request = requestBuilder.build()

            okHttpClient.newCall(request).execute().use { response ->
                // T1.4: execute() 返回后检查 isActive，协程取消时立即返回 null
                // use 块内 this 变为 Response，需用 coroutineContext.isActive 显式访问协程上下文
                if (!coroutineContext.isActive) {
                    AppLog.putDebug("sniffWithRangeRequest cancelled after execute, urlPath=${sanitizeUrl(url)}")
                    return@use null
                }
                if (!response.isSuccessful && response.code != 206 && response.code != 200) {
                    AppLog.putDebug("SniffingMime: non-200 response: code=${response.code}, urlPath=${sanitizeUrl(url)}")
                    return@use null
                }
                // L2: 服务端 Content-Type 检测
                val contentType = response.header("Content-Type")
                val mimeFromContentType = parseContentType(contentType)
                if (mimeFromContentType != null) {
                    AppLog.putDebug("SniffingMime: L2 content-type hit: mime=$mimeFromContentType, contentType=$contentType, urlPath=${sanitizeUrl(url)}")
                    return@use mimeFromContentType
                }
                // L3: magic number 检测（读 body 前 1KB，限制最多 1024 字节避免 OOM）
                val body = response.body ?: run {
                    AppLog.putDebug("SniffingMime: empty body, urlPath=${sanitizeUrl(url)}")
                    return@use null
                }
                val bodyBytes = readLimitedBytes(body, 1024)
                val magicMime = MimeSniffer.sniff(bodyBytes)
                if (magicMime == null) {
                    AppLog.putDebug("SniffingMime: L3 magic not matched: bytes=${bodyBytes.size}, contentType=$contentType, urlPath=${sanitizeUrl(url)}")
                } else {
                    AppLog.putDebug("SniffingMime: L3 magic hit: mime=$magicMime, bytes=${bodyBytes.size}, urlPath=${sanitizeUrl(url)}")
                }
                magicMime
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // 重新抛出，保留协程取消语义（项目铁证）
        } catch (e: java.io.IOException) {
            AppLog.put("SniffingMime: range request io failed: ${e.javaClass.simpleName}, urlPath=${sanitizeUrl(url)}")
            null
        }
    }

    /**
     * 从 ResponseBody 读取最多 [maxBytes] 字节，避免服务端不支持 Range 返回完整大文件导致 OOM
     *
     * T2.6: 改为 suspend 函数，循环内检查 [isActive]，协程取消时立即停止读取（解决 Bug-21）
     */
    private suspend fun readLimitedBytes(body: okhttp3.ResponseBody, maxBytes: Int): ByteArray {
        val stream = body.byteStream()
        val buffer = ByteArray(maxBytes)
        var totalRead = 0
        while (totalRead < maxBytes) {
            // T2.6: 循环内检查 isActive，协程取消时立即停止读取
            // 注：readLimitedBytes 是 suspend 函数，coroutineContext 可直接访问
            if (!coroutineContext.isActive) {
                AppLog.putDebug("readLimitedBytes cancelled in readLoop, totalRead=$totalRead")
                break
            }
            val read = stream.read(buffer, totalRead, maxBytes - totalRead)
            if (read <= 0) break
            totalRead += read
        }
        return if (totalRead == maxBytes) buffer else buffer.copyOf(totalRead)
    }

    /**
     * 解析 HTTP Content-Type 头，返回 ExoPlayer 兼容的 mimeType（仅识别视频流类型）
     *
     * - L2 命中条件：Content-Type 是 video 子类型 或 application/x-mpegURL 或 application/vnd.apple.mpegurl
     * - 忽略非视频 Content-Type（如 text/html 表示返回的是错误页面，不视为有效）
     */
    private fun parseContentType(contentType: String?): String? {
        if (contentType.isNullOrBlank()) return null
        val lower = contentType.lowercase().substringBefore(";").trim()
        return when {
            // 通用视频类型
            lower.startsWith("video/") -> {
                // 映射常见 video/* 到 ExoPlayer MimeTypes
                when (lower) {
                    "video/mp4" -> MimeTypes.VIDEO_MP4
                    "video/mp2t" -> MimeTypes.VIDEO_MP2T
                    "video/webm" -> MimeTypes.VIDEO_WEBM
                    "video/x-matroska" -> MimeTypes.VIDEO_MATROSKA
                    "video/x-flv" -> "video/x-flv"
                    else -> lower  // 其他 video/* 直接返回原值
                }
            }
            // HLS m3u8（多种变体）
            lower == "application/x-mpegurl" || lower == "application/vnd.apple.mpegurl" ->
                MimeTypes.APPLICATION_M3U8
            // DASH mpd
            lower == "application/dash+xml" -> MimeTypes.APPLICATION_MPD
            // 非视频类型（text/html、application/json 等）视为无效
            else -> null
        }
    }

    /** 嗅探超时：5 秒（T1.3 从 3s 提升至 5s，解决 Bug-3 弱网场景嗅探未完成被超时；超时返回 null 让 ExoPlayer 内置 sniff 兜底） */
    private const val SNIFF_TIMEOUT_MS = 5000L

    /**
     * URL 后缀→MIME 类型映射
     * app-stability-round2 P1-3 修复：createMediaItem 不再拼接 SPLIT_TAG 到 URI，
     * 改用 setMimeType 显式声明类型，避免 DefaultMediaSourceFactory 的 URL 后缀检测被破坏
     */
    private fun getMimeType(url: String): String? {
        val lower = url.lowercase()
        val path = lower.substringBefore("?").substringBefore("#")
        return when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
            path.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
            path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            path.endsWith(".flv") -> "video/x-flv"
            path.endsWith(".ts") -> MimeTypes.VIDEO_MP2T
            // HLS query 标识检测：仅匹配显式 query 参数 format=m3u8 / type=m3u8
            // 修复 R3 BUG：原 lower.contains("m3u8") 会匹配 URL 任意位置（含 query 参数中的 URL 值），
            //   误判场景：/Player/Play.php?uu=https%3A%2F%2Fplay2.xxx.top%2F...%2Findex.m3u8
            //   Play.php 是 PHP 代理页面，但 query 参数 uu 含 index.m3u8 字符串触发误判，
            //   ExoPlayer 用 HLS 解析器解析非 m3u8 内容抛 3002 ERROR_CODE_PARSING_MANIFEST_MALFORMED
            // 修复策略：
            //   1. 删除 lower.contains("m3u8") 和 lower.contains("index.m3u8") 过宽匹配
            //   2. 仅保留显式 format=m3u8 / type=m3u8 query 标识（站点B ruleContent 用）
            //   3. path 后缀场景已由 path.endsWith(".m3u8") 覆盖
            // 铁证：logcat L7164-L7218 SniffingMime cache hit mimeType=application/x-mpegURL
            //       + ParserException "Input does not start with the #EXTM3U header"
            lower.contains("format=m3u8") || lower.contains("type=m3u8") -> MimeTypes.APPLICATION_M3U8
            // 修复 3002 PARSING_CONTAINER_MALFORMED BUG：
            // 原代码 else 兜底强制返回 APPLICATION_M3U8，导致任何识别不出的 URL
            // （如 play.php?id=xxx 返回 mp4 流）被误判为 m3u8，
            // HlsMediaSource 期望 #EXTM3U 头但实际是 mp4 ftyp 二进制盒，抛 3002
            // 修复：返回 null 让 ExoPlayer 根据 URL 后缀 + HTTP Content-Type 自动推断
            // 安全性：L57 已有 if (mimeType != null) 判空，返回 null 不影响调用方
            else -> null
        }
    }

    /**
     * URL 脱敏：用于日志输出，只保留 path 前40字符（符合 P0 安全规范）
     *
     * P0-6 修复（2026-07-26）：从 private 改为 public，供 Exo2MediaPlayer 复用，
     * 统一URL脱敏入口，避免完整URL输出到AppLog触发违禁词审查中断
     */
    fun sanitizeUrl(url: String): String {
        return try {
            val u = java.net.URL(url)
            "path=${u.path?.take(40)}"
        } catch (e: Exception) {
            "raw=${url.take(30)}"
        }
    }

    fun createHttpExoPlayer(context: Context): ExoPlayer {
        return ExoPlayer.Builder(context).setLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS / 10,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS / 10
            ).build()
        ).setMediaSourceFactory(
            DefaultMediaSourceFactory(
                context,
                DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
            ).setDataSourceFactory(resolvingDataSource)
                .setLiveTargetOffsetMs(5000)
        ).build()
    }


    val resolvingDataSource: ResolvingDataSource.Factory by lazy {
        ResolvingDataSource.Factory(cacheDataSourceFactory) {
            var res = it

            if (it.uri.toString().contains(SPLIT_TAG)) {
                val urls = it.uri.toString().split(SPLIT_TAG)
                val url = urls[0]
                res = res.withUri(Uri.parse(url))
                try {
                    val headers: Map<String, String> = GSON.fromJson(urls[1], mapType)
                    okhttpDataFactory.setDefaultRequestProperties(headers)
                    // P0 日志规范：Header 注入成功确认（Tag=ExoHeader）
                    AppLog.putDebug("ExoHeader: Headers injected via SPLIT_TAG, keys=${headers.keys}, urlLen=${url.length}")
                } catch (e: Exception) {
                    // P0 日志规范：错误处理路径必须有日志
                    AppLog.putError("ExoHeader: Failed to parse headers from SPLIT_TAG", e)
                }
            }

            res

        }
    }


    /**
     * 支持缓存的DataSource.Factory
     */
    val cacheDataSourceFactory by lazy {
        //使用自定义的CacheDataSource以支持设置UA
        // P2 修复：用 DefaultDataSource 包装 okhttpDataFactory，支持 file:// 等本地协议
        // 根因：OkHttpDataSource 只支持 http/https，遇到 file:// 路径抛 HttpDataSourceException: Malformed URL
        // 证据：crash-2026-07-13-14-53-47 + logcat L81452 OkHttpDataSource.makeRequest 请求 file://...mpd
        // DefaultDataSource 会根据 URI scheme 自动选择 FileDataSource/OkHttpDataSource/ContentDataSource
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(appCtx, okhttpDataFactory))
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory()
                    .setCache(cache)
                    .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)
            )
    }

    /**
     * Okhttp DataSource.Factory
     *
     * P2-C 修复：强制 HTTP/1.1，规避 HTTP/2 PROTOCOL_ERROR
     * 根因：部分视频 CDN 的 HTTP/2 实现对 mp4 流式响应处理有 bug，
     *      主动发送 RST_STREAM(PROTOCOL_ERROR)，导致 OkHttp 抛 StreamResetException，
     *      视频播放失败（ERROR_CODE_IO_NETWORK_CONNECTION_FAILED 2001）。
     * 证据：appLog-26-07-12 + logcat 中 22 条 StreamResetException
     * 方案：ExoPlayer 客户端限制 protocols=[HTTP_1_1]，绕开 HTTP/2 协商。
     * 影响范围：仅视频播放，不影响书源/订阅源请求（仍用默认 OkHttp + Cronet）。
     * 已知上限：HTTP/1.1 无多路复用，但视频流是单长连接，性能影响可忽略。
     */
    private val okhttpDataFactory by lazy {
        val client = okHttpClient.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))  // P2-C: 强制 HTTP/1.1
            .followRedirects(true)  // R4-T6: 跟随重定向，感知最终 URL
            .build()
        OkHttpDataSource.Factory(client)
            .setUserAgent(BROWSER_UA)  // R4-T6: 浏览器 UA，提升 CDN 抓取成功率
            // R4-T6: 跨协议重定向由 OkHttp client 的 followRedirects(true)+followSslRedirects(true) 处理
            .setCacheControl(CacheControl.Builder().maxAge(1, TimeUnit.DAYS).build())
    }

    /**
     * R5 Header 修复：设置 okhttpDataFactory 的默认请求头
     *
     * Exo2MediaPlayer.prepareAsyncInternal 使用 no-op resolver（不处理 Header），
     * 但其 MediaSource 使用 cacheDataSourceFactory（upstream 是 okhttpDataFactory）。
     * 在 ExoPlayerManager.initVideoPlayer 中 setDataSource 前调用此方法，
     * 可确保 Header 到达 HTTP 请求（解决 CDN 防盗链 404 问题）。
     *
     * 简化说明：单播放器场景，Header 覆盖不影响 | 已知上限：多播放器并发会互相覆盖 | 升级路径：改用 per-request Header 注入
     */
    fun setDefaultHeaders(headers: Map<String, String>) {
        okhttpDataFactory.setDefaultRequestProperties(headers)
    }

    /**
     * P1-C 修复：清除视频缓存（HTTP 416 错误时调用）
     *
     * 根因：ExoPlayer 的 Range 请求与服务端缓存状态不匹配，服务端返回 416 Range Not Satisfiable
     * 方案：清除缓存分片后重试，避免 Range 请求冲突
     * 影响范围：清除所有视频缓存（416 不常见，清除全部可接受）
     */
    fun clearCache() {
        try {
            val keys = cache.keys.toList()
            keys.forEach { cache.removeResource(it) }
            AppLog.put("清除视频缓存: count=${keys.size}")
        } catch (e: Exception) {
            AppLog.put("清除视频缓存失败", e)
        }
    }

    /**
     * Exoplayer 内置的缓存
     * P0-3：缓存容量从 VideoPlay.videoCacheSize 读取（首次 lazy 初始化时生效，修改后需重启 App）
     */
    private val cache: Cache by lazy {
        val databaseProvider = StandaloneDatabaseProvider(appCtx)
        val cacheSizeMb = VideoPlay.videoCacheSize.coerceIn(50, 500)  // 容量范围保护：50-500MB
        return@lazy SimpleCache(
            //Exoplayer的缓存路径
            File(appCtx.externalCache, "exoplayer"),
            //容量从配置读取（默认 100MB）
            LeastRecentlyUsedCacheEvictor((cacheSizeMb * 1024 * 1024).toLong()),
            //记录缓存的数据库
            databaseProvider
        )
    }

    /**
     * 通过kotlin扩展函数+反射实现CacheDataSource.Factory设置默认请求头
     * 需要添加混淆规则 -keepclassmembers class com.google.android.exoplayer2.upstream.cache.CacheDataSource$Factory{upstreamDataSourceFactory;}
     * @param headers
     * @return
     */
//    private fun CacheDataSource.Factory.setDefaultRequestProperties(headers: Map<String, String> = mapOf()): CacheDataSource.Factory {
//        val declaredField = this.javaClass.getDeclaredField("upstreamDataSourceFactory")
//        declaredField.isAccessible = true
//        val df = declaredField[this] as DataSource.Factory
//        if (df is OkHttpDataSource.Factory) {
//            df.setDefaultRequestProperties(headers)
//        }
//        return this
//    }


    fun getMediaSource(context: Context, url: String): MediaSource? {
        val uris = GSON.fromJsonArray<String>(url).getOrNull() ?: return null
        val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context)
        val mediaSourceBuilder = ConcatenatingMediaSource2.Builder()
        for (uri in uris) {
            mediaSourceBuilder.add(
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri)), 3000
            )
        }
        return mediaSourceBuilder.build()
    }
}