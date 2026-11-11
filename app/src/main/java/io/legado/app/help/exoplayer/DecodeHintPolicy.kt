package io.legado.app.help.exoplayer

/**
 * 解码降级提示判据（纯函数）。
 *
 * 设计依据：docs/specs/video-buffer-prefetch-v2/design.md §1.17 / E4
 *
 * 诚实边界：本仓无法可靠区分硬解/软解，因此只用「分辨率 + 编码 + 丢帧」启发式，
 * 只提示、不自动改清晰度、每会话最多一次。
 */
object DecodeHintPolicy {

    data class VideoFormat(
        val mime: String?,
        val codecs: String?,
        val width: Int,
        val height: Int,
    )

    data class Signal(
        val format: VideoFormat?,
        val droppedFrames: Int,
        val windowMs: Long,
        val alreadyHinted: Boolean,
    )

    /** 丢帧统计窗口下限（太短的样本不下结论） */
    const val MIN_WINDOW_MS = 5_000L

    /** 触发提示的最小丢帧数 */
    const val MIN_DROPPED_FRAMES = 3

    fun shouldHint(signal: Signal): Boolean {
        if (signal.alreadyHinted) return false
        if (signal.windowMs < MIN_WINDOW_MS) return false
        if (signal.droppedFrames < MIN_DROPPED_FRAMES) return false
        val format = signal.format ?: return false
        if (format.height <= 0) return false
        val heavyCodec = isHeavyCodec(format)
        val highRes = format.height >= 1080
        return highRes || (heavyCodec && format.height >= 720)
    }

    fun isHeavyCodec(format: VideoFormat): Boolean {
        val codec = (format.codecs ?: "").lowercase()
        val mime = (format.mime ?: "").lowercase()
        return codec.startsWith("hvc") || codec.startsWith("hev") ||
            mime.contains("hevc") || mime.contains("dolby-vision") ||
            codec.startsWith("av01")
    }
}
