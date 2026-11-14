package io.legado.app.help.exoplayer

import io.legado.app.constant.AppLog
import io.legado.app.help.video.engine.M3u8Parser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 下一集「倒计时预热」的分片预填（从 [VideoSegmentPrefetcher] 拆出，守 G-38 单文件行数）。
 *
 * 语义：解析清单 → 跳过已缓存分片 → 只写前 [count] 个未缓存分片（增量、幂等）。
 * 清单本身不写缓存；限速按调用方传入的档位预算执行，避免与当前播放抢带宽。
 */
internal object SegmentPrefiller {

    suspend fun prefill(
        playlistUri: String,
        headers: Map<String, String>,
        count: Int,
        limitBytesPerSec: Long,
        fetcher: ManifestFetcher,
        writer: SegmentWriter,
        cache: PrefetchCache,
        clock: PrefetchClock,
    ): Int = withContext(Dispatchers.IO) {
        if (playlistUri.isBlank() || count <= 0) return@withContext 0
        var mediaUri = playlistUri
        var text = fetcher.fetch(mediaUri, headers) ?: return@withContext 0
        if (M3u8Parser.isMasterPlaylist(text)) {
            val variant = M3u8Parser.pickVariantFromText(text, mediaUri) ?: return@withContext 0
            mediaUri = variant
            text = fetcher.fetch(mediaUri, headers) ?: return@withContext 0
        }
        if (HlsManifestParser.hasByteRange(text) || !HlsManifestParser.hasEndList(text)) {
            return@withContext 0
        }
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
                // 已缓存不占「预取 N 片」名额：继续取后面的未缓存分片，最大化切集收益
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
}
