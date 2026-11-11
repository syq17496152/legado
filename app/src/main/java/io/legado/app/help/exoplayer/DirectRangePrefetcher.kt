package io.legado.app.help.exoplayer

import io.legado.app.constant.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/**
 * 直链 MP4/FLV 的 Range 预取（从 VideoSegmentPrefetcher 拆出，守 G-38）。
 *
 * 设计依据：本期 AD-34 / R46。
 * - FAST+：从 `bufferedPosition+2s` 起按 32MB 分块向文件末尾写（整集模式）；
 * - WEAK/STANDARD：仅取档位窗口；
 * - 不适用（无直链/不支持 Range/非 TYPE_OTHER/时长码率未知）返回 true，不参与熔断；
 * - 服务器忽略 Range 时，写入器读到目标长度即停，不会整包下载。
 */
internal object DirectRangePrefetcher {

    suspend fun prefetch(
        snapshot: VideoSegmentPrefetcher.Snapshot,
        headers: Map<String, String>,
        tier: SegmentPrefetchPolicy.NetworkTier,
        cache: PrefetchCache,
        writer: SegmentWriter,
        clock: PrefetchClock,
        episodeWritten: AtomicLong,
        currentSessionId: Long,
    ): Boolean {
        val uri = snapshot.directUri ?: return true
        if (!uri.startsWith("http")) return true
        if (!snapshot.supportsRange ||
            snapshot.contentType != androidx.media3.common.C.TYPE_OTHER
        ) {
            return true
        }
        if (snapshot.durationMs <= 0L || snapshot.bitrateBps <= 0L) return true
        val total = cache.contentLength(uri)
        if (total <= 0L) return true

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
            episodeWrittenBytes = snapshot.episodeWrittenBytes,
            hasMeasuredSample = snapshot.hasMeasuredSample,
            previousTier = snapshot.previousTier,
            msSinceTierSwitch = snapshot.msSinceTierSwitch,
            allowFullEpisode = snapshot.allowFullEpisode,
        )
        val budget = SegmentPrefetchPolicy.budget(input, tier)
        if (budget.state == SegmentPrefetchPolicy.BudgetState.PAUSED) return true

        val startMs = snapshot.bufferedPositionMs + 2_000L
        if (startMs >= snapshot.durationMs) return true
        val remainingMs = (snapshot.durationMs - startMs).coerceAtLeast(0L)
        val targetMs = if (tier.isFullEpisodeCapable) {
            remainingMs
        } else {
            minOf(
                SpeedBufferPolicy.scaledWindowMs(tier.windowMs, snapshot.speed),
                remainingMs,
            )
        }
        if (targetMs <= 0L) return true
        val bytesPerMs = total.toDouble() / snapshot.durationMs
        val poolFree = SegmentPrefetchPolicy.poolFreeBytes(
            snapshot.cacheSizeBytes,
            episodeWritten.get(),
        )
        val startByte = (startMs * bytesPerMs).toLong()
            .coerceIn(0L, (total - 1).coerceAtLeast(0L))
        val length = minOf(
            (targetMs * bytesPerMs).toLong(),
            total - startByte,
            if (poolFree > 0L) poolFree else Long.MAX_VALUE,
        )
        if (length <= 0L) return true
        if (cache.isCached(uri, startByte, length)) {
            episodeWritten.addAndGet(length)
            return true
        }

        var written = 0L
        val startedAt = clock.nowMs()
        var failed = false
        while (written < length) {
            if (snapshot.sessionId != currentSessionId) return true
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
            AppLog.put(
                "[Prefetch] direct_range tier=${tier.name} startByte=$startByte " +
                    "len=$length written=$written full=${tier.isFullEpisodeCapable}"
            )
        }
        return !failed || written > 0L
    }
}
