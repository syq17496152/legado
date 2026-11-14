package io.legado.app.help.exoplayer

import io.legado.app.help.video.engine.M3u8Parser

/** 已学习的媒体清单（视频或独立音频） */
internal data class MediaPlaylist(
    val uri: String,
    val segments: List<SegmentPrefetchPolicy.Segment>,
    val hasEndList: Boolean,
    val hasByteRange: Boolean,
    val targetDurationMs: Long,
    val fetchedAtMs: Long,
)

/**
 * 清单学习的诊断计数（可观测性）。
 *
 * 为什么需要：清单学习失败时（fetch 失败/master 跳过/解析为空），预取会**静默空转**，
 * 日志里只有 tier 没有 plan，线上无法判断"到底哪一步没拿到清单"。
 */
internal class PlaylistLearnStats {
    var candidates: Int = 0
    var fromCache: Int = 0
    var fetched: Int = 0
    var fetchFailed: Int = 0
    var masterSkipped: Int = 0
    var parseEmpty: Int = 0

    override fun toString(): String =
        "cand=$candidates cache=$fromCache fetch=$fetched " +
            "fetchFail=$fetchFailed master=$masterSkipped parseEmpty=$parseEmpty"
}

/**
 * 媒体清单学习（播放器已打开的清单 → 分片序列）—— 从 [VideoSegmentPrefetcher] 拆出，守 G-38。
 *
 * 只按 [observerUris] 顺序学习，最多 [maxPlaylists] 份（视频 + 独立音频）；
 * master 清单跳过并记入 [masterUris]，避免每轮重复抓取。
 */
internal suspend fun learnMediaPlaylists(
    observerUris: List<String>,
    learned: MutableMap<String, MediaPlaylist>,
    masterUris: MutableSet<String>,
    fetcher: ManifestFetcher,
    headers: Map<String, String>,
    nowMs: Long,
    maxPlaylists: Int,
    ttlMs: Long,
    stats: PlaylistLearnStats = PlaylistLearnStats(),
): List<MediaPlaylist> {
    val result = ArrayList<MediaPlaylist>(maxPlaylists)
    stats.candidates = observerUris.size
    for (uri in observerUris) {
        if (result.size >= maxPlaylists) break
        if (masterUris.contains(uri)) {
            stats.masterSkipped++
            continue
        }
        val cached = learned[uri]
        if (cached != null && cached.segments.isNotEmpty() && nowMs - cached.fetchedAtMs < ttlMs) {
            result.add(cached)
            stats.fromCache++
            continue
        }
        val text = fetcher.fetch(uri, headers)
        if (text == null) {
            stats.fetchFailed++
            continue
        }
        stats.fetched++
        if (M3u8Parser.isMasterPlaylist(text)) {
            masterUris.add(uri)
            stats.masterSkipped++
            continue
        }
        val parsed = HlsManifestParser.parseSegmentsWithDurations(text)
        if (parsed.isEmpty()) {
            stats.parseEmpty++
            continue
        }
        val segments = parsed.map {
            SegmentPrefetchPolicy.Segment(
                durationMs = it.durationMs,
                uri = HlsManifestParser.resolve(uri, it.uri),
                rangeOffset = it.rangeOffset,
                rangeLength = it.rangeLength,
            )
        }
        val playlist = MediaPlaylist(
            uri = uri,
            segments = segments,
            hasEndList = HlsManifestParser.hasEndList(text),
            hasByteRange = HlsManifestParser.hasByteRange(text),
            targetDurationMs = HlsManifestParser.parseTargetDuration(text),
            fetchedAtMs = nowMs,
        )
        learned[uri] = playlist
        result.add(playlist)
    }
    return result
}
