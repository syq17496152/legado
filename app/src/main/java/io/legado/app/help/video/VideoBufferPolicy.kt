package io.legado.app.help.video

/**
 * 播放缓冲策略（design §1.14 B3/B10 / tasks 13.3/13.10，纯函数可直测）
 *
 * 现状问题（已静态取证）：
 * - `playerBufferStrategy` 只有定义与写入，**无消费点**（死配置）⇒ 用户调了没效果；
 * - `setTargetBufferBytes(-1)` 不限字节，高码率长视频会让 allocator 持续增长。
 *
 * 本策略把「用户策略 + 带宽档位」收敛为唯一参数来源：
 * 1. 档位给出基准 min/max/起播阈值，用户策略只调整 maxBuffer 与起播门槛；
 * 2. 字节上限按「基准码率 × 秒数 × 安全系数」计算并封顶，避免 allocator 无界增长。
 *
 * 不变式：minBuffer 永远取档位值（首帧优化成果不被策略破坏，见 ExoPlayerHelper 注释）。
 */
object VideoBufferPolicy {

    /** 策略：0=自动（按档位） 1=激进 2=均衡 3=保守 */
    const val STRATEGY_AUTO = 0
    const val STRATEGY_AGGRESSIVE = 1
    const val STRATEGY_BALANCED = 2
    const val STRATEGY_CONSERVATIVE = 3

    /** 基准码率估算：8 Mbps（1 MB/s）。真实码率在起播前不可得，用保守基准 + 系数 */
    private const val REF_BYTES_PER_SEC = 1_000_000.0

    /** 安全系数：抵消码率估算误差与容器开销 */
    private const val SAFETY_FACTOR = 1.3

    /** 字节上限（256MB）：与 design §1.14「上限 128-256MB」一致，防 allocator 爆涨 */
    const val MAX_TARGET_BUFFER_BYTES = 256 * 1024 * 1024

    /** maxBuffer 的合法区间（秒） */
    private const val MIN_MAX_BUFFER_MS = 20_000
    private const val MAX_MAX_BUFFER_MS = 300_000

    /** 策略对 maxBuffer 的倍率（1=原档位） */
    fun multiplier(strategy: Int): Double = when (strategy) {
        STRATEGY_AGGRESSIVE -> 1.5
        STRATEGY_CONSERVATIVE -> 0.6
        else -> 1.0
    }

    /** 内存桶条件提升后的目标 maxBuffer（180s） */
    const val DEEPENED_MAX_BUFFER_MS = 180_000

    /** 内存桶提升所需最小物理内存（MB） */
    const val DEEPEN_MIN_RAM_MB = 8_000L

    /** 内存桶提升允许的最大码率（bps） */
    const val DEEPEN_MAX_BITRATE_BPS = 8_000_000L

    /**
     * 是否允许加深内存桶（R42 / AD-22 v1.2）。
     *
     * 必须同时满足：档位 ≥ FAST、物理内存 ≥8GB、码率 ∈ (0, 8Mbps]、目标字节 ≤256MB。
     * 码率未知（≤0）按「不满足」处理；调用方负责按「实测 → 上次实测 → 默认 8Mbps」取值。
     */
    fun shouldDeepenBucket(
        tier: io.legado.app.help.exoplayer.SegmentPrefetchPolicy.NetworkTier,
        ramMb: Long,
        bitrateBps: Long,
        targetBufferBytes: Int,
    ): Boolean {
        if (!tier.isFullEpisodeCapable) return false
        if (ramMb < DEEPEN_MIN_RAM_MB) return false
        if (bitrateBps <= 0L || bitrateBps > DEEPEN_MAX_BITRATE_BPS) return false
        if (targetBufferBytes <= 0 || targetBufferBytes > MAX_TARGET_BUFFER_BYTES) return false
        return true
    }

    /**
     * 计算实际缓冲参数（毫秒）
     *
     * @param tierMinBufferMs 档位 minBuffer（保持首帧优化成果，策略不改）
     * @param tierMaxBufferMs 档位 maxBuffer
     * @param tierBufferForPlaybackMs 档位起播阈值
     * @param strategy 用户策略（0-3，越界按自动处理）
     * @param userMaxBufferSec 用户显式秒数（>0 时优先于档位基准）
     */
    fun resolve(
        tierMinBufferMs: Int,
        tierMaxBufferMs: Int,
        tierBufferForPlaybackMs: Int,
        strategy: Int,
        userMaxBufferSec: Int = 0
    ): BufferProfile {
        val base = if (userMaxBufferSec > 0) userMaxBufferSec * 1000 else tierMaxBufferMs
        val maxMs = (base * multiplier(strategy)).toInt()
            .coerceIn(MIN_MAX_BUFFER_MS, MAX_MAX_BUFFER_MS)
            .coerceAtLeast(tierMinBufferMs * 2)
        val playMs = when (strategy) {
            STRATEGY_AGGRESSIVE -> minOf(tierBufferForPlaybackMs, 500)
            STRATEGY_CONSERVATIVE -> 1_500
            else -> tierBufferForPlaybackMs
        }
        val afterRebufferMs = if (strategy == STRATEGY_CONSERVATIVE) 3_000 else 2_000
        return BufferProfile(tierMinBufferMs, maxMs, playMs, afterRebufferMs)
    }

    /**
     * 字节上限：`maxBufferSec × 基准码率 × 安全系数`，封顶 [MAX_TARGET_BUFFER_BYTES]
     *
     * 返回 `DefaultLoadControl.setTargetBufferBytes` 入参（Int）。
     */
    fun targetBufferBytes(maxBufferMs: Int): Int {
        val bytes = (maxBufferMs / 1000.0) * REF_BYTES_PER_SEC * SAFETY_FACTOR
        return bytes.toLong()
            .coerceIn(16L * 1024 * 1024, MAX_TARGET_BUFFER_BYTES.toLong())
            .toInt()
    }

    data class BufferProfile(
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val bufferForPlaybackMs: Int,
        val bufferForPlaybackAfterRebufferMs: Int
    )
}
