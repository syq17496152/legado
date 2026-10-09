package io.legado.app.model.localBook.epubcore.direct

/**
 * 五态分流 → 渲染参数判据（纯函数，JVM 可测；AD-06 / AD-15 / AD-30 §二）。
 *
 * 判定目标：**只有可重排纯文字章节做阅读器归一化**（阅读器主题 insets / 字体 / 列宽），
 * 其余四态保留出版方盒模型契约（不注入阅读器排版），从而兼顾「主题一致性」与「出版方保真」。
 *
 * 迁移自 archive v15 的分类消费点（`EpubDirectDocumentBuilder` 的 padding 契约注释），
 * 并按我方需求显式落为策略对象：判据可单测、可审计、默认值下与旧行为逐字节等价。
 */
internal object EpubReaderContentModePolicy {

    /**
     * 解析一个章节的渲染参数。
     *
     * @param layoutMode 五态分类结果。
     * @param publisherViewportWidth 出版方声明视口宽（px，逻辑像素）；未知传 null。
     * @param publisherViewportHeight 出版方声明视口高；未知传 null。
     * @param writingMode 出版方根节点 writing-mode（如 `vertical-rl`）；未知传 null。
     */
    fun resolve(
        layoutMode: EpubDirectLayoutMode,
        publisherViewportWidth: Float? = null,
        publisherViewportHeight: Float? = null,
        writingMode: String? = null
    ): EpubReaderContentMode {
        val viewportWidth = publisherViewportWidth?.takeIf { it.isFinite() && it > 0f }
        val viewportHeight = publisherViewportHeight?.takeIf { it.isFinite() && it > 0f }
        val hasViewport = viewportWidth != null && viewportHeight != null
        val normalizedWritingMode = writingMode
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.startsWith("vertical") }
        return when (layoutMode) {
            // 可重排纯文字：阅读器归一化（主题/字体/insets 生效），多页分栏。
            EpubDirectLayoutMode.REFLOWABLE -> EpubReaderContentMode(
                layoutMode = layoutMode,
                preservePublisherLayout = false,
                singlePage = false,
                scaleToPublisherViewport = false,
                publisherViewportWidthPx = viewportWidth,
                publisherViewportHeightPx = viewportHeight,
                verticalWriting = false
            )
            // 出版方语义排版（多栏/竖排/绝对定位）：不加阅读器归一化，但保持可滚动多页。
            EpubDirectLayoutMode.PUBLISHER_STYLED -> EpubReaderContentMode(
                layoutMode = layoutMode,
                preservePublisherLayout = true,
                singlePage = false,
                scaleToPublisherViewport = false,
                publisherViewportWidthPx = viewportWidth,
                publisherViewportHeightPx = viewportHeight,
                verticalWriting = normalizedWritingMode != null
            )
            // 固定版式：单页独占视口；声明了视口则等比缩放贴合阅读区。
            EpubDirectLayoutMode.FIXED -> EpubReaderContentMode(
                layoutMode = layoutMode,
                preservePublisherLayout = true,
                singlePage = true,
                scaleToPublisherViewport = hasViewport,
                publisherViewportWidthPx = viewportWidth,
                publisherViewportHeightPx = viewportHeight,
                verticalWriting = normalizedWritingMode != null
            )
            // 媒体/图库：单页独占；有视口则缩放（svg/固定图集需按声明尺寸适配）。
            EpubDirectLayoutMode.MEDIA -> EpubReaderContentMode(
                layoutMode = layoutMode,
                preservePublisherLayout = true,
                singlePage = true,
                scaleToPublisherViewport = hasViewport,
                publisherViewportWidthPx = viewportWidth,
                publisherViewportHeightPx = viewportHeight,
                verticalWriting = false
            )
            // 交互态：单页独占；不缩放（脚本自管画布，缩放会破坏命中测试）。
            EpubDirectLayoutMode.INTERACTIVE -> EpubReaderContentMode(
                layoutMode = layoutMode,
                preservePublisherLayout = true,
                singlePage = true,
                scaleToPublisherViewport = false,
                publisherViewportWidthPx = viewportWidth,
                publisherViewportHeightPx = viewportHeight,
                verticalWriting = false
            )
        }
    }
}

/**
 * 章节渲染参数（由 [EpubReaderContentModePolicy] 产出，供布局请求/后端分流消费）。
 *
 * 关键判据：`preservePublisherLayout` 为 true 时**不得**注入阅读器归一化
 * （字体/insets/列宽强制），否则出版方盒模型被破坏（archive 的 padding 契约）。
 */
internal data class EpubReaderContentMode(
    val layoutMode: EpubDirectLayoutMode,
    /** true ⇒ 保留出版方排版（跳过阅读器归一化）。 */
    val preservePublisherLayout: Boolean,
    /** true ⇒ 单页独占视口（固定版式/媒体/交互）。 */
    val singlePage: Boolean,
    /** true ⇒ 按出版方声明视口等比缩放贴合阅读区。 */
    val scaleToPublisherViewport: Boolean,
    val publisherViewportWidthPx: Float?,
    val publisherViewportHeightPx: Float?,
    /** true ⇒ 竖排（`writing-mode: vertical-*`）透传。 */
    val verticalWriting: Boolean
) {
    /** true ⇒ 需要注入阅读器主题（字体/安全区/列宽）。 */
    val readerNormalized: Boolean
        get() = !preservePublisherLayout

    /** 缓存键片段：同章在不同内容态下不得互相命中。 */
    fun cacheKey(): String = buildString {
        append(layoutMode.name)
        append('|').append(if (preservePublisherLayout) "preserve" else "normalize")
        append('|').append(if (singlePage) "single" else "flow")
        append('|').append(if (scaleToPublisherViewport) "scale" else "noscale")
        append('|').append(if (verticalWriting) "vertical" else "horizontal")
        if (scaleToPublisherViewport) {
            append('|').append(publisherViewportWidthPx).append('x').append(publisherViewportHeightPx)
        }
    }
}