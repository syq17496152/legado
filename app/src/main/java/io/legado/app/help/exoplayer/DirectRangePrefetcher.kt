package io.legado.app.help.exoplayer

import io.legado.app.constant.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/**
 * 直链 MP4/FLV 的 Range 预取（从 VideoSegmentPrefetcher 拆出，守 G-38）。
 *
 * 设计依据：本期 AD-34 / R46 / AD-38。
 * - FAST+：从 `bufferedPosition+2s` 起按 32MB 分块向文件末尾写（整集模式）；
 * - WEAK/STANDARD：仅取档位窗口；
 * - 不适用（无直链/不支持 Range/非 TYPE_OTHER/时长码率未知/池满/让路）返回 **-1**，不参与熔断也不续轮；
 * - 已全部命中返回 **0**（零进展 ⇒ 不触发续轮，防忙循环）；
 * - 服务器忽略 Range 时，写入器读到目标长度即停，不会整包下载。
 */
internal object DirectRangePrefetcher {

    /** 最近一次「不适用」原因（原因变化才落日志，避免每 5s 刷屏） */
    @Volatile
    private var lastSkipReason: String? = null

    /**
     * 直链已落盘预取的“媒体毫秒”（startByte+written 换算）。
     *
     * 直链没有分片游标，灰条若要体现整集落盘进展，只能由这里提供（AD-39 扩展）。
     */
    @Volatile
    var lastReadyMs: Long = 0L
        private set

    /** 新会话复位（切集/切源） */
    fun reset() {
        lastReadyMs = 0L
        lastSkipReason = null
    }

    private fun markReady(byteOffset: Long, total: Long, durationMs: Long) {
        if (total <= 0L || durationMs <= 0L) return
        val ms = (byteOffset * durationMs / total).coerceIn(0L, durationMs)
        if (ms > lastReadyMs) lastReadyMs = ms
    }

    private fun skip(reason: String): Long {
        if (reason != lastSkipReason) {
            lastSkipReason = reason
            AppLog.putDebug("[Prefetch] direct_skip reason=$reason")
        }
        return -1L
    }


    /** @return 实际写入字节数；-1 = 不适用（无需熔断/续轮）；0 = 无进展 */
    suspend fun prefetch(
        snapshot: PrefetchSnapshot,
        headers: Map<String, String>,
        tier: SegmentPrefetchPolicy.NetworkTier,
        cache: PrefetchCache,
        writer: SegmentWriter,
        clock: PrefetchClock,
        episodeWritten: AtomicLong,
        currentSessionId: Long,
    ): Long {
        val uri = snapshot.directUri ?: return skip("no_direct_uri")
        if (!uri.startsWith("http")) return skip("not_http")
        if (!snapshot.supportsRange ||
            snapshot.contentType != androidx.media3.common.C.TYPE_OTHER
        ) {
            return skip(
                if (!snapshot.supportsRange) "no_range" else "not_progressive"
            )
        }
        if (snapshot.durationMs <= 0L || snapshot.bitrateBps <= 0L) return skip("no_duration_or_bitrate")
        val total = cache.contentLength(uri)
        if (total <= 0L) return skip("no_content_length")

        val input = SegmentPrefetchPolicy.Input(
            segments = emptyList(),
            currentPositionMs = snapshot.positionMs,
            bufferedPositionMs = snapshot.bufferedPositionMs,
            durationMs = snapshot.durationMs,
            speed = snapshot.speed,
            isLive = snapshot.isLive,
            hasEndList = true,
            hasByteRange = false,
            hasDrm = snapshot.hasDrm,
            isMetered = snapshot.isMetered,
            videoCacheEnabled = snapshot.videoCacheEnabled,
            prefetchEnabled = snapshot.prefetchEnabled,
            buffering = snapshot.buffering,
            paused = snapshot.paused,
            foreground = snapshot.foreground,
            cacheSizeBytes = snapshot.cacheSizeBytes,
            bitrateBps = snapshot.bitrateBps,
            bandwidthEstimateBps = snapshot.bandwidthEstimateBps,
            bucketDepthMs = snapshot.bucketDepthMs,
            episodeWrittenBytes = episodeWritten.get(),
            cacheUsedBytes = cache.usedBytes(),
            hasMeasuredSample = snapshot.hasMeasuredSample,
            previousTier = snapshot.previousTier,
            msSinceTierSwitch = snapshot.msSinceTierSwitch,
            allowFullEpisode = snapshot.allowFullEpisode,
        )
        val budget = SegmentPrefetchPolicy.budget(input, tier)
        if (budget.state == SegmentPrefetchPolicy.BudgetState.PAUSED) return skip("budget_paused")

        val startMs = snapshot.bufferedPositionMs + 2_000L
        if (startMs >= snapshot.durationMs) return skip("near_end")
        val remainingMs = (snapshot.durationMs - startMs).coerceAtLeast(0L)
        val targetMs = if (tier.isFullEpisodeCapable) {
            remainingMs
        } else {
            minOf(
                SpeedBufferPolicy.scaledWindowMs(tier.windowMs, snapshot.speed),
                remainingMs,
            )
        }
        if (targetMs <= 0L) return skip("target_zero")
        val bytesPerMs = total.toDouble() / snapshot.durationMs
        val poolFree = SegmentPrefetchPolicy.poolFreeBytes(
            snapshot.cacheSizeBytes,
            episodeWritten.get(),
            SegmentPrefetchPolicy.BYTE_RATIO_LONG,
            cache.usedBytes(),
        )
        val startByte = (startMs * bytesPerMs).toLong()
            .coerceIn(0L, (total - 1).coerceAtLeast(0L))
        val length = minOf(
            (targetMs * bytesPerMs).toLong(),
            total - startByte,
            if (poolFree > 0L) poolFree else Long.MAX_VALUE,
        )
        // 池满（length<=0）属于「不适用」，不计失败也不续轮
        if (length <= 0L) return skip("pool_full")
        // 全部命中返回 0：不能虚增 episodeWrittenBytes，否则池余量被误吞、续轮判据失真
        if (cache.isCached(uri, startByte, length)) {
            markReady(startByte + length, total, snapshot.durationMs)
            return 0L
        }

        var written = 0L
        val startedAt = clock.nowMs()
        var failed = false
        while (written < length) {
            if (snapshot.sessionId != currentSessionId) return written
            val chunk = minOf(length - written, MAX_SEGMENT_WRITE_BYTES)
            val bytes = try {
                writer.write(uri, headers, startByte + written, chunk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.putDebug("[Prefetch] direct_range fail error=${e.javaClass.simpleName}")
                failed = true
                0L
            }
            if (bytes <= 0L) break
            written += bytes
            episodeWritten.addAndGet(bytes)
            val wait = PrefillThrottle.waitMs(
                totalRead = written,
                elapsedMs = clock.nowMs() - startedAt,
                limitBytesPerSec = budget.bytesPerSec,
            )
            if (wait > 0L) delay(wait)
        }
        if (written > 0L) {
            markReady(startByte + written, total, snapshot.durationMs)
            AppLog.put(
                "[Prefetch] direct_range tier=${tier.name} startByte=$startByte " +
                    "len=$length written=$written full=${tier.isFullEpisodeCapable}"
            )
            return written
        }
        // 零写入：有异常 ⇒ 0（计入熔断）；服务器无数据但未抛错 ⇒ -1（不参与熔断）
        return if (failed) 0L else -1L
    }
}
