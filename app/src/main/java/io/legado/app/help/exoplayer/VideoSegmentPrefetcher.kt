package io.legado.app.help.exoplayer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.legado.app.constant.AppLog
import io.legado.app.help.http.videoStreamClient
import io.legado.app.help.video.engine.M3u8Parser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 分片级预取器（当前集后续分片 + 下一集首片）。
 *
 * 设计依据：docs/specs/video-buffer-prefetch-v2/design.md §1.2/§1.3/§1.12
 * 审计修复：audit-implementation-20261109.md B1/B6/B7/B8
 *
 * 关键约束：
 * - **媒体清单集合**（最多 2 份：视频 + 独立音频），避免「第一个非 master 是音频」导致视频零预取；
 * - 不直接读 Player（跨线程 1004），只读应用线程写入的 [Snapshot]；
 * - 写缓存异常按失败计数（不重试、不崩溃），连续 3 次熔断；
 * - 端口可注入（[SegmentWriter]/[PrefetchCache]/[ManifestFetcher]/[PrefetchClock]），便于 JVM 单测。
 */
object VideoSegmentPrefetcher {

    /** 播放状态快照（应用线程写、预取线程读；不可变） */
    data class Snapshot(
        val sessionId: Long,
        val positionMs: Long,
        val bufferedPositionMs: Long,
        val durationMs: Long,
        val speed: Float,
        val isLive: Boolean,
        val buffering: Boolean,
        val paused: Boolean,
        val foreground: Boolean,
        val isMetered: Boolean,
        val videoCacheEnabled: Boolean,
        val prefetchEnabled: Boolean,
        val cacheSizeBytes: Long,
        val bitrateBps: Long,
        val bandwidthEstimateBps: Long,
        val hasDrm: Boolean,
        val headers: Map<String, String>,
        /** 直链源（非 HLS）的播放地址；HLS 为 null */
        val directUri: String? = null,
        /** 嗅探出的 contentType（C.TYPE_OTHER 才走直链 Range 预取） */
        val contentType: Int = -1,
        val supportsRange: Boolean = false,
    )

    /** 已学习的媒体清单 */
    private data class MediaPlaylist(
        val uri: String,
        val segments: List<SegmentPrefetchPolicy.Segment>,
        val hasEndList: Boolean,
        val hasByteRange: Boolean,
        val targetDurationMs: Long,
        val fetchedAtMs: Long,
    )

    private const val MAX_PLAYLISTS = 2
    private const val PLAYLIST_TTL_MS = 60_000L
    private const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
    private const val MAX_SEGMENT_WRITE_BYTES = 32L * 1024 * 1024
    private const val MAX_CONSECUTIVE_FAILURES = 3
    private const val ROUND_MIN_INTERVAL_MS = 1_000L
    private const val SEEK_DELAY_MS = 1_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var snapshot: Snapshot? = null
    private var roundJob: Job? = null
    private var seekJob: Job? = null
    @Volatile
    private var currentSessionId: Long = 0L

    private val learned = ConcurrentHashMap<String, MediaPlaylist>()
    private val masterUris = ConcurrentHashMap.newKeySet<String>()

    /** 本会话已成功预取的分片 URI（避免 5s 补水重复从缓存读同一分片） */
    private val doneKeys = ConcurrentHashMap.newKeySet<String>()
    private val consecutiveFailures = java.util.concurrent.atomic.AtomicInteger(0)
    private val circuitOpen = AtomicBoolean(false)
    private val lastRoundAt = AtomicLong(0L)
    @Volatile
    private var lastGateReason: String? = null
    private var segDoneLogCount = 0
    private var planLogCount = 0

    // ===== 可注入端口（默认生产实现） =====
    internal var writer: SegmentWriter = DefaultSegmentWriter
    internal var cache: PrefetchCache = DefaultPrefetchCache
    internal var fetcher: ManifestFetcher = DefaultManifestFetcher
    internal var clock: PrefetchClock = PrefetchClock { System.nanoTime() / 1_000_000L }

    /** 测试用：重置全部状态与端口 */
    internal fun resetForTest(
        writer: SegmentWriter? = null,
        cache: PrefetchCache? = null,
        fetcher: ManifestFetcher? = null,
        clock: PrefetchClock? = null,
    ) {
        roundJob?.cancel()
        seekJob?.cancel()
        roundJob = null
        seekJob = null
        snapshot = null
        currentSessionId = 0L
        learned.clear()
        masterUris.clear()
        doneKeys.clear()
        consecutiveFailures.set(0)
        circuitOpen.set(false)
        lastRoundAt.set(0L)
        lastGateReason = null
        segDoneLogCount = 0
        planLogCount = 0
        if (writer != null) this.writer = writer
        if (cache != null) this.cache = cache
        if (fetcher != null) this.fetcher = fetcher
        if (clock != null) this.clock = clock
    }

    /** 开启新会话（切集/切源）；取消在途任务并清空学习结果 */
    fun beginSession(sessionId: Long) {
        synchronized(lock) {
            roundJob?.cancel()
            seekJob?.cancel()
            roundJob = null
            seekJob = null
            currentSessionId = sessionId
            learned.clear()
            masterUris.clear()
            doneKeys.clear()
            consecutiveFailures.set(0)
            circuitOpen.set(false)
            lastRoundAt.set(0L)
            lastGateReason = null
            segDoneLogCount = 0
            planLogCount = 0
        }
    }

    /**
     * 播放状态更新（应用线程）。
     *
     * 多实例/相邻页（FEED）场景：只允许「正在播放（paused=false）」的实例接管会话，
     * 避免非活跃页 prepare 后把活跃页的预取会话顶掉（审计 H8）。
     */
    @Synchronized
    fun updateSnapshot(value: Snapshot) {
        snapshot = value
        if (!value.paused && value.sessionId != currentSessionId) {
            currentSessionId = value.sessionId
            learned.clear()
            masterUris.clear()
            doneKeys.clear()
            consecutiveFailures.set(0)
            circuitOpen.set(false)
        }
    }

    /** READY 时立即补水 */
    fun onReady() {
        topUp(force = true)
    }

    /** seek：取消在途 + 1s 后重算（防连续拖动风暴） */
    fun onSeek() {
        seekJob?.cancel()
        roundJob?.cancel()
        val session = currentSessionId
        seekJob = scope.launch {
            delay(SEEK_DELAY_MS)
            if (session == currentSessionId) topUp(force = true)
        }
    }

    /** 会话结束/切集：取消全部任务 */
    fun stop() {
        synchronized(lock) {
            roundJob?.cancel()
            seekJob?.cancel()
            roundJob = null
            seekJob = null
            snapshot = null
        }
    }

    /** 退出播放器：取消 + 清空学习与熔断 */
    fun clearAll() {
        stop()
        learned.clear()
        masterUris.clear()
        doneKeys.clear()
        ManifestObserver.clear()
        circuitOpen.set(false)
        consecutiveFailures.set(0)
    }

    /** 已学习清单的时间轴 span（回看清理用） */
    fun learnedSpans(): List<RollingBackCache.Span> {
        val result = ArrayList<RollingBackCache.Span>()
        learned.values.forEach { playlist ->
            var acc = 0L
            playlist.segments.forEach { seg ->
                val start = acc
                acc += seg.durationMs.coerceAtLeast(0L)
                result.add(RollingBackCache.Span(key = seg.uri, startMs = start, endMs = acc))
            }
        }
        return result
    }

    /** 长视频回看清理（HLS：按分片 key 删资源） */
    fun trimBackCache(positionMs: Long, windowMs: Long): Int {
        if (windowMs <= 0L) return 0
        val spans = learnedSpans()
        if (spans.isEmpty()) return 0
        val removals = RollingBackCache.plan(spans, positionMs, windowMs, singleKey = false)
        return RollingBackCache.trim(cache, removals)
    }

    /** 长视频回看清理（直链：单 key，按 span 精确删） */
    fun trimBackCacheSingleKey(
        key: String,
        positionMs: Long,
        windowMs: Long,
        bitrateBps: Long,
    ): Int {
        if (windowMs <= 0L || key.isBlank() || bitrateBps <= 0L) return 0
        val cached = cache.cachedSpans(key)
        if (cached.isEmpty()) return 0
        val spans = cached.map { span ->
            val startMs = span.position * 8L * 1000L / bitrateBps
            val endMs = (span.position + span.length) * 8L * 1000L / bitrateBps
            RollingBackCache.Span(
                key = key,
                startMs = startMs,
                endMs = endMs,
                byteStart = span.position,
                byteEnd = span.position + span.length,
            )
        }
        val removals = RollingBackCache.plan(
            spans,
            positionMs = positionMs,
            windowMs = windowMs,
            singleKey = true,
            bitrateBps = bitrateBps,
        )
        return RollingBackCache.trim(cache, removals)
    }

    /** 主动触发一轮补水（5s 定时调用；force=false 时受最小间隔约束） */
    fun topUp(force: Boolean = false) {
        val s = snapshot ?: return
        if (s.sessionId != currentSessionId) return
        if (circuitOpen.get()) return
        val now = clock.nowMs()
        if (!force && now - lastRoundAt.get() < ROUND_MIN_INTERVAL_MS) return
        if (roundJob?.isActive == true) return

        val globalGate = globalGate(s)
        if (globalGate != null) {
            if (lastGateReason != globalGate) {
                lastGateReason = globalGate
                AppLog.put("[Prefetch] gate reason=$globalGate")
            }
            return
        }
        if (lastGateReason != null) {
            lastGateReason = null
            AppLog.put("[Prefetch] gate open")
        }

        lastRoundAt.set(now)
        roundJob = scope.launch {
            try {
                runRound(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.putDebug("[Prefetch] round failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** 全局门禁（无需清单即可判定） */
    private fun globalGate(s: Snapshot): String? = when {
        !s.prefetchEnabled -> "disabled"
        !s.videoCacheEnabled -> "video_cache_off"
        s.isMetered -> "metered"
        s.isLive -> "live"
        s.paused -> "paused"
        !s.foreground -> "background"
        s.durationMs <= 0L -> "no_duration"
        else -> null
    }

    private suspend fun runRound(s: Snapshot) {
        val headers = s.headers
        val playlists = learnPlaylists(s, headers)
        if (playlists.isEmpty()) {
            // video-buffer-prefetch-v2 E1：直链 MP4/FLV（支持 Range）按字节窗口预取
            if (!tryDirectRange(s, headers)) recordFailure("no_media_playlist")
            return
        }
        val baseInput = s.toInput(emptyList())
        val budget = SegmentPrefetchPolicy.budget(baseInput)
        if (budget.state == SegmentPrefetchPolicy.BudgetState.PAUSED) {
            if (lastGateReason != "low_buffer") {
                lastGateReason = "low_buffer"
                AppLog.put("[Prefetch] gate reason=low_buffer bufMs=${(s.bufferedPositionMs - s.positionMs).coerceAtLeast(0)}")
            }
            return
        }
        val perPlaylistBudget = (budget.bytesPerSec / playlists.size).coerceAtLeast(1L)

        coroutineScope {
            playlists.map { playlist ->
                async {
                    writePlaylist(s, playlist, headers, perPlaylistBudget)
                }
            }.awaitAll()
        }
    }

    /**
     * 直链 MP4/FLV 的 Range 窗口预取（E1）。
     *
     * 只在 `supportsRange=true` 且 `contentType=TYPE_OTHER` 且时长/码率已知时启用；
     * 服务器若忽略 Range（返回 200），写入器读到窗口字节即停，不会整包下载。
     */
    private suspend fun tryDirectRange(s: Snapshot, headers: Map<String, String>): Boolean {
        val uri = s.directUri ?: return false
        if (!uri.startsWith("http")) return false
        if (!s.supportsRange || s.contentType != androidx.media3.common.C.TYPE_OTHER) return false
        if (s.durationMs <= 0L || s.bitrateBps <= 0L) return false
        val total = cache.contentLength(uri)
        if (total <= 0L) return false
        val budget = SegmentPrefetchPolicy.budget(s.toInput(emptyList()))
        if (budget.state == SegmentPrefetchPolicy.BudgetState.PAUSED) return true

        val startMs = s.bufferedPositionMs + 2_000L
        if (startMs >= s.durationMs) return true
        val windowMs = minOf(
            SpeedBufferPolicy.scaledWindowMs(SegmentPrefetchPolicy.BASE_WINDOW_MS, s.speed),
            s.durationMs - startMs,
        )
        if (windowMs <= 0L) return true
        val bytesPerMs = total.toDouble() / s.durationMs
        val maxBytes = (s.cacheSizeBytes * SegmentPrefetchPolicy.BYTE_RATIO_LONG).toLong()
        val startByte = (startMs * bytesPerMs).toLong().coerceIn(0L, (total - 1).coerceAtLeast(0L))
        val length = minOf(
            (windowMs * bytesPerMs).toLong(),
            total - startByte,
            if (maxBytes > 0L) maxBytes else Long.MAX_VALUE,
        )
        if (length <= 0L) return true
        if (cache.isCached(uri, startByte, length)) return true
        val bytes = try {
            writer.write(uri, headers, startByte, length)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.putDebug("[Prefetch] direct_range fail error=${e.javaClass.simpleName}")
            return false
        }
        AppLog.put("[Prefetch] direct_range startByte=$startByte len=$length bytes=$bytes")
        return true
    }

    private suspend fun learnPlaylists(s: Snapshot, headers: Map<String, String>): List<MediaPlaylist> {
        val now = clock.nowMs()
        val result = ArrayList<MediaPlaylist>(MAX_PLAYLISTS)
        for (uri in ManifestObserver.snapshot()) {
            if (result.size >= MAX_PLAYLISTS) break
            if (masterUris.contains(uri)) continue
            val cached = learned[uri]
            if (cached != null && cached.segments.isNotEmpty() && now - cached.fetchedAtMs < PLAYLIST_TTL_MS) {
                result.add(cached)
                continue
            }
            val text = fetcher.fetch(uri, headers) ?: continue
            if (M3u8Parser.isMasterPlaylist(text)) {
                masterUris.add(uri)
                continue
            }
            val parsed = HlsManifestParser.parseSegmentsWithDurations(text)
            if (parsed.isEmpty()) continue
            val segments = parsed.map {
                SegmentPrefetchPolicy.Segment(
                    durationMs = it.durationMs,
                    uri = HlsManifestParser.resolve(uri, it.uri),
                )
            }
            val playlist = MediaPlaylist(
                uri = uri,
                segments = segments,
                hasEndList = HlsManifestParser.hasEndList(text),
                hasByteRange = HlsManifestParser.hasByteRange(text),
                targetDurationMs = HlsManifestParser.parseTargetDuration(text),
                fetchedAtMs = now,
            )
            learned[uri] = playlist
            result.add(playlist)
        }
        return result
    }

    private suspend fun writePlaylist(
        s: Snapshot,
        playlist: MediaPlaylist,
        headers: Map<String, String>,
        budgetBps: Long,
    ) {
        val input = s.toInput(playlist.segments).copy(
            hasEndList = playlist.hasEndList,
            hasByteRange = playlist.hasByteRange,
        )
        val gate = SegmentPrefetchPolicy.gate(input)
        if (gate != null) return
        val window = SegmentPrefetchPolicy.selectWindow(input) ?: return
        if (window.isEmpty) return
        // HLS 分片请求头：与播放一致（已有 Referer 不覆盖）
        val writeHeaders = M3u8UrlShape.headersWithHlsReferer(headers, playlist.uri)
        if (planLogCount < 30 || planLogCount % 10 == 0) {
            AppLog.put(
                "[Prefetch] plan segs=${window.count} startMs=${window.startMs} endMs=${window.endMs} " +
                    "maxBytes=${window.maxBytes} budgetBps=$budgetBps td=${playlist.targetDurationMs} " +
                    "speed=${s.speed} pl=${playlist.uri.substringAfterLast('/').take(24)}"
            )
        }
        planLogCount++

        // 按估算字节数截断，避免只写半个分片（半个分片对播放无收益）
        val segBytes = { seg: SegmentPrefetchPolicy.Segment ->
            (seg.durationMs / 1000.0 * s.bitrateBps / 8.0 * 1.3).toLong()
        }
        var plannedBytes = 0L
        val indexes = ArrayList<Int>(window.count)
        for (i in window.startIndex until window.endIndexExclusive) {
            val estimate = segBytes(playlist.segments[i]).coerceAtLeast(1L)
            if (plannedBytes > 0 && plannedBytes + estimate > window.maxBytes) break
            plannedBytes += estimate
            indexes.add(i)
        }
        if (indexes.isEmpty()) return

        var written = 0L
        var writtenSegs = 0
        var skipped = 0
        val roundStart = clock.nowMs()
        for (i in indexes) {
            if (s.sessionId != currentSessionId) return
            val seg = playlist.segments[i]
            if (doneKeys.contains(seg.uri)) {
                skipped++
                continue
            }
            val contentLength = cache.contentLength(seg.uri)
            if (contentLength > 0 && cache.isCached(seg.uri, 0L, contentLength)) {
                skipped++
                continue
            }
            val bytes = try {
                writer.write(seg.uri, writeHeaders, 0L, MAX_SEGMENT_WRITE_BYTES)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.putDebug("[Prefetch] seg fail idx=$i error=${e.javaClass.simpleName}")
                recordFailure("write")
                if (circuitOpen.get()) return
                continue
            }
            if (bytes <= 0L) {
                recordFailure("empty")
                if (circuitOpen.get()) return
                continue
            }
            consecutiveFailures.set(0)
            doneKeys.add(seg.uri)
            written += bytes
            writtenSegs++
            if (segDoneLogCount < 30 || segDoneLogCount % 10 == 0) {
                AppLog.putDebug("[Prefetch] seg_done idx=$i bytes=$bytes")
            }
            segDoneLogCount++
            // 限速：保持 budgetBps，把带宽让给播放
            val wait = PrefillThrottle.waitMs(
                totalRead = written,
                elapsedMs = clock.nowMs() - roundStart,
                limitBytesPerSec = budgetBps,
            )
            if (wait > 0) delay(wait)
        }
        AppLog.put(
            "[Prefetch] window_done segs=$writtenSegs skipped=$skipped bytes=$written " +
                "budgetBps=$budgetBps pl=${playlist.uri.substringAfterLast('/').take(24)}"
        )
    }

    private fun recordFailure(reason: String) {
        val count = consecutiveFailures.incrementAndGet()
        if (count >= MAX_CONSECUTIVE_FAILURES && circuitOpen.compareAndSet(false, true)) {
            AppLog.put("[Prefetch] circuit reason=$reason failCount=$count")
        }
    }

    /**
     * 下一集预填：解析清单并预取前 [count] 个分片。
     *
     * 返回值：写入字节数；失败返回 0。清单本身不写缓存。
     */
    suspend fun prefillSegments(
        playlistUri: String,
        headers: Map<String, String>,
        count: Int = 3,
    ): Int = withContext(Dispatchers.IO) {
        if (playlistUri.isBlank() || count <= 0) return@withContext 0
        var mediaUri = playlistUri
        var text = fetcher.fetch(mediaUri, headers) ?: return@withContext 0
        if (M3u8Parser.isMasterPlaylist(text)) {
            val variant = M3u8Parser.pickVariantFromText(text, mediaUri) ?: return@withContext 0
            mediaUri = variant
            text = fetcher.fetch(mediaUri, headers) ?: return@withContext 0
        }
        if (HlsManifestParser.hasByteRange(text) || !HlsManifestParser.hasEndList(text)) return@withContext 0
        val segments = HlsManifestParser.parseSegmentsWithDurations(text)
        if (segments.isEmpty()) return@withContext 0
        val writeHeaders = M3u8UrlShape.headersWithHlsReferer(headers, mediaUri)
        var total = 0L
        var done = 0
        val startedAt = clock.nowMs()
        for (seg in segments) {
            if (done >= count) break
            val uri = HlsManifestParser.resolve(mediaUri, seg.uri)
            val len = cache.contentLength(uri)
            if (len > 0 && cache.isCached(uri, 0L, len)) {
                // 已缓存不占「预取 3 片」名额：继续取后面的未缓存分片，最大化切集收益
                continue
            }
            val bytes = try {
                writer.write(uri, writeHeaders, 0L, MAX_SEGMENT_WRITE_BYTES)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.putDebug("[Prefetch] prefill seg fail error=${e.javaClass.simpleName}")
                0L
            }
            total += bytes
            done++
            // 与当前播放让路：下一集预填同样限速
            val wait = PrefillThrottle.waitMs(
                totalRead = total,
                elapsedMs = clock.nowMs() - startedAt,
            )
            if (wait > 0) delay(wait)
        }
        AppLog.put("[Prefetch] prefill segs=$done bytes=$total")
        total.toInt()
    }

    private fun Snapshot.toInput(segments: List<SegmentPrefetchPolicy.Segment>) =
        SegmentPrefetchPolicy.Input(
            segments = segments,
            currentPositionMs = positionMs,
            bufferedPositionMs = bufferedPositionMs,
            durationMs = durationMs,
            speed = speed,
            isLive = isLive,
            hasEndList = true,
            hasByteRange = false,
            hasDrm = hasDrm,
            isMetered = isMetered,
            videoCacheEnabled = videoCacheEnabled,
            prefetchEnabled = prefetchEnabled,
            buffering = buffering,
            paused = paused,
            foreground = foreground,
            cacheSizeBytes = cacheSizeBytes,
            bitrateBps = bitrateBps,
            bandwidthEstimateBps = bandwidthEstimateBps,
        )

    // ===================== 生产端口实现 =====================

    private object DefaultPrefetchCache : PrefetchCache {
        override fun isCached(key: String, position: Long, length: Long): Boolean =
            kotlin.runCatching { ExoPlayerHelper.cache.isCached(key, position, length) }.getOrDefault(false)

        override fun contentLength(key: String): Long = kotlin.runCatching {
            ContentMetadata.getContentLength(ExoPlayerHelper.cache.getContentMetadata(key))
        }.getOrDefault(0L)

        override fun removeResource(key: String) {
            kotlin.runCatching { ExoPlayerHelper.cache.removeResource(key) }
        }

        override fun removeSpan(key: String, position: Long, length: Long) {
            kotlin.runCatching {
                val spans = ExoPlayerHelper.cache.getCachedSpans(key)
                val target = spans.firstOrNull { it.position == position && it.length == length }
                    ?: spans.firstOrNull {
                        it.length > 0 && it.position <= position && it.position + it.length >= position + length
                    }
                if (target != null) ExoPlayerHelper.cache.removeSpan(target)
            }
        }

        override fun cachedSpans(key: String): List<CachedSpan> = kotlin.runCatching {
            ExoPlayerHelper.cache.getCachedSpans(key)
                .filter { it.length > 0 }
                .map { CachedSpan(position = it.position, length = it.length) }
        }.getOrDefault(emptyList())
    }

    private object DefaultSegmentWriter : SegmentWriter {
        override suspend fun write(
            uri: String,
            headers: Map<String, String>,
            position: Long,
            maxBytes: Long,
        ): Long = withContext(Dispatchers.IO) {
            val upstream = OkHttpDataSource.Factory(videoStreamClient)
                .setUserAgent(ExoPlayerHelper.BROWSER_UA)
                .setDefaultRequestProperties(headers)
            val factory = CacheDataSource.Factory()
                .setCache(ExoPlayerHelper.cache)
                .setUpstreamDataSourceFactory(upstream)
                .setCacheReadDataSourceFactory(FileDataSource.Factory())
                .setCacheWriteDataSinkFactory(
                    CacheDataSink.Factory()
                        .setCache(ExoPlayerHelper.cache)
                        .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)
                )
            val length = if (maxBytes <= 0L || maxBytes >= Long.MAX_VALUE / 2) {
                C.LENGTH_UNSET.toLong()
            } else {
                maxBytes
            }
            val dataSpec = DataSpec(Uri.parse(uri), position, length, null)
            val dataSource = factory.createDataSource()
            var total = 0L
            try {
                dataSource.open(dataSpec)
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = dataSource.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT || read <= 0) break
                    total += read
                    if (length != C.LENGTH_UNSET.toLong() && total >= length) break
                }
            } finally {
                kotlin.runCatching { dataSource.close() }
            }
            total
        }
    }

    private object DefaultManifestFetcher : ManifestFetcher {
        override suspend fun fetch(uri: String, headers: Map<String, String>): String? =
            withContext(Dispatchers.IO) {
                try {
                    val builder = okhttp3.Request.Builder()
                        .url(uri)
                        .header("User-Agent", ExoPlayerHelper.BROWSER_UA)
                    headers.forEach { (k, v) -> builder.header(k, v) }
                    videoStreamClient.newCall(builder.build()).execute().use { response ->
                        if (!response.isSuccessful) return@withContext null
                        val body = response.body ?: return@withContext null
                        val source = body.source()
                        source.request(MAX_MANIFEST_BYTES.toLong())
                        val buffer = source.buffer
                        val size = minOf(buffer.size, MAX_MANIFEST_BYTES.toLong()).toInt()
                        if (size <= 0) null else buffer.clone().readUtf8(size.toLong())
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    AppLog.putDebug("[Prefetch] manifest fetch fail error=${e.javaClass.simpleName}")
                    null
                }
            }
    }
}
