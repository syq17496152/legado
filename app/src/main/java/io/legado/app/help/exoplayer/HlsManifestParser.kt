package io.legado.app.help.exoplayer

import io.legado.app.help.video.engine.M3u8Parser

/**
 * HLS 清单的时间轴解析（video-buffer-prefetch-v2 §1.3）。
 *
 * 与 `M3u8Parser.parseSegments` 的分工：
 * - `M3u8Parser`（下载侧）只返回 URI 列表 + 总时长；
 * - 本对象返回**逐片时长**（构造预取时间窗口必需），并补充直播/字节区间标签判定。
 *
 * 纯文本、无网络/Android 依赖；相对地址仍复用 `M3u8Parser.resolveRelative`。
 */
object HlsManifestParser {

    /**
     * 单个分片：时长（毫秒）+ 清单中的原始 URI（可能是相对地址）。
     *
     * `rangeLength>0` 表示该分片是 `#EXT-X-BYTERANGE` 字节区间（fMP4/单文件多片常见），
     * 预取必须按 `[rangeOffset, rangeOffset+rangeLength)` 读，key 仍是同一 URI。
     */
    data class SegmentInfo(
        val durationMs: Long,
        val uri: String,
        val rangeOffset: Long = -1L,
        val rangeLength: Long = 0L,
    )

    /**
     * 解析媒体清单中的分片及其逐片时长。
     *
     * 规则：`#EXTINF:<秒>,` 后第一个非 `#` 行为分片 URI；缺失/非法 EXTINF 的分片跳过。
     * 紧邻的 `#EXT-X-BYTERANGE:<len>[@<offset>]` 生效于其后的分片；省略 offset 时
     * 按 HLS 规范继承「同一 URI 上一个分片的结束位置」。
     */
    fun parseSegmentsWithDurations(playlist: String): List<SegmentInfo> {
        val result = mutableListOf<SegmentInfo>()
        val lines = playlist.lineSequence().toList()
        var i = 0
        var pendingRangeLen = 0L
        var pendingRangeOffset = -1L
        var lastUri = ""
        var lastRangeEnd = 0L
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-BYTERANGE")) {
                val spec = line.substringAfter(':').trim()
                val len = spec.substringBefore('@').toLongOrNull() ?: 0L
                val offset = spec.substringAfter('@', "").toLongOrNull() ?: -1L
                pendingRangeLen = if (len > 0) len else 0L
                pendingRangeOffset = offset
                i++
                continue
            }
            if (line.startsWith("#EXTINF")) {
                val seconds = Regex("""#EXTINF:\s*([0-9.]+)""")
                    .find(line)?.groupValues?.get(1)?.toDoubleOrNull()
                var j = i + 1
                // 中间可能再出现 BYTERANGE（规范允许 EXTINF 与 URI 之间），一并消费
                while (j < lines.size && lines[j].trim().startsWith("#")) {
                    val mid = lines[j].trim()
                    if (mid.startsWith("#EXT-X-BYTERANGE")) {
                        val spec = mid.substringAfter(':').trim()
                        val len = spec.substringBefore('@').toLongOrNull() ?: 0L
                        val offset = spec.substringAfter('@', "").toLongOrNull() ?: -1L
                        pendingRangeLen = if (len > 0) len else 0L
                        pendingRangeOffset = offset
                    }
                    j++
                }
                if (j < lines.size) {
                    val uri = lines[j].trim()
                    if (seconds != null && seconds > 0 && uri.isNotEmpty() && !uri.startsWith("#")) {
                        var offset = -1L
                        var length = 0L
                        if (pendingRangeLen > 0) {
                            length = pendingRangeLen
                            offset = when {
                                pendingRangeOffset >= 0L -> pendingRangeOffset
                                uri == lastUri -> lastRangeEnd
                                else -> 0L
                            }
                            lastRangeEnd = offset + length
                            lastUri = uri
                        }
                        result.add(
                            SegmentInfo(
                                durationMs = (seconds * 1000).toLong(),
                                uri = uri,
                                rangeOffset = offset,
                                rangeLength = length,
                            )
                        )
                    }
                }
                pendingRangeLen = 0L
                pendingRangeOffset = -1L
                i = j
            } else {
                i++
            }
        }
        return result
    }

    /** 解析 `#EXT-X-TARGETDURATION`（秒 → 毫秒）；缺失返回 0 */
    fun parseTargetDuration(playlist: String): Long =
        Regex("""#EXT-X-TARGETDURATION:\s*([0-9.]+)""")
            .find(playlist)?.groupValues?.get(1)?.toDoubleOrNull()
            ?.let { (it * 1000).toLong() } ?: 0L

    /** 是否点播（含 ENDLIST）；直播清单无该标签 */
    fun hasEndList(playlist: String): Boolean =
        playlist.lineSequence().any { it.trim().startsWith("#EXT-X-ENDLIST") }

    /** 是否存在字节区间分片（本期不预取） */
    fun hasByteRange(playlist: String): Boolean =
        playlist.lineSequence().any { it.trim().startsWith("#EXT-X-BYTERANGE") }

    /** 绝对地址解析（复用 M3u8Parser 的既有实现，避免两套解析漂移） */
    fun resolve(base: String, path: String): String = M3u8Parser.resolveRelative(base, path)
}
