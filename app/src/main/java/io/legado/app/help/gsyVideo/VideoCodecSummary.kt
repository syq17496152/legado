package io.legado.app.help.gsyVideo

/**
 * tasks §13.1：编解码摘要（补「解码器」缺口）。
 *
 * 框架限制：GSY 基类未转发 `AnalyticsListener.onDecoderInitialized`，
 * 因此改从 Format 读取 `codecs` / `sampleMimeType` / 码率 / 分辨率——同样是
 * 「用什么解码」的可观测证据，且不臆造实现名。抽成纯函数便于单测锁定格式。
 */
internal fun formatCodecSummary(
    codecs: String?,
    sampleMimeType: String?,
    bitrate: Int,
    width: Int,
    height: Int,
): String =
    "codecs=${codecs ?: "unknown"}, mime=${sampleMimeType ?: "unknown"}, " +
        "bitrate=$bitrate, size=${width}x$height"
