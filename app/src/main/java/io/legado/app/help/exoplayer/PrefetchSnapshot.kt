package io.legado.app.help.exoplayer

/**
 * 播放状态快照（应用线程写、预取线程读；不可变）。
 *
 * 从 [VideoSegmentPrefetcher] 抽出（守 G-38 单文件行数）：只描述数据，不含任何策略。
 */
internal data class PrefetchSnapshot(
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
