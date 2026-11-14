package io.legado.app.help.exoplayer

import io.legado.app.constant.AppLog
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
 * - 不直接读 Player（跨线程 1004），只读应用线程写入的 [PrefetchSnapshot]；
 * - 写缓存异常按失败计数（不重试、不崩溃），连续 3 次熔断；
 * - 端口可注入（[SegmentWriter]/[PrefetchCache]/[ManifestFetcher]/[PrefetchClock]），便于 JVM 单测。
 */
object VideoSegmentPrefetcher {

    /** 一轮预取的结果（AD-38 续轮判据） */
    private data class RoundResult(
        /** 本轮实际写入字节数 */
        val writtenBytes: Long,
        /** 本集是否已完成（双清单都到片尾且全部就绪） */
        val complete: Boolean,
    )

    private const val MAX_PLAYLISTS = 2
    private const val PLAYLIST_TTL_MS = 60_000L
    private const val MAX_CONSECUTIVE_FAILURES = 3
    private const val CIRCUIT_COOLDOWN_MS = 60_000L
    private const val ROUND_MIN_INTERVAL_MS = 1_000L
    private const val SEEK_DELAY_MS = 1_000L

    /** AD-38：有写入且未完成时的续轮间隔（让「尽快缓冲完」不被 5s tick 限制） */
    private const val CONTINUE_DELAY_MS = 500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var snapshot: PrefetchSnapshot? = null
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

    /**
     * 磁盘预取游标（清单 URI → 媒体毫秒）：记录「最后一个连续就绪分片的末尾」。
     *
     * AD-35：写入磁盘池**不会**推进 `player.bufferedPosition`（那是内存桶），
     * 若窗口起点继续绑缓冲头，命中后就原地打转、零写入 —— 真机表现为「缓冲一小段就停」。
     * 实现见 [PrefetchCursorTracker]。
     */
    private val cursors = PrefetchCursorTracker(cache = { cache }, doneKeys = { doneKeys })

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
        cursors.clear()
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
            DirectRangePrefetcher.reset()
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
     * 磁盘预取游标（媒体毫秒，多清单取最小值）——供 UI 展示「就绪进度」。
     *
     * 只统计已建立游标的清单：视频/音频双清单时取小值，避免用视频进度掩盖音频缺口；
     * 无游标/会话不匹配返回 0（调用方与 bufferedPosition 取 max，不改变原语义）。
     */
    fun prefetchCursorMs(sessionId: Long): Long {
        if (sessionId != currentSessionId) return 0L
        val playlistUris = learned.values
            .filter { it.segments.isNotEmpty() }
            .map { it.uri }
        if (playlistUris.isEmpty()) return 0L
        return cursors.minCursor(playlistUris)
    }

    /**
     * 播放状态更新（应用线程）。
     *
     * 多实例/相邻页（FEED）场景：只允许「正在播放（paused=false）」的实例接管会话，
     * 避免非活跃页 prepare 后把活跃页的预取会话顶掉（审计 H8）。
     */
    @Synchronized
    internal fun updateSnapshot(value: PrefetchSnapshot) {
        snapshot = value
        if (!value.paused && value.sessionId != currentSessionId) {
            currentSessionId = value.sessionId
            learned.clear()
            masterUris.clear()
            doneKeys.clear()
            cursors.clear()
            consecutiveFailures.set(0)
            circuitOpen.set(false)
        }
    }

    /** READY 时立即补水 */
    fun onReady() {
        topUp(force = true)
    }

    /** seek：取消在途 + 清空游标 + 1s 后按新位置重算（防连续拖动风暴） */
    fun onSeek() {
        seekJob?.cancel()
        roundJob?.cancel()
        // AD-35：跳转后原游标对应的「已就绪区间」不再连续，重新从新缓冲头起算
        cursors.clear()
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
        cursors.clear()
        DirectRangePrefetcher.reset()
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
    fun learnedSpans(): List<RollingBackCache.Span> =
        LearnedBackCacheTrimmer.spans(learned.values)

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
                // AD-47：带上下文，便于真机仅凭日志判因（暂停/缓冲/移动网络/开关）
                AppLog.put("[Prefetch] gate reason=$globalGate ${PrefetchGateDiag.describe(s)}")
            }
            return
        }
        if (lastGateReason != null) {
            lastGateReason = null
            AppLog.put("[Prefetch] gate open ${PrefetchGateDiag.describe(s)}")
        }

        lastRoundAt.set(now)
        roundJob = scope.launch {
            var result = RoundResult(0L, false)
            try {
                result = runRound(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.putDebug("[Prefetch] round failed: ${e.javaClass.simpleName}")
            } finally {
                // AD-38：必须先把 roundJob 置空再续轮，否则下一轮会被「上一轮仍在跑」拦掉
                val self = kotlin.coroutines.coroutineContext[Job]
                synchronized(lock) {
                    if (roundJob === self) roundJob = null
                }
            }
            // 有进展且未完成 ⇒ 立即续轮；零写入不续轮（防忙循环），
            // 完成/池满/门禁关闭时 runRound 自然返回零写入或 complete ⇒ 自动终止
            if (result.writtenBytes > 0L && !result.complete && s.sessionId == currentSessionId) {
                delay(CONTINUE_DELAY_MS)
                if (s.sessionId == currentSessionId) topUp(force = true)
            }
        }
    }

    /** 全局门禁（无需清单即可判定） */
    private fun globalGate(s: PrefetchSnapshot): String? = when {
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
    private fun resolvedTier(s: PrefetchSnapshot): SegmentPrefetchPolicy.NetworkTier {
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

    private suspend fun runRound(s: PrefetchSnapshot): RoundResult {
        val headers = s.headers
        val tier = resolvedTier(s)
        val playlists = learnPlaylists(s, headers)
        if (playlists.isEmpty()) {
            // video-buffer-prefetch-v2 E1 / 本期 AD-34：直链 MP4/FLV（支持 Range）预取
            val wrote = tryDirectRange(s, headers, tier)
            if (wrote < 0L) {
                // -1 = 不适用（无直链/不支持 Range/池满/让路），不计熔断
                return RoundResult(0L, complete = false)
            }
            if (wrote == 0L) recordFailure("direct_range")
            // 直链无分片游标，是否完成由 UI 侧按 buffered 判定
            return RoundResult(wrote, complete = false)
        }
        // 完成度以第一份（视频）清单为准
        val probe = playlists.first()
        val probeInput = s.toInput(probe.segments).copy(
            hasEndList = probe.hasEndList,
            hasByteRange = probe.hasByteRange,
            // AD-35/AD-37：锚点用磁盘游标，池余量扣真实占用
            anchorMs = cursors.cursorOf(probe.uri) ?: -1L,
            episodeWrittenBytes = episodeWrittenBytes.get(),
            cacheUsedBytes = cache.usedBytes(),
        )
        val state = SegmentPrefetchPolicy.stateOf(probeInput, tier)
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
                SegmentPrefetchPolicy.effectiveRatio(probeInput, tier),
                cache.usedBytes(),
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
            return RoundResult(0L, complete = true)
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
            return RoundResult(0L, complete = false)
        }
        // 清单并行 × 清单内并发：总量仍受档位预算约束
        val totalTasks = (playlists.size * tier.concurrency).coerceAtLeast(1)
        val perTaskBudget = (budget.bytesPerSec / totalTasks).coerceAtLeast(1L)

        val writtenList = coroutineScope {
            playlists.map { playlist ->
                async { writePlaylist(s, playlist, headers, tier, perTaskBudget) }
            }.awaitAll()
        }
        return RoundResult(writtenList.sum(), isEpisodeComplete(s.sessionId))
    }

    /**
     * 直链 MP4/FLV 的 Range 预取（E1 / 本期 AD-34）——实现见 [DirectRangePrefetcher]。
     *
     * @return 实际写入字节数；-1 = 不适用（不参与熔断，也不触发续轮）
     */
    private suspend fun tryDirectRange(
        s: PrefetchSnapshot,
        headers: Map<String, String>,
        tier: SegmentPrefetchPolicy.NetworkTier,
    ): Long {
        val wrote = DirectRangePrefetcher.prefetch(
            snapshot = s,
            headers = headers,
            tier = tier,
            cache = cache,
            writer = writer,
            clock = clock,
            episodeWritten = episodeWrittenBytes,
            currentSessionId = currentSessionId,
        )
        if (wrote > 0L) consecutiveFailures.set(0)
        return wrote
    }

    /** 上次「学不到清单」的诊断串（相同则不再刷日志） */
    private var lastLearnDiag: String? = null

    /** 上次写入侧 gate 拒绝的 key（清单+原因），避免每轮重复打印 */
    private var lastWriteGateKey: String? = null

    private suspend fun learnPlaylists(s: PrefetchSnapshot, headers: Map<String, String>): List<MediaPlaylist> {
        val stats = PlaylistLearnStats()
        val list = learnMediaPlaylists(
            observerUris = ManifestObserver.snapshot(),
            learned = learned,
            masterUris = masterUris,
            fetcher = fetcher,
            headers = headers,
            nowMs = clock.nowMs(),
            maxPlaylists = MAX_PLAYLISTS,
            ttlMs = PLAYLIST_TTL_MS,
            stats = stats,
        )
        // 学不到清单时预取会静默空转（只有 tier 没有 plan）：诊断串变化时打一条，避免刷屏
        if (list.isEmpty()) {
            val diag = stats.toString()
            if (diag != lastLearnDiag) {
                lastLearnDiag = diag
                AppLog.put("[Prefetch] no_playlist $diag")
            }
        } else {
            lastLearnDiag = null
        }
        return list
    }

    /**
     * 写单份清单的预取窗口，并推进磁盘游标。
     *
     * @return 本轮实际写入字节数（AD-38 续轮判据；0 表示无进展）
     */
    private suspend fun writePlaylist(
        s: PrefetchSnapshot,
        playlist: MediaPlaylist,
        headers: Map<String, String>,
        tier: SegmentPrefetchPolicy.NetworkTier,
        budgetBps: Long,
    ): Long {
        val totalMs = playlist.segments.sumOf { it.durationMs.coerceAtLeast(0L) }
        if (totalMs <= 0L) return 0L
        // AD-35：游标 clamp 到清单总时长（TTL 重取后清单变短也不越界）
        val cursorBefore = cursors.cursorOf(playlist.uri)?.coerceIn(0L, totalMs)
        val input = s.toInput(playlist.segments).copy(
            hasEndList = playlist.hasEndList,
            hasByteRange = playlist.hasByteRange,
            // 起点由磁盘游标接管；池余量扣真实占用
            anchorMs = cursorBefore ?: -1L,
            episodeWrittenBytes = episodeWrittenBytes.get(),
            cacheUsedBytes = cache.usedBytes(),
        )
        val gate = SegmentPrefetchPolicy.gate(input)
        if (gate != null) {
            // 静默拒绝会让线上无法判断「预取为什么停了」（2026-11-13 实测吃过这个亏：只有 tier 没有 plan）
            val key = "${playlist.uri.substringAfterLast('/').take(24)}|${gate.reason}"
            if (key != lastWriteGateKey) {
                lastWriteGateKey = key
                AppLog.put(
                    "[Prefetch] skip reason=${gate.reason} " +
                        "pl=${playlist.uri.substringAfterLast('/').take(24)} " +
                        "durMs=${input.durationMs} live=${input.isLive} endList=${input.hasEndList}"
                )
            }
            return 0L
        }
        if (lastWriteGateKey != null) lastWriteGateKey = null
        val window = SegmentPrefetchPolicy.selectWindow(input, tier) ?: return 0L
        if (window.isEmpty) return 0L
        // HLS 分片请求头：与播放一致（已有 Referer 不覆盖）
        val writeHeaders = M3u8UrlShape.headersWithHlsReferer(headers, playlist.uri)
        val poolFree = SegmentPrefetchPolicy.poolFreeBytes(
            s.cacheSizeBytes,
            input.episodeWrittenBytes,
            SegmentPrefetchPolicy.effectiveRatio(input, tier),
            input.cacheUsedBytes,
        )
        if (planLogCount < 30 || planLogCount % 10 == 0) {
            AppLog.put(
                "[Prefetch] plan tier=${tier.name} state=${window.state} segs=${window.count} " +
                    "startMs=${window.startMs} endMs=${window.endMs} maxBytes=${window.maxBytes} " +
                    "budgetBps=$budgetBps poolFree=$poolFree conc=${tier.concurrency} " +
                    "bucketMs=${s.bucketDepthMs} speed=${s.speed} cursor=${cursorBefore ?: -1L} " +
                    "pl=${playlist.uri.substringAfterLast('/').take(24)}"
            )
        }
        planLogCount++

        // 写盘规划：按估算字节截断（避免只写半个分片）+ 磁盘池 NEAR 优先（见 SegmentPlanner）
        val plan = SegmentPlanner.plan(
            segments = playlist.segments,
            window = window,
            bitrateBps = s.bitrateBps,
            bufferedPositionMs = s.bufferedPositionMs,
            bucketDepthMs = s.bucketDepthMs,
            fallbackBucketMs = tier.windowMs.coerceAtMost(60_000L),
        ) ?: return 0L
        val starts = plan.starts
        val indexes = plan.indexes
        val ordered = plan.ordered
        val nearCount = plan.nearCount

        var written = 0L
        var writtenSegs = 0
        var skipped = 0
        // 轮内推进用的平行数组（每批复用，避免反复重建）
        val durations = LongArray(playlist.segments.size) { playlist.segments[it].durationMs }

        for (batch in ordered.chunked(tier.concurrency.coerceAtLeast(1))) {
            if (s.sessionId != currentSessionId) return written
            coroutineScope {
                batch.map { i ->
                    async {
                        if (s.sessionId != currentSessionId) {
                            return@async Triple(i, 0L, false)
                        }
                        val seg = playlist.segments[i]
                        if (cursors.isReady(seg)) {
                            return@async Triple(i, 0L, true)
                        }
                        val taskStart = clock.nowMs()
                        var taskWritten = 0L
                        // AD-43：字节区间分片必须带 offset 读，且只读到区间长度为止
                        val readOffset = if (seg.isRange) seg.rangeOffset.coerceAtLeast(0L) else 0L
                        val readMax = if (seg.isRange) seg.rangeLength else MAX_SEGMENT_WRITE_BYTES
                        val bytes = try {
                            writer.write(seg.uri, writeHeaders, readOffset, readMax)
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
                    val seg = playlist.segments[i]
                    // doneKeys 用 cacheKey：字节区间分片同 URI 多片，必须带区间去重（AD-43）
                    if (doneKeys.size < MAX_DONE_KEYS) doneKeys.add(seg.cacheKey)
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
            // R2/AD-35：每批写完后立即推进游标 —— 进度不必等整轮结束（真机「停住」的观感来源）
            advanceCursor(playlist, starts, durations, window, totalMs)
            if (circuitOpen.get()) break
        }

        // 轮末再推进一次（覆盖最后一批/被截断的边界），并落最终位置
        advanceCursor(playlist, starts, durations, window, totalMs)
        val cursorAfter = cursors.cursorOf(playlist.uri) ?: 0L

        // 完成标记：最后一个「本轮规划」分片已到片尾，且规划分片全部就绪
        // （不能只看 window 覆盖，字节截断会让 window 到片尾而 indexes 没到）
        val coveredToEnd = (indexes.maxOrNull() ?: -1) >= playlist.segments.size - 1
        val allReady = indexes.all { cursors.isReady(playlist.segments[it]) }
        if (coveredToEnd && allReady) {
            completedPlaylists.add(playlist.uri)
        } else {
            completedPlaylists.remove(playlist.uri)
        }

        AppLog.put(
            "[Prefetch] window_done tier=${tier.name} state=${window.state} segs=$writtenSegs " +
                "skipped=$skipped bytes=$written near=$nearCount far=${ordered.size - nearCount} " +
                "poolFree=$poolFree budgetBps=$budgetBps cursor=$cursorAfter " +
                "pl=${playlist.uri.substringAfterLast('/').take(24)}"
        )
        return written
    }

    /** 轮内推进游标（整轮未结束也可见进度）；变化时落日志，实现见 [PrefetchCursorTracker.advanceFrom] */
    private fun advanceCursor(
        playlist: MediaPlaylist,
        starts: LongArray,
        durations: LongArray,
        window: SegmentPrefetchPolicy.Window,
        totalMs: Long,
    ) = cursors.advanceFrom(
        playlistUri = playlist.uri,
        starts = starts,
        durations = durations,
        segments = playlist.segments,
        fromIndex = window.startIndex,
        toIndexExclusive = window.endIndexExclusive,
        totalMs = totalMs,
    ) { previous, next ->
        AppLog.put(
            "[Prefetch] cursor pl=${playlist.uri.substringAfterLast('/').take(24)} " +
                "from=${previous ?: -1L} to=$next totalMs=$totalMs state=${window.state}"
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
     * @return 写入字节数；失败返回 0。清单本身不写缓存。实现见 [SegmentPrefiller]。
     */
    suspend fun prefillSegments(
        playlistUri: String,
        headers: Map<String, String>,
        count: Int = 3,
        limitBytesPerSec: Long = PrefillThrottle.DEFAULT_LIMIT_BYTES_PER_SEC,
    ): Int = SegmentPrefiller.prefill(
        playlistUri = playlistUri,
        headers = headers,
        count = count,
        limitBytesPerSec = limitBytesPerSec,
        fetcher = fetcher,
        writer = writer,
        cache = cache,
        clock = clock,
    )

    private fun PrefetchSnapshot.toInput(segments: List<SegmentPrefetchPolicy.Segment>) =
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
            // 用本单例的实时累计值（快照里的同名字段可能滞后一个 tick，续轮时更明显）
            episodeWrittenBytes = this@VideoSegmentPrefetcher.episodeWrittenBytes.get(),
            hasMeasuredSample = hasMeasuredSample,
            previousTier = previousTier,
            msSinceTierSwitch = msSinceTierSwitch,
            allowFullEpisode = allowFullEpisode,
        )

}
