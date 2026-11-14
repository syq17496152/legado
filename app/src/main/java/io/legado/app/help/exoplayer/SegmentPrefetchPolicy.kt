package io.legado.app.help.exoplayer

/**
 * 分片预取策略（纯函数，可 JVM 直测）。
 *
 * 设计依据：
 * - docs/specs/video-buffer-prefetch-v2/design.md（基线 §1.3/§1.12/§1.15/§1.19）
 * - docs/specs/video-buffer-aggressive-and-countdown-warmup/design.md（本期 §1.2~§1.11、AD-19~AD-31）
 *
 * 职责边界：
 * - 只做「能不能预取 / 预取哪一段 / 预算多少 / 池子分哪两区」的决策；
 * - 不做任何网络、缓存、线程操作（由 [VideoSegmentPrefetcher] 执行）。
 *
 * 本期新增（相对基线）：
 * 1. 五档网络自适应（WEAK/STANDARD/FAST/ULTRA/EXTREME），网越好窗口/字节比/预算/并发越大；
 * 2. 整集完成度三态（RUNNING/TAIL/COMPLETE）+ 整集全量（FULL_EPISODE/FILL_TO_CAPACITY）；
 * 3. 滑动窗口以播放位置为锚点，远端可爬坡到「整集剩余 / 池子余量」；
 * 4. 磁盘池 NEAR（近端补给）/ FAR（远端储备）分区；
 * 5. 回看额度 35% → 15%，磁盘优先给未来分片。
 *
 * 不变式：
 * - WEAK/STANDARD 的窗口/字节比/预算与基线一致（防弱网回归）；
 * - 窗口起点永远在缓冲头之后 [SKIP_SEGMENTS] 片（避免与播放 Loader 抢同一分片）；
 * - 字节上限优先于时间窗口；
 * - 缓冲水位 < [PAUSE_BUFFER_MS] 完全暂停，< [LOW_BUFFER_MS] 降速。
 */
object SegmentPrefetchPolicy {

    /** 跳过缓冲头之后的近端分片数 */
    const val SKIP_SEGMENTS = 2

    /** 非全量档位窗口上限（整集模式不受此限） */
    const val MAX_WINDOW_MS = 600_000L

    /** 整集模式兜底上限：防止异常超长时长导致无限遍历（4 小时） */
    const val FULL_WINDOW_GUARD_MS = 4 * 60 * 60 * 1000L

    /**
     * 单轮预取最多覆盖的媒体时长（3 分钟）。
     *
     * 为什么必须限制：整集/短视频模式曾一次规划整集（可达数十分钟），
     * 受预算限速后单轮可写数分钟；期间游标/灰条不动、5s tick 被在飞任务拦掉，
     * 用户观感就是「缓冲一会就不动了」。分轮推进 + 每轮结束续轮，进度始终可见。
     */
    const val MAX_ROUND_MEDIA_MS = 3 * 60 * 1000L

    /** 窗口兜底下限（避免全部为 0） */
    const val MIN_WINDOW_MS = 1_000L

    /**
     * 短视频阈值（2 分钟）。
     *
     * 原 20 分钟会把 10 分钟剧集也当「短视频」整集规划，与整集模式叠加后
     * 单轮窗口被拉到整集 → 见 [MAX_ROUND_MEDIA_MS] 的说明。
     */
    const val SHORT_VIDEO_THRESHOLD_MS = 2 * 60 * 1000L

    /** 短视频字节比上限（防止短视频写满池子） */
    const val BYTE_RATIO_SHORT = 0.25

    /** 长视频前向预取字节比上限（覆盖缓存容量） */
    const val BYTE_RATIO_LONG = 0.65

    /** 回看窗口字节比（基线 0.35 → 0.15） */
    const val BYTE_RATIO_BACK_CACHE = 0.15

    /** 预算下限（避免除零/过低导致无限等待） */
    const val MIN_BUDGET_BPS = 100_000L

    /** 带宽估计缺失时的保守值 */
    const val FALLBACK_BANDWIDTH_BPS = 300_000L

    /** 降速水位 */
    const val LOW_BUFFER_MS = 8_000L

    /** 暂停水位 */
    const val PAUSE_BUFFER_MS = 4_000L

    /** 弱网阈值（低于该值即使水位健康也降速） */
    const val WEAK_BANDWIDTH_BPS = 2_000_000L

    /** 码率未知时的保守估算（bps） */
    const val ESTIMATE_BITRATE_BPS = 512_000L

    /** 完成阈值下限（媒体毫秒） */
    const val COMPLETE_THRESHOLD_MS = 15_000L

    /** 完成阈值：至少 2 个分片时长 */
    const val COMPLETE_SEGMENTS = 2

    /** 单次爬坡倍率 */
    const val RAMP_FACTOR = 1.25

    /** 分片 */
    /**
     * 分片。
     *
     * `rangeLength>0` 表示 `#EXT-X-BYTERANGE` 字节区间分片：读取必须带 offset，
     * 就绪判定也必须按 `[rangeOffset, rangeOffset+rangeLength)` 命中（同一 URI 多片共享文件）。
     */
    data class Segment(
        val durationMs: Long,
        val uri: String,
        val rangeOffset: Long = -1L,
        val rangeLength: Long = 0L,
    ) {
        val isRange: Boolean get() = rangeLength > 0L

        /** 缓存键：字节区间分片必须带上区间，否则同一文件的多个分片会互相误判 */
        val cacheKey: String
            get() = if (isRange) "$uri#$rangeOffset#$rangeLength" else uri
    }

    /**
     * 网络档位（唯一事实来源）。
     *
     * @param windowMs 非全量档位的目标窗口（FAST+ 仅作参考，实际按整集模式）
     * @param byteRatio 前向预取字节占缓存容量比例
     * @param budgetRatio 预取可用带宽比例
     * @param maxBudgetBps 预算绝对上限
     * @param concurrency 同清单并发数
     */
    enum class NetworkTier(
        val windowMs: Long,
        val byteRatio: Double,
        val budgetRatio: Double,
        val maxBudgetBps: Long,
        val concurrency: Int,
    ) {
        /** <2Mbps：小窗口、省流量、并发 1 */
        WEAK(30_000L, 0.15, 0.20, 1_000_000L, 1),

        /** 2~20Mbps：基线与原行为一致 */
        STANDARD(60_000L, 0.25, 0.30, 3_000_000L, 2),

        /** 20~50Mbps：窗口 180s */
        FAST(180_000L, 0.30, 0.45, 8_000_000L, 2),

        /** 50~100Mbps：窗口 300s */
        ULTRA(300_000L, 0.40, 0.55, 16_000_000L, 3),

        /** ≥100Mbps：窗口 600s（整集模式下不受此限） */
        EXTREME(600_000L, 0.50, 0.65, 24_000_000L, 3);

        val isFullEpisodeCapable: Boolean get() = this >= FAST
    }

    /** 预取状态（完成度 + 整集模式） */
    enum class PrefetchState {
        /** 正常窗口预取 */
        RUNNING,

        /** 本集剩余不足一个目标窗口：取完剩余 */
        TAIL,

        /** 本集已缓冲完毕：停止预取 */
        COMPLETE,

        /** 整集可装下：全量预取 */
        FULL_EPISODE,

        /** 整集装不下：从播放位置向后填满池子 */
        FILL_TO_CAPACITY,
    }

    /** 预取输入快照（全部来自应用线程，预取器只读） */
    data class Input(
        val segments: List<Segment>,
        val currentPositionMs: Long,
        val bufferedPositionMs: Long,
        /**
         * 预取锚点（媒体毫秒）：-1 = 按旧逻辑（缓冲头 + SKIP 片）。
         * AD-35：磁盘预取游标；写入磁盘不会推进 bufferedPosition，必须由游标接管锚点。
         */
        val anchorMs: Long = -1L,
        val durationMs: Long,
        val speed: Float,
        val isLive: Boolean,
        val hasEndList: Boolean,
        val hasByteRange: Boolean,
        val hasDrm: Boolean,
        val isMetered: Boolean,
        val videoCacheEnabled: Boolean,
        val prefetchEnabled: Boolean,
        val buffering: Boolean,
        val paused: Boolean,
        val foreground: Boolean,
        val cacheSizeBytes: Long,
        val bitrateBps: Long,
        val bandwidthEstimateBps: Long,
        /** 内存桶当前深度（毫秒）；由 tick 从 LoadControl 配置读取，0=未知 */
        val bucketDepthMs: Long = 0L,
        /** 本集已写入磁盘池的字节数（用于池子余量与整集判定） */
        val episodeWrittenBytes: Long = 0L,
        /** 缓存当前已用字节（含本集，AD-37）；0=未知 */
        val cacheUsedBytes: Long = 0L,
        /** 本会话是否已有有效实测吞吐样本（升档门槛） */
        val hasMeasuredSample: Boolean = false,
        /** 上一次档位（自调档滞回用；null=首次） */
        val previousTier: NetworkTier? = null,
        /** 距上次档位切换的毫秒数 */
        val msSinceTierSwitch: Long = 0L,
        /** 是否允许整集全量（低电/过热/近 30s 卡顿时为 false，仅保留窗口预取） */
        val allowFullEpisode: Boolean = true,
    )

    /** 门禁结果；null 表示放行 */
    data class Gate(val reason: String)

    /** 预取窗口 */
    data class Window(
        val startIndex: Int,
        val endIndexExclusive: Int,
        val startMs: Long,
        val endMs: Long,
        val maxBytes: Long,
        /** 窗口状态（RUNNING/TAIL/FULL_EPISODE/FILL_TO_CAPACITY） */
        val state: PrefetchState = PrefetchState.RUNNING,
    ) {
        val count: Int get() = (endIndexExclusive - startIndex).coerceAtLeast(0)
        val isEmpty: Boolean get() = count <= 0
    }

    /** 磁盘池分区（NEAR 近端补给区 / FAR 远端储备区） */
    data class PoolRegions(
        val nearEndMs: Long,
        val farEndMs: Long,
        /** NEAR 是否仍有未缓存分片（用于「NEAR 未满不写 FAR」） */
        val nearHasGap: Boolean,
    )

    enum class BudgetState { RUNNING, THROTTLED, PAUSED }

    data class Budget(val bytesPerSec: Long, val state: BudgetState)

    // ===================== 档位与自调档 =====================

    /** 按有效带宽取档位（纯映射） */
    fun tierOf(bandwidthBps: Long): NetworkTier = when {
        bandwidthBps < 2_000_000L -> NetworkTier.WEAK
        bandwidthBps < 20_000_000L -> NetworkTier.STANDARD
        bandwidthBps < 50_000_000L -> NetworkTier.FAST
        bandwidthBps < 100_000_000L -> NetworkTier.ULTRA
        else -> NetworkTier.EXTREME
    }

    /** 档位滞回最小间隔 */
    const val TIER_SWITCH_MIN_INTERVAL_MS = 30_000L

    /**
     * 自调档（用户无感知）。
     *
     * - 升档：必须有本会话实测样本，且与上一档位不同、距上次切换 ≥30s；
     * - 降档：立即生效（卡顿优先）；
     * - 首次（previousTier=null）：直接采用目标档位（仍受「无实测样本不上浮 FAST+」约束）。
     */
    fun resolveTier(input: Input): NetworkTier {
        val target = tierOf(input.bandwidthEstimateBps)
        val previous = input.previousTier ?: return if (
            target.isFullEpisodeCapable && !input.hasMeasuredSample
        ) {
            NetworkTier.STANDARD
        } else {
            target
        }
        if (target == previous) return previous
        if (target > previous) {
            val eligible = input.hasMeasuredSample &&
                input.msSinceTierSwitch >= TIER_SWITCH_MIN_INTERVAL_MS
            return if (eligible) target else previous
        }
        // 降档立即
        return target
    }

    /**
     * 条件门禁（任一命中即整会话停用预取）。
     *
     * 说明：`isLive` 与 `hasEndList` 是两个独立信号——直播判定以播放器动态时间轴为准，
     * 而 `!hasEndList` 只用于预取门禁（VOD 清单偶见缺 ENDLIST，不能据此关写缓存）。
     */
    fun gate(input: Input): Gate? = when {
        !input.prefetchEnabled -> Gate("disabled")
        !input.videoCacheEnabled -> Gate("video_cache_off")
        input.isMetered -> Gate("metered")
        input.isLive -> Gate("live")
        !input.hasEndList -> Gate("no_endlist")
        // 字节区间分片已支持（AD-43）：按 [offset,len) 写入/判定，不再整体放弃
        input.hasDrm -> Gate("drm")
        input.durationMs <= 0L -> Gate("no_duration")
        input.paused -> Gate("paused")
        !input.foreground -> Gate("background")
        input.segments.isEmpty() -> Gate("no_segments")
        input.cacheSizeBytes <= 0L -> Gate("no_cache_size")
        else -> null
    }

    // ===================== 完成度与整集模式 =====================

    /** 跳过分片后剩余媒体时长（毫秒）；AD-35：以预取游标为起点 */
    fun remainingMs(input: Input): Long {
        val segDur = averageSegmentDurationMs(input.segments)
        if (segDur <= 0L) return 0L
        val total = input.segments.sumOf { it.durationMs.coerceAtLeast(0L) }
        return (total - prefetchStartMs(input)).coerceAtLeast(0L)
    }

    /** 完成阈值：2 个分片或 15s 取大 */
    fun completeThresholdMs(input: Input): Long {
        val segDur = averageSegmentDurationMs(input.segments)
        return maxOf(COMPLETE_SEGMENTS * segDur, COMPLETE_THRESHOLD_MS)
    }

    /**
     * 整集估算字节：`时长 × 码率 / 8 × 1.3`；码率未知用保守估算。
     */
    fun episodeBytes(durationMs: Long, bitrateBps: Long): Long {
        if (durationMs <= 0L) return 0L
        val effectiveBitrate = if (bitrateBps > 0L) bitrateBps else ESTIMATE_BITRATE_BPS
        return (durationMs / 1000.0 * effectiveBitrate / 8.0 * 1.3).toLong()
    }

    /** 池子可写余量字节（默认前向预取 65%，扣除本集已写与缓存真实占用） */
    fun poolFreeBytes(
        cacheSizeBytes: Long,
        episodeWrittenBytes: Long,
        ratio: Double = BYTE_RATIO_LONG,
        /**
         * 缓存当前已用字节（含本集）；用于 AD-37 扣除真实占用。
         * 与 [episodeWrittenBytes] 不重复扣减：配额按本集、空间按整池分别计算后取小。
         */
        cacheUsedBytes: Long = 0L,
    ): Long {
        if (cacheSizeBytes <= 0L) return 0L
        val quotaLeft = (cacheSizeBytes * ratio).toLong() - episodeWrittenBytes
        val spaceLeft = cacheSizeBytes - cacheUsedBytes
        return minOf(quotaLeft, spaceLeft).coerceAtLeast(0L)
    }

    /**
     * 预取起点的有效时间（AD-35）。
     *
     * - 无游标（anchor < 0）：缓冲头之后 [SKIP_SEGMENTS] 片；
     * - 有游标：`max(游标, 缓冲头 + SKIP 片)`，保证不抢播放器正在取的分片，且可连续推进。
     */
    fun prefetchStartMs(input: Input): Long {
        val segDur = averageSegmentDurationMs(input.segments)
        val skipEnd = input.bufferedPositionMs + SKIP_SEGMENTS * segDur
        return if (input.anchorMs >= 0L) maxOf(input.anchorMs, skipEnd) else skipEnd
    }

    /**
     * 本次预取可用的字节比例（AD-36：状态判定与窗口计算必须同源）。
     *
     * - 短视频：0.25（防写满池子）；
     * - 整集模式（FAST+ 且允许整集）：0.65；
     * - 其余：档位比例。
     */
    fun effectiveRatio(input: Input, tier: NetworkTier = resolveTier(input)): Double {
        val isShort = input.durationMs in 1..SHORT_VIDEO_THRESHOLD_MS
        return when {
            isShort -> BYTE_RATIO_SHORT
            tier.isFullEpisodeCapable && input.allowFullEpisode -> BYTE_RATIO_LONG
            else -> tier.byteRatio
        }
    }

    /**
     * 预取状态判定。
     *
     * - remaining ≤ 完成阈值 ⇒ COMPLETE；
     * - FAST+ 且整集可装下 ⇒ FULL_EPISODE；
     * - FAST+ 且装不下 ⇒ FILL_TO_CAPACITY；
     * - remaining < 目标窗口 ⇒ TAIL；
     * - 否则 RUNNING。
     */
    fun stateOf(input: Input, tier: NetworkTier = resolveTier(input)): PrefetchState {
        val remaining = remainingMs(input)
        if (remaining <= 0L || remaining <= completeThresholdMs(input)) {
            return PrefetchState.COMPLETE
        }
        val isShort = input.durationMs in 1..SHORT_VIDEO_THRESHOLD_MS
        if (!isShort && input.allowFullEpisode && tier.isFullEpisodeCapable && input.bitrateBps > 0L) {
            val need = episodeBytes(input.durationMs, input.bitrateBps)
            val free = poolFreeBytes(
                input.cacheSizeBytes,
                input.episodeWrittenBytes,
                effectiveRatio(input, tier),
                input.cacheUsedBytes,
            )
            if (need > 0L && need <= free) return PrefetchState.FULL_EPISODE
            return PrefetchState.FILL_TO_CAPACITY
        }
        val target = minOf(tier.windowMs, remaining)
        return if (remaining <= target) PrefetchState.TAIL else PrefetchState.RUNNING
    }

    /**
     * 选择预取窗口：缓冲头之后跳过 [SKIP_SEGMENTS] 片。
     *
     * 目标时长：
     * - 短视频 / FAST+：本集剩余（整集语义，再受池子余量与兜底上限钳制）；
     * - WEAK/STANDARD：档位窗口 × 倍速（上限 [MAX_WINDOW_MS]）。
     *
     * 返回 null 表示「无可预取」（空清单/片长非法/已到末尾）；完成态由 [stateOf] 区分。
     */
    fun selectWindow(input: Input, tier: NetworkTier = resolveTier(input)): Window? {
        val segments = input.segments
        if (segments.isEmpty()) return null
        val segDur = averageSegmentDurationMs(segments)
        if (segDur <= 0L) return null

        val starts = LongArray(segments.size)
        var acc = 0L
        for (i in segments.indices) {
            starts[i] = acc
            acc += segments[i].durationMs.coerceAtLeast(0L)
        }
        if (acc <= 0L) return null

        val skipEndMs = prefetchStartMs(input)
        if (skipEndMs >= acc) return null

        val remaining = (acc - skipEndMs).coerceAtLeast(0L)
        val isShort = input.durationMs in 1..SHORT_VIDEO_THRESHOLD_MS
        val speed = SpeedBufferPolicy.sanitizeSpeed(input.speed)
        val state = stateOf(input, tier)
        val ratio = effectiveRatio(input, tier)

        val targetMs = when {
            // R2：整集/短视频同样受单轮上限约束（否则单轮数分钟、进度不可见）
            isShort -> minOf(remaining, MAX_ROUND_MEDIA_MS)
            tier.isFullEpisodeCapable && input.allowFullEpisode -> {
                minOf(remaining, MAX_ROUND_MEDIA_MS)
            }
            else -> minOf(
                (tier.windowMs * speed).toLong().coerceAtLeast(tier.windowMs),
                MAX_WINDOW_MS,
            )
        }
        val cacheWritableMs = writableMs(
            poolFreeBytes(
                input.cacheSizeBytes,
                input.episodeWrittenBytes,
                ratio,
                input.cacheUsedBytes,
            ),
            input.bitrateBps,
        )
        val windowMs = minOf(
            targetMs.coerceAtLeast(MIN_WINDOW_MS),
            remaining,
            if (cacheWritableMs > 0L) cacheWritableMs else Long.MAX_VALUE,
            FULL_WINDOW_GUARD_MS,
        ).coerceAtLeast(0L)
        if (windowMs <= 0L) return null
        val endMs = skipEndMs + windowMs

        var startIndex = -1
        var endIndex = -1
        for (i in segments.indices) {
            val segStart = starts[i]
            if (segStart < skipEndMs) continue
            if (segStart >= endMs) break
            if (startIndex < 0) startIndex = i
            endIndex = i
        }
        if (startIndex < 0 || endIndex < startIndex) return null

        val effectiveBitrate = if (input.bitrateBps > 0L) input.bitrateBps else ESTIMATE_BITRATE_BPS
        // 时间窗口对应字节（bitrate 为 bps）：bytes = sec * bps / 8 * 1.3（安全系数）
        val timeBytes = (windowMs / 1000.0 * effectiveBitrate / 8.0 * 1.3).toLong()
        val capacityBytes = (input.cacheSizeBytes * ratio).toLong()
        val maxBytes = minOf(
            if (timeBytes > 0L) timeBytes else Long.MAX_VALUE,
            if (capacityBytes > 0L) capacityBytes else Long.MAX_VALUE,
        ).coerceAtLeast(0L)

        return Window(
            startIndex = startIndex,
            endIndexExclusive = endIndex + 1,
            startMs = starts[startIndex],
            endMs = endMs,
            maxBytes = maxBytes,
            state = if (state == PrefetchState.COMPLETE) PrefetchState.TAIL else state,
        )
    }

    /**
     * 字节余量换算为可写媒体时长；码率未知时按保守值，仍返回有限值。
     */
    fun writableMs(freeBytes: Long, bitrateBps: Long): Long {
        if (freeBytes <= 0L) return 0L
        val effectiveBitrate = if (bitrateBps > 0L) bitrateBps else ESTIMATE_BITRATE_BPS
        return (freeBytes * 8.0 / effectiveBitrate * 1000).toLong().coerceAtLeast(0L)
    }

    /**
     * 磁盘池分区：
     * - NEAR：`[缓冲头, 缓冲头 + 内存桶深度]`（马上要进桶）；
     * - FAR：`(NEAR 末尾, NEAR + 目标窗口]`（储备）。
     *
     * `bucketDepthMs ≤0` 时按 60s 兜底（避免分区为空）。
     */
    fun poolRegions(
        input: Input,
        window: Window,
        tier: NetworkTier = resolveTier(input),
    ): PoolRegions {
        val bucketDepth = if (input.bucketDepthMs > 0L) {
            input.bucketDepthMs
        } else {
            tier.windowMs.coerceAtMost(60_000L)
        }
        val nearEnd = input.bufferedPositionMs + bucketDepth
        val farEnd = maxOf(window.endMs, nearEnd)
        val nearHasGap = window.startMs < nearEnd && window.endMs > input.bufferedPositionMs
        return PoolRegions(nearEndMs = nearEnd, farEndMs = farEnd, nearHasGap = nearHasGap)
    }

    /**
     * 预算双闸：带宽比例 + 缓冲水位。
     *
     * - 水位 < [PAUSE_BUFFER_MS] ⇒ PAUSED（0 B/s）
     * - 水位 < [LOW_BUFFER_MS] 或 BUFFERING 或带宽 < [WEAK_BANDWIDTH_BPS] ⇒ THROTTLED（带宽×0.2）
     * - 否则 RUNNING（档位预算比与倍速预算比取较小者；倍速越高预取让位越多）
     */
    fun budget(input: Input, tier: NetworkTier = resolveTier(input)): Budget {
        val bandwidth = if (input.bandwidthEstimateBps > 0L) {
            input.bandwidthEstimateBps
        } else {
            FALLBACK_BANDWIDTH_BPS
        }
        val bufferLevel = (input.bufferedPositionMs - input.currentPositionMs).coerceAtLeast(0L)
        if (bufferLevel < PAUSE_BUFFER_MS) {
            return Budget(0L, BudgetState.PAUSED)
        }
        val weak = bandwidth < WEAK_BANDWIDTH_BPS
        if (bufferLevel < LOW_BUFFER_MS || input.buffering || weak) {
            return Budget(
                (bandwidth * 0.20).toLong().coerceIn(MIN_BUDGET_BPS, tier.maxBudgetBps),
                BudgetState.THROTTLED,
            )
        }
        // IF-14（D）：高倍速时即时播放需求带宽按「码率 × speed」放大，预取必须让位。
        // 取档位比例与倍速比例的较小者（原「≥1.5x 预算 +0.05」方向相反，已废弃）。
        val ratio = SpeedBufferPolicy.effectiveBudgetRatio(tier.budgetRatio, input.speed)
        return Budget(
            (bandwidth * ratio).toLong().coerceIn(MIN_BUDGET_BPS, tier.maxBudgetBps),
            BudgetState.RUNNING,
        )
    }

    /** 分片平均时长（忽略非正数）；空列表返回 0 */
    fun averageSegmentDurationMs(segments: List<Segment>): Long {
        val valid = segments.filter { it.durationMs > 0L }
        if (valid.isEmpty()) return 0L
        return valid.sumOf { it.durationMs } / valid.size
    }

    /**
     * 长视频回看窗口实际生效秒数：受缓存容量钳制。
     *
     * `min(configSec, cacheSize×0.15×8/bitrate)`；bitrate 未知返回 0（仅依赖 LRU）。
     */
    fun backCacheSeconds(configSec: Int, cacheSizeBytes: Long, bitrateBps: Long): Int {
        if (configSec <= 0) return 0
        if (bitrateBps <= 0L || cacheSizeBytes <= 0L) return 0
        val capacitySec = (cacheSizeBytes * BYTE_RATIO_BACK_CACHE * 8.0 / bitrateBps).toInt()
        return minOf(configSec, capacitySec).coerceAtLeast(0)
    }
}
