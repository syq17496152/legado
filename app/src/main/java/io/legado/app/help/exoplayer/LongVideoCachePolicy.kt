package io.legado.app.help.exoplayer

import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec

/**
 * tasks §13.3：长视频缓存分流策略（纯函数 + 可测）。
 *
 * 目标：长视频**默认不边播边写**（省存储/减少 IO 抖动），短视频保持边下边存（重播秒开）。
 *
 * 阈值取 20 分钟：短剧/样片远低于该值，长剧集（40 分钟档）高于该值。
 * 时长未知（`<= 0`，如 prepare 前或直播）按短视频处理——保持旧行为，宁多写不漏写。
 */
internal const val LONG_VIDEO_CACHE_THRESHOLD_MS = 20 * 60 * 1000L

internal fun shouldWriteCache(durationMs: Long): Boolean =
    durationMs <= 0L || durationMs < LONG_VIDEO_CACHE_THRESHOLD_MS

/**
 * tasks §13.4：缓存写决策日志的打点判据（纯函数，便于单测）。
 *
 * 为什么不是简单限频：HLS 分片较大时，播放数分钟只创建个位数 sink，
 * 「每 N 次」的限频会让「长视频由 on 转 off」这一关键事件长期不可见
 * （真机取证 2026-11-08 02:41：4 分钟仅 3 次 sink 创建）。
 * 因此**决策变化必须打**，限频仅用于稳定状态下的抽样。
 *
 * @param changed 本次决策是否与上次不同
 * @param count 本次是第几次 sink 创建（从 1 开始）
 */
internal fun shouldLogCacheWriteDecision(
    changed: Boolean,
    count: Int,
    firstN: Int = 3,
    logEvery: Int = 20
): Boolean = changed || count <= firstN || count % logEvery == 0

/**
 * tasks §13.4：不写缓存的空 DataSink。
 *
 * 为什么可以"不写"而不重建 media source：`CacheDataSource` 对写 sink 的语义是
 * 「tee 一份到缓存」——读取永远走 upstream，写入失败/丢弃都会被忽略。
 * 因此把 `open/write/close` 做成空操作即可只保留播放、不落盘（无需二次 prepare）。
 */
internal class DiscardingDataSink : DataSink {
    override fun open(dataSpec: DataSpec) = Unit
    override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
    override fun close() = Unit
}
