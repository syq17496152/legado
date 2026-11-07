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
 * tasks §13.3：缓存写决策日志的打点判据（纯函数，便于单测）。
 *
 * 为什么不是简单限频：真机取证（2026-11-08 04:12）表明 `createDataSink()` 并不按请求调用
 * （一个 session 仅 4 次），限频会让「长视频由 on 转 off」事件不可见。
 * 因此**决策变化必打**，限频仅用于稳定状态下的抽样。
 *
 * @param changed 本次决策是否与上次不同
 * @param count 本次是第几次决策（从 1 开始）
 */
internal fun shouldLogCacheWriteDecision(
    changed: Boolean,
    count: Int,
    firstN: Int = 30,
    logEvery: Int = 10
): Boolean = changed || count <= firstN || count % logEvery == 0

/**
 * tasks §13.3/§13.4：**按请求**分流的写 sink。
 *
 * 真机铁证（2026-11-08 04:12）：`CacheDataSource` 的写 sink 在一个播放会话里只创建 4 次
 * （`createDataSink()` 4 次），之后被复用；而缓存文件仍按分片持续写入（1 分钟 23 个 `.exo`）
 * ⇒ **在 `createDataSink()` 里做决策无效**。决策必须下沉到每次 `open(dataSpec)`：
 *
 * - `open` 现算时长 → 长视频不建 delegate（丢弃），短视频交给 `CacheDataSink`；
 * - `write/close` 透传给当次 delegate，delegate 为空即丢弃；
 * - 每次 `open` 都重新决策 ⇒ READY 后拿到真实时长即对后续分片生效，无需重建 media source。
 */
internal class PolicySwitchDataSink(
    private val cacheSinkFactory: DataSink.Factory,
    private val durationProvider: () -> Long,
    private val onDecision: (write: Boolean, durationMs: Long) -> Unit = { _, _ -> }
) : DataSink {

    private var delegate: DataSink? = null

    override fun open(dataSpec: DataSpec) {
        val durationMs = durationProvider()
        val write = shouldWriteCache(durationMs)
        onDecision(write, durationMs)
        val sink = if (write) cacheSinkFactory.createDataSink() else null
        delegate = sink
        sink?.open(dataSpec)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        delegate?.write(buffer, offset, length)
    }

    override fun close() {
        delegate?.close()
        delegate = null
    }
}
