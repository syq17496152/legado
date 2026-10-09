package io.legado.app.model.reader.render

/**
 * 统一坐标（epub-md-rich-rendering 阶段 2.9 / AD-14 修订版）。
 *
 * 修订要点：**只统一「可表达的位置」，不做假换算**。
 * - 文本类（在线正文 / txt / md / 可重排 EPUB）用 **字符偏移** [Text]；
 * - 固定版式 / 媒体 / 交互态用 **页·区域锚点** [Page]（不可换算，保位仅同后端）；
 * - 漫画 / 视频 / 音频用各自锚点 [Media]（页 / 进度百分比）。
 *
 * 跨态切换（如 EPUB 从 canvas 切到 WebView 后端）**不尝试像素级换算**，
 * 统一退化为「章首 + 章内相对比例」的锚点，保证不丢位置、不跳章。
 */
sealed interface ReadingPosition {

    val chapterIndex: Int

    /** 文本类：章内字符偏移（同后端可无损恢复，跨后端可经比例降级）。 */
    data class Text(
        override val chapterIndex: Int,
        val charOffset: Int
    ) : ReadingPosition {
        init {
            require(charOffset >= 0) { "字符偏移不得为负：$charOffset" }
        }
    }

    /** 固定/媒体/交互：页索引 + 页内相对比例（0..1）。 */
    data class Page(
        override val chapterIndex: Int,
        val pageIndex: Int,
        val inPageRatio: Float = 0f
    ) : ReadingPosition {
        init {
            require(pageIndex >= 0) { "页索引不得为负：$pageIndex" }
            require(inPageRatio in 0f..1f) { "页内比例须在 0..1：$inPageRatio" }
        }
    }

    /** 非文本形态：进度百分比（0..1）。 */
    data class Media(
        override val chapterIndex: Int,
        val progressPercent: Float
    ) : ReadingPosition {
        init {
            require(progressPercent in 0f..1f) { "进度须在 0..1：$progressPercent" }
        }
    }
}

/**
 * 位置降级与换算判据（纯函数，JVM 可测）。
 *
 * 原则（AD-14）：**换算不可靠时以章内锚点兜底，并如实标记降级**，
 * 绝不产出「看起来精确实则错误」的位置。
 */
object ReadingPositionPolicy {

    /** 换算结果：位置 + 是否发生了降级（供宿主埋点/提示）。 */
    data class Resolution(
        val position: ReadingPosition,
        val degraded: Boolean
    )

    /**
     * 目标后端能否无损恢复该位置。
     *
     * @param position 已保存的位置。
     * @param targetSupportsTextOffset 目标后端是否支持字符偏移（`capabilities.supportsTextSelection` 的近义判据）。
     */
    fun isLossless(position: ReadingPosition, targetSupportsTextOffset: Boolean): Boolean {
        return when (position) {
            is ReadingPosition.Text -> targetSupportsTextOffset
            is ReadingPosition.Page -> true
            is ReadingPosition.Media -> true
        }
    }

    /**
     * 把位置解析为目标后端可用的形式。
     *
     * - 原生支持 ⇒ 原样返回（`degraded=false`）；
     * - [ReadingPosition.Text] 落到不支持字符偏移的后端 ⇒ 降级为**章内锚点**
     *   （[ReadingPosition.Page] 第 0 页）并标记 `degraded=true`；
     * - 其余类型不做跨形态转换（各自锚点已是最终形态）。
     */
    fun resolveFor(
        position: ReadingPosition,
        targetSupportsTextOffset: Boolean
    ): Resolution {
        return when {
            isLossless(position, targetSupportsTextOffset) -> Resolution(position, degraded = false)
            position is ReadingPosition.Text -> Resolution(
                ReadingPosition.Page(chapterIndex = position.chapterIndex, pageIndex = 0, inPageRatio = 0f),
                degraded = true
            )
            else -> Resolution(position, degraded = false)
        }
    }

    /**
     * 章内相对比例（0..1）用于「同章换后端」时报位。
     *
     * @param position 当前位置。
     * @param chapterCharCount 该章总字符数（文本类）；非正数返回 null。
     */
    fun chapterRatio(position: ReadingPosition, chapterCharCount: Int): Float? {
        return when (position) {
            is ReadingPosition.Text -> chapterCharCount
                .takeIf { it > 0 }
                ?.let { (position.charOffset.toFloat() / it).coerceIn(0f, 1f) }
            is ReadingPosition.Page -> position.inPageRatio.coerceIn(0f, 1f)
            is ReadingPosition.Media -> position.progressPercent.coerceIn(0f, 1f)
        }
    }

    /**
     * 由章内相对比例构造目标后端的锚点位置（跨后端换挡的兜底路径）。
     *
     * 说明：文本类**不再反推字符数**——反推需要双方一致的分页/字符计数口径，
     * 属于「假换算」的典型场景（AD-14 明确禁止）；故统一给出章内锚点。
     */
    fun anchorFromRatio(chapterIndex: Int, ratio: Float): ReadingPosition {
        val clamped = ratio.coerceIn(0f, 1f)
        return ReadingPosition.Page(chapterIndex = chapterIndex, pageIndex = 0, inPageRatio = clamped)
    }
}