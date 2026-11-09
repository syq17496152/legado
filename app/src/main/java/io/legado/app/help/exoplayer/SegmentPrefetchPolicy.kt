package io.legado.app.help.exoplayer

/**
 * 分片预取策略（纯函数，可 JVM 直测）。
 *
 * 设计依据：docs/specs/video-buffer-prefetch-v2/design.md §1.3/§1.12/§1.15
 *
 * 职责边界：
 * - 只做「能不能预取 / 预取哪一段 / 预算多少」的决策；
 * - 不做任何网络、缓存、线程操作（由 [VideoSegmentPrefetcher] 执行）。
 *
 * 不变式：
 * 1. 预取窗口永远从缓冲头之后 [SKIP_SEGMENTS] 个分片开始（避免与播放 Loader 抢同一分片）；
 * 2. 字节上限优先于时间窗口（缓存容量 25%/15% 截断）；
 * 3. 缓冲水位 < [PAUSE_BUFFER_MS] 时完全暂停，< [LOW_BUFFER_MS] 时降速。
 */
object SegmentPrefetchPolicy {

    /** 跳过缓冲头之后的近端分片数 */
    const val SKIP_SEGMENTS = 2

    /** 长视频基础窗口 */
    const val BASE_WINDOW_MS = 60_000L

    /** 高速网络（≥20Mbps，5G/WiFi6）长视频窗口：直接拉满 120s */
    const val FAST_WINDOW_MS = 120_000L

    /** 高速网络阈值（bps） */
    const val FAST_BANDWIDTH_BPS = 20_000_000L

    /** 超高速网络阈值（bps，5G/WiFi6 实测常见） */
    const val ULTRA_BANDWIDTH_BPS = 50_000_000L

    /** 短视频基础窗口 */
    const val SHORT_WINDOW_MS = 30_000L

    /** 窗口上限（含倍速放大后） */
    const val MAX_WINDOW_MS = 180_000L

    /** 长视频字节上限占缓存容量比例 */
    const val BYTE_RATIO_LONG = 0.25

    /** 短视频字节上限占缓存容量比例 */
    const val BYTE_RATIO_SHORT = 0.15

    /** 预算绝对上限（8MB/s：5G/WiFi6 下让预取真正吃得下带宽） */
    const val MAX_BUDGET_BPS = 8_000_000L

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

    /** 短视频阈值（与 LongVideoCachePolicy 保持一致） */
    const val SHORT_VIDEO_THRESHOLD_MS = 20 * 60 * 1000L

    /** 分片 */
    data class Segment(val durationMs: Long, val uri: String)

    /** 预取输入快照（全部来自应用线程，预取器只读） */
    data class Input(
        val segments: List<Segment>,
        val currentPositionMs: Long,
        val bufferedPositionMs: Long,
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
    ) {
        val count: Int get() = (endIndexExclusive - startIndex).coerceAtLeast(0)
        val isEmpty: Boolean get() = count <= 0
    }

    enum class BudgetState { RUNNING, THROTTLED, PAUSED }

    data class Budget(val bytesPerSec: Long, val state: BudgetState)

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
        input.hasByteRange -> Gate("byterange")
        input.hasDrm -> Gate("drm")
        input.durationMs <= 0L -> Gate("no_duration")
        input.paused -> Gate("paused")
        !input.foreground -> Gate("background")
        input.segments.isEmpty() -> Gate("no_segments")
        else -> null
    }

    /**
     * 选择预取窗口：缓冲头之后跳过 [SKIP_SEGMENTS] 片，取基础窗口（按倍速放大，上限 [MAX_WINDOW_MS]）。
     *
     * 时间窗口不足一个分片时返回 null（避免「预取一个已在缓冲中的分片」）。
     */
    fun selectWindow(input: Input): Window? {
        val segments = input.segments
        if (segments.isEmpty()) return null
        val segDurMs = averageSegmentDurationMs(segments)
        if (segDurMs <= 0L) return null

        // 分片起始时间（相对本集 0 点）
        val starts = LongArray(segments.size)
        var acc = 0L
        for (i in segments.indices) {
            starts[i] = acc
            acc += segments[i].durationMs.coerceAtLeast(0L)
        }
        if (acc <= 0L) return null

        val skipEndMs = input.bufferedPositionMs + SKIP_SEGMENTS * segDurMs
        if (skipEndMs >= acc) return null

        val isShort = input.durationMs in 1..SHORT_VIDEO_THRESHOLD_MS
        val fastNet = input.bandwidthEstimateBps >= FAST_BANDWIDTH_BPS
        val baseWindowMs = when {
            isShort -> minOf(SHORT_WINDOW_MS, (acc - skipEndMs).coerceAtLeast(0L))
            // 5G/WiFi6：直接把窗口拉满 120s（字节仍受缓存 25% 钳制）
            fastNet -> FAST_WINDOW_MS
            else -> BASE_WINDOW_MS
        }
        val speed = SpeedBufferPolicy.sanitizeSpeed(input.speed)
        val windowMs = minOf(
            (baseWindowMs * speed).toLong().coerceAtLeast(baseWindowMs),
            MAX_WINDOW_MS,
            (acc - skipEndMs).coerceAtLeast(0L),
        )
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

        val ratio = if (isShort) BYTE_RATIO_SHORT else BYTE_RATIO_LONG
        // 时间窗口对应字节（bitrate 为 bps）：bytes = sec * bps / 8 * 1.3（安全系数）
        val timeBytes = (windowMs / 1000.0 * input.bitrateBps / 8.0 * 1.3).toLong()
        val capacityBytes = (input.cacheSizeBytes * ratio).toLong()
        val maxBytes = minOf(
            if (timeBytes > 0) timeBytes else Long.MAX_VALUE,
            if (capacityBytes > 0) capacityBytes else Long.MAX_VALUE,
        ).coerceAtLeast(0L)

        return Window(
            startIndex = startIndex,
            endIndexExclusive = endIndex + 1,
            startMs = starts[startIndex],
            endMs = endMs,
            maxBytes = maxBytes,
        )
    }

    /**
     * 预算双闸：带宽比例 + 缓冲水位。
     *
     * - 水位 < [PAUSE_BUFFER_MS] ⇒ PAUSED（0 B/s）
     * - 水位 < [LOW_BUFFER_MS] 或 BUFFERING 或带宽 < [WEAK_BANDWIDTH_BPS] ⇒ THROTTLED（带宽×0.2）
     * - 否则 RUNNING（带宽×0.3；倍速 ≥1.5 时 ×0.35）
     */
    fun budget(input: Input): Budget {
        val bandwidth = if (input.bandwidthEstimateBps > 0) {
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
                (bandwidth * 0.20).toLong().coerceIn(MIN_BUDGET_BPS, MAX_BUDGET_BPS),
                BudgetState.THROTTLED,
            )
        }
        val speed = SpeedBufferPolicy.sanitizeSpeed(input.speed)
        // 5G/WiFi6：带宽越富裕，预取占比越高（仍封顶 8MB/s，绝不抢播放供给）
        val ratio = when {
            bandwidth >= ULTRA_BANDWIDTH_BPS -> 0.50
            else -> SpeedBufferPolicy.budgetRatio(speed)
        }
        return Budget(
            (bandwidth * ratio).toLong().coerceIn(MIN_BUDGET_BPS, MAX_BUDGET_BPS),
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
     * `min(configSec, cacheSize×0.35×8/bitrate)`；bitrate 未知返回 0（仅依赖 LRU）。
     */
    fun backCacheSeconds(configSec: Int, cacheSizeBytes: Long, bitrateBps: Long): Int {
        if (configSec <= 0) return 0
        if (bitrateBps <= 0L || cacheSizeBytes <= 0L) return 0
        val capacitySec = (cacheSizeBytes * 0.35 * 8.0 / bitrateBps).toInt()
        return minOf(configSec, capacitySec).coerceAtLeast(0)
    }
}
