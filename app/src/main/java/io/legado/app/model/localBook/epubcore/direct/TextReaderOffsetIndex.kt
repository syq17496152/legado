package io.legado.app.model.localBook.epubcore.direct

/**
 * 显示 / 朗读**同源偏移索引**（epub-md-rich-rendering 阶段 2.7 配套）。
 *
 * 背景：[TextReaderDocument.Content] 已是「显示与朗读同源」的单一文本模型——
 * 显示用 `html()`、朗读用 `plainText()`，两者都从同一份 `blocks` 派生；`html()` 还会为
 * 纯文本段落写入 `data-legado-text-offset`。**但"同源"不等于"可对齐"**：朗读按字符推进，
 * 屏幕按段落/行绘制，若没有统一的偏移契约，段评气泡就会挂到错误位置（任务 2.7 的判定项）。
 *
 * 本对象把该契约显式化并可单测：
 * - [spans] 复刻 `html()` 的光标推进规则（标题占 `title.length + 1`，纯文本块占 `text.length + 1`，
 *   图片块与空文本块**不推进**），因此 `spans` 与 `html()` 的 `data-legado-text-offset`、
 *   与 `plainText()` 的字符位置**三者一致**；
 * - [blockAt] / [offsetOf] 提供「朗读字符位置 ↔ 段落」双向映射，供气泡定位与高亮对齐复用。
 *
 * 纯函数（只依赖 [TextReaderDocument.Content]）⇒ JVM 可测。
 */
object TextReaderOffsetIndex {

    /**
     * 段落偏移区间。
     *
     * @param blockIndex `content.blocks` 中的下标（标题为 -1）。
     * @param startOffset 该段在 `plainText()` 中的起始字符偏移（含换行计数）。
     * @param endOffset 该段文本结束偏移（不含其后的分隔换行）。
     * @param isTitle 是否为章节标题行。
     */
    data class BlockSpan(
        val blockIndex: Int,
        val startOffset: Int,
        val endOffset: Int,
        val isTitle: Boolean
    ) {
        fun contains(offset: Int): Boolean = offset in startOffset until endOffset
    }

    /**
     * 构造偏移索引。
     *
     * @param includeTitle 与 `plainText(includeTitle)`/`html(includeTitle)` 保持同一开关。
     */
    fun spans(content: TextReaderDocument.Content, includeTitle: Boolean): List<BlockSpan> {
        val spans = arrayListOf<BlockSpan>()
        var cursor = 0
        if (includeTitle && content.title.isNotBlank()) {
            val title = content.title
            spans += BlockSpan(
                blockIndex = TitleBlockIndex,
                startOffset = cursor,
                endOffset = cursor + title.length,
                isTitle = true
            )
            cursor += title.length + 1
        }
        content.blocks.forEachIndexed { index, block ->
            // 与 html() 的行内偏移写入条件一致：仅「非图片块且文本非空」推进光标。
            if (block.image != null || block.text.isEmpty()) return@forEachIndexed
            spans += BlockSpan(
                blockIndex = index,
                startOffset = cursor,
                endOffset = cursor + block.text.length,
                isTitle = false
            )
            cursor += block.text.length + 1
        }
        return spans
    }

    /** 朗读字符位置 → 所在段落（含边界：等于段末偏移时归入该段）。 */
    fun blockAt(spans: List<BlockSpan>, charOffset: Int): BlockSpan? {
        if (spans.isEmpty() || charOffset < 0) return null
        return spans.firstOrNull { it.contains(charOffset) }
            ?: spans.lastOrNull()?.takeIf { charOffset == it.endOffset }
    }

    /** 段落下标 → 该段起始字符偏移（未知返回 null）。 */
    fun offsetOf(spans: List<BlockSpan>, blockIndex: Int): Int? {
        return spans.firstOrNull { it.blockIndex == blockIndex }?.startOffset
    }

    /** 索引覆盖的字符总数（= `plainText` 的字符长度口径，含分隔换行）。 */
    fun totalChars(spans: List<BlockSpan>): Int {
        val last = spans.lastOrNull() ?: return 0
        return last.endOffset + 1
    }

    const val TitleBlockIndex = -1
}