package io.legado.app.help.exoplayer

import io.legado.app.constant.AppLog
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
import java.util.concurrent.atomic.AtomicInteger
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
        /** 内存桶当前深度（毫秒），用于磁盘池 NEAR 分区；0=未知 */
        val bucketDepthMs: Long = 0L,
        /** 本集已写入磁盘池的字节数（应用线程维护的累计值） */
        val episodeWrittenBytes: Long = 0L,
        /** 本会话是否已有有效实测吞吐样本（升档门槛） */
        val hasMeasuredSample: Boolean = false,
        /** 上一次档位（自调档滞回） */
        val previousTier: SegmentPrefetchPolicy.NetworkTier? = null,
        /** 距上次档位切换的毫秒数 */
        val msSinceTierSwitch: Long = 0L,
        /** 是否允许整集全量（低电/过热/近 30s 卡顿时为 false） */
        val allowFullEpisode: Boolean = true,
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
    private const val MAX_CONSECUTIVE_FAILURES = 3
    private const val CIRCUIT_COOLDOWN_MS = 60_000L
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

    /** 已完成（取到片尾且全部命中）的清单 URI 集合；双清单都完成 ⇒ 本集预取完成 */
    private val completedPlaylists = ConcurrentHashMap.newKeySet<String>()

    /** 本集已写入字节（会话内累计，用于池子余量与整集判定） */
    private val episodeWrittenBytes = AtomicLong(0L)

    /** 已做过定向清理的会话号（每会话一次，AD-33） */
    @Volatile
    private var governedSessionId: Long = 0L

    /** 最近一次计算的档位（供上层读取；不直接读 Player） */
    @Volatile
    private var lastTier: SegmentPrefetchPolicy.NetworkTier? = null

    /** 熔断冷却截止时间（elapsed 毫秒）；0=未冷却 */
    private val circuitCooldownUntil = AtomicLong(0L)

    /** 熔断次数（1=冷却，2=本会话停用） */
    private val circuitStrikes = AtomicInteger(0)

    /** 本会话永久停用（第二次熔断） */
    private val circuitDisabled = AtomicBoolean(false)

    /** doneKeys 上限（防整集模式下无界增长） */
    private const val MAX_DONE_KEYS = 4096

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
        completedPlaylists.clear()
        episodeWrittenBytes.set(0L)
        governedSessionId = 0L
        lastTier = null
        circuitCooldownUntil.set(0L)
        circuitStrikes.set(0)
        circuitDisabled.set(false)
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
            completedPlaylists.clear()
            episodeWrittenBytes.set(0L)
            governedSessionId = 0L
            lastTier = null
            circuitCooldownUntil.set(0L)
            circuitStrikes.set(0)
            circuitDisabled.set(false)
            consecutiveFailures.set(0)
            circuitOpen.set(false)
            lastRoundAt.set(0L)
            lastGateReason = null
            segDoneLogCount = 0
            planLogCount = 0
        }
    }

    /** 当前档位（供倒计时预热读取；null=尚未计算） */
    fun lastTier(): SegmentPrefetchPolicy.NetworkTier? = lastTier

    /** 本集已写入字节（测试/日志用） */
    fun episodeWrittenBytes(): Long = episodeWrittenBytes.get()

    /**
     * 本集预取是否已完成（双清单都到片尾且全部命中）。
     *
     * @param sessionId 会话号；与当前会话不一致时返回 false（防旧实例误报）
     */
    fun isEpisodeComplete(sessionId: Long): Boolean {
        if (sessionId != currentSessionId) return false
        val playlists = learned.values.filter { it.segments.isNotEmpty() }
        if (playlists.isEmpty()) return false
        val playlistsToCheck = playlists.take(MAX_PLAYLISTS)
        return playlistsToCheck.isNotEmpty() && playlistsToCheck.all { completedPlaylists.contains(it.uri) }
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
        completedPlaylists.clear()
        episodeWrittenBytes.set(0L)
        governedSessionId = 0L
        lastTier = null
        ManifestObserver.clear()
        circuitOpen.set(false)
        circuitStrikes.set(0)
        circuitDisabled.set(false)
        circuitCooldownUntil.set(0L)
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

    /** 长视频回看清理（HLS：按分片 key 删资源）——实现见 [LearnedBackCacheTrimmer] */
    fun trimBackCache(positionMs: Long, windowMs: Long): Int =
        LearnedBackCacheTrimmer.trim(cache, learnedSpans(), positionMs, windowMs)

    /** 长视频回看清理（直链：单 key，按 span 精确删）——实现见 [LearnedBackCacheTrimmer] */
    fun trimBackCacheSingleKey(
        key: String,
        positionMs: Long,
        windowMs: Long,
        bitrateBps: Long,
    ): Int = LearnedBackCacheTrimmer.trimSingleKey(cache, key, positionMs, windowMs, bitrateBps)

    /** 主动触发一轮补水（5s 定时调用；force=false 时受最小间隔约束） */
    fun topUp(force: Boolean = false) {
        val s = snapshot ?: return
        if (s.sessionId != currentSessionId) return
        val now = clock.nowMs()
        if (circuitDisabled.get()) return
        if (circuitOpen.get()) {
            val until = circuitCooldownUntil.get()
            if (until > 0L && now >= until) {
                circuitOpen.set(false)
                circuitCooldownUntil.set(0L)
                consecutiveFailures.set(0)
                AppLog.put("[Prefetch] circuit reopened after cooldown")
            } else {
                return
            }
        }
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

    /** 档位切换时间（自调档滞回） */
    @Volatile
    private var lastTierAt = 0L

    /**
     * 解析当前档位（自调档唯一入口）。
     *
     * 降档立即生效；升档需「本会话有实测样本 + 距上次切换 ≥30s」。
     */
    private fun resolvedTier(s: Snapshot): SegmentPrefetchPolicy.NetworkTier {
        val now = clock.nowMs()
        val input = s.toInput(emptyList()).copy(
            previousTier = lastTier,
            msSinceTierSwitch = if (lastTier == null) 0L else (now - lastTierAt).coerceAtLeast(0L),
        )
        val tier = SegmentPrefetchPolicy.resolveTier(input)
        if (tier != lastTier) {
            AppLog.put(
                "[Prefetch] tier ${lastTier?.name ?: "null"} -> ${tier.name} " +
                    "bw=${s.bandwidthEstimateBps} measured=${s.hasMeasuredSample}"
            )
            lastTier = tier
            lastTierAt = now
        }
        return tier
    }

    private suspend fun runRound(s: Snapshot) {
        val headers = s.headers
        val tier = resolvedTier(s)
        val playlists = learnPlaylists(s, headers)
        if (playlists.isEmpty()) {
            // video-buffer-prefetch-v2 E1 / 本期 AD-34：直链 MP4/FLV（支持 Range）预取
            if (!tryDirectRange(s, headers, tier)) recordFailure("no_media_playlist")
            return
        }
        // 完成度以第一份（视频）清单为准
        run {
            val probe = playlists.first()
            val probeInput = s.toInput(probe.segments).copy(
                hasEndList = probe.hasEndList,
                hasByteRange = probe.hasByteRange,
            )
            var state = SegmentPrefetchPolicy.stateOf(probeInput, tier)
            // R45 / AD-33：整集装不下时，先定向清理「非当前视频」的旧 key（每会话一次）
            if (state == SegmentPrefetchPolicy.PrefetchState.FILL_TO_CAPACITY &&
                probeInput.allowFullEpisode &&
                probeInput.bitrateBps > 0L &&
                governedSessionId != s.sessionId
            ) {
                governedSessionId = s.sessionId
                val need = SegmentPrefetchPolicy.episodeBytes(
                    probeInput.durationMs,
                    probeInput.bitrateBps,
                )
                val free = SegmentPrefetchPolicy.poolFreeBytes(
                    s.cacheSizeBytes,
                    episodeWrittenBytes.get(),
                )
                if (need > free) {
                    val protectedKeys = learned.values
                        .flatMap { playlist -> playlist.segments.map { it.uri } }
                        .toSet() + listOfNotNull(s.directUri)
                    CacheGovernor.govern(protectedKeys, need - free, s.cacheSizeBytes)
                }
            }
            if (state == SegmentPrefetchPolicy.PrefetchState.COMPLETE) {
                playlists.forEach { completedPlaylists.add(it.uri) }
                if (lastGateReason != "episode_complete") {
                    lastGateReason = "episode_complete"
                    AppLog.put(
                        "[Prefetch] gate reason=episode_complete written=${episodeWrittenBytes.get()} " +
                            "tier=${tier.name}"
                    )
                }
                return
            }
            if (lastGateReason == "episode_complete") {
                lastGateReason = null
                AppLog.put("[Prefetch] gate open after seek/resume")
            }

            val budget = SegmentPrefetchPolicy.budget(probeInput, tier)
            if (budget.state == SegmentPrefetchPolicy.BudgetState.PAUSED) {
                if (lastGateReason != "low_buffer") {
                    lastGateReason = "low_buffer"
                    AppLog.put(
                        "[Prefetch] gate reason=low_buffer " +
                            "bufMs=${(s.bufferedPositionMs - s.positionMs).coerceAtLeast(0)}"
                    )
                }
                return
            }
            // 清单并行 × 清单内并发：总量仍受档位预算约束
            val totalTasks = (playlists.size * tier.concurrency).coerceAtLeast(1)
            val perTaskBudget = (budget.bytesPerSec / totalTasks).coerceAtLeast(1L)

            coroutineScope {
                playlists.map { playlist ->
                    async { writePlaylist(s, playlist, headers, tier, perTaskBudget) }
                }.awaitAll()
            }
        }
    }

    /**
     * 直链 MP4/FLV 的 Range 预取（E1 / 本期 AD-34）——实现见 [DirectRangePrefetcher]。
     */
    private suspend fun tryDirectRange(
        s: Snapshot,
        headers: Map<String, String>,
        tier: SegmentPrefetchPolicy.NetworkTier,
    ): Boolean {
        val ok = DirectRangePrefetcher.prefetch(
            snapshot = s,
            headers = headers,
            tier = tier,
            cache = cache,
            writer = writer,
            clock = clock,
            episodeWritten = episodeWrittenBytes,
            currentSessionId = currentSessionId,
        )
        if (ok) consecutiveFailures.set(0)
        return ok
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
        tier: SegmentPrefetchPolicy.NetworkTier,
        budgetBps: Long,
    ) {
        val input = s.toInput(playlist.segments).copy(
            hasEndList = playlist.hasEndList,
            hasByteRange = playlist.hasByteRange,
        )
        val gate = SegmentPrefetchPolicy.gate(input)
        if (gate != null) return
        val window = SegmentPrefetchPolicy.selectWindow(input, tier) ?: return
        if (window.isEmpty) return
        // HLS 分片请求头：与播放一致（已有 Referer 不覆盖）
        val writeHeaders = M3u8UrlShape.headersWithHlsReferer(headers, playlist.uri)
        val poolFree = SegmentPrefetchPolicy.poolFreeBytes(
            s.cacheSizeBytes,
            episodeWrittenBytes.get(),
        )
        if (planLogCount < 30 || planLogCount % 10 == 0) {
            AppLog.put(
                "[Prefetch] plan tier=${tier.name} state=${window.state} segs=${window.count} " +
                    "startMs=${window.startMs} endMs=${window.endMs} maxBytes=${window.maxBytes} " +
                    "budgetBps=$budgetBps poolFree=$poolFree conc=${tier.concurrency} " +
                    "bucketMs=${s.bucketDepthMs} speed=${s.speed} " +
                    "pl=${playlist.uri.substringAfterLast('/').take(24)}"
            )
        }
        planLogCount++

        // 按估算字节数截断，避免只写半个分片（半个分片对播放无收益）
        val effectiveBitrate = if (s.bitrateBps > 0L) {
            s.bitrateBps
        } else {
            SegmentPrefetchPolicy.ESTIMATE_BITRATE_BPS
        }
        val segBytes = { seg: SegmentPrefetchPolicy.Segment ->
            (seg.durationMs / 1000.0 * effectiveBitrate / 8.0 * 1.3).toLong()
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

        // 磁盘池 NEAR 优先：近端（即将进内存桶）先写，FAR（储备）后写
        val bucketDepth = if (s.bucketDepthMs > 0L) {
            s.bucketDepthMs
        } else {
            tier.windowMs.coerceAtMost(60_000L)
        }
        val nearEndMs = s.bufferedPositionMs + bucketDepth
        val starts = LongArray(playlist.segments.size)
        var acc = 0L
        for (i in playlist.segments.indices) {
            starts[i] = acc
            acc += playlist.segments[i].durationMs.coerceAtLeast(0L)
        }
        val ordered = indexes.sortedWith(
            compareBy({ if (starts[it] < nearEndMs) 0 else 1 }, { it })
        )
        val nearCount = ordered.count { starts[it] < nearEndMs }

        var written = 0L
        var writtenSegs = 0
        var skipped = 0

        for (batch in ordered.chunked(tier.concurrency.coerceAtLeast(1))) {
            if (s.sessionId != currentSessionId) return
            coroutineScope {
                batch.map { i ->
                    async {
                        if (s.sessionId != currentSessionId) {
                            return@async Triple(i, 0L, false)
                        }
                        val seg = playlist.segments[i]
                        if (doneKeys.contains(seg.uri)) {
                            return@async Triple(i, 0L, true)
                        }
                        val contentLength = cache.contentLength(seg.uri)
                        if (contentLength > 0L && cache.isCached(seg.uri, 0L, contentLength)) {
                            return@async Triple(i, 0L, true)
                        }
                        val taskStart = clock.nowMs()
                        var taskWritten = 0L
                        val bytes = try {
                            writer.write(seg.uri, writeHeaders, 0L, MAX_SEGMENT_WRITE_BYTES)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLog.putDebug("[Prefetch] seg fail idx=$i error=${e.javaClass.simpleName}")
                            recordFailure("write")
                            return@async Triple(i, 0L, false)
                        }
                        taskWritten += bytes
                        if (taskWritten > 0L) {
                            val wait = PrefillThrottle.waitMs(
                                totalRead = taskWritten,
                                elapsedMs = clock.nowMs() - taskStart,
                                limitBytesPerSec = budgetBps,
                            )
                            if (wait > 0L) delay(wait)
                        }
                        Triple(i, bytes, bytes > 0L)
                    }
                }.awaitAll().forEach { (i, bytes, ready) ->
                    if (!ready) return@forEach
                    if (bytes <= 0L) {
                        skipped++
                        return@forEach
                    }
                    val uri = playlist.segments[i].uri
                    if (doneKeys.size < MAX_DONE_KEYS) doneKeys.add(uri)
                    written += bytes
                    writtenSegs++
                    episodeWrittenBytes.addAndGet(bytes)
                    consecutiveFailures.set(0)
                    if (segDoneLogCount < 30 || segDoneLogCount % 10 == 0) {
                        AppLog.putDebug("[Prefetch] seg_done idx=$i bytes=$bytes")
                    }
                    segDoneLogCount++
                }
            }
            if (circuitOpen.get()) return
        }

        // 完成标记：窗口已覆盖到片尾，且窗口内分片全部就绪
        val fullyCovered = window.endIndexExclusive >= playlist.segments.size
        val allReady = indexes.all { i ->
            val uri = playlist.segments[i].uri
            doneKeys.contains(uri) || run {
                val len = cache.contentLength(uri)
                len > 0L && cache.isCached(uri, 0L, len)
            }
        }
        if (fullyCovered && allReady) {
            completedPlaylists.add(playlist.uri)
        } else {
            completedPlaylists.remove(playlist.uri)
        }

        AppLog.put(
            "[Prefetch] window_done tier=${tier.name} state=${window.state} segs=$writtenSegs " +
                "skipped=$skipped bytes=$written near=$nearCount far=${ordered.size - nearCount} " +
                "poolFree=$poolFree budgetBps=$budgetBps " +
                "pl=${playlist.uri.substringAfterLast('/').take(24)}"
        )
    }

    private fun recordFailure(reason: String) {
        val count = consecutiveFailures.incrementAndGet()
        if (count < MAX_CONSECUTIVE_FAILURES) return
        if (circuitOpen.compareAndSet(false, true)) {
            val strikes = circuitStrikes.incrementAndGet()
            if (strikes >= 2) {
                // 冷却后再次达到阈值：本会话停用
                circuitDisabled.set(true)
                circuitCooldownUntil.set(0L)
                AppLog.put("[Prefetch] circuit disabled reason=$reason failCount=$count strikes=$strikes")
            } else {
                // 第一次：降并发 + 冷却 60s（冷却后自动重试）
                circuitCooldownUntil.set(clock.nowMs() + CIRCUIT_COOLDOWN_MS)
                AppLog.put(
                    "[Prefetch] circuit open reason=$reason failCount=$count " +
                        "cooldownMs=$CIRCUIT_COOLDOWN_MS"
                )
            }
        }
    }

    /**
     * 下一集预填：解析清单并预取 [count] 个尚未缓存的分片（增量语义）。
     *
     * @param limitBytesPerSec 限速；调用方应传当前档位预算（倒计时预热不再落默认 1.5MB/s）
     * @return 写入字节数；失败返回 0。清单本身不写缓存。
     */
    suspend fun prefillSegments(
        playlistUri: String,
        headers: Map<String, String>,
        count: Int = 3,
        limitBytesPerSec: Long = PrefillThrottle.DEFAULT_LIMIT_BYTES_PER_SEC,
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
            // 与当前播放让路：下一集预填同样限速（按调用方档位预算）
            val wait = PrefillThrottle.waitMs(
                totalRead = total,
                elapsedMs = clock.nowMs() - startedAt,
                limitBytesPerSec = limitBytesPerSec,
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
            bucketDepthMs = bucketDepthMs,
            episodeWrittenBytes = episodeWrittenBytes,
            hasMeasuredSample = hasMeasuredSample,
            previousTier = previousTier,
            msSinceTierSwitch = msSinceTierSwitch,
            allowFullEpisode = allowFullEpisode,
        )

}
