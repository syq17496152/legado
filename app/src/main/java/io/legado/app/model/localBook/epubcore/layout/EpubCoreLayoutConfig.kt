package io.legado.app.model.localBook.epubcore.layout

import android.text.Layout
import android.text.TextPaint
import android.graphics.Color
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplate

/**
 * EPUB 章节布局配置。
 *
 * **本仓形态（超集）**：保留我方 canvas 阅读器既有字段
 * （`lineSpacingMultiplier` / `lineSpacingExtraPx` / `paragraphSpacingPx: Int` 等）以维持零回归；
 * 同时纳入 archive v15 的「出版方排版保真」所需字段（安全区、字重/斜体、行高、背景图、
 * 页眉页脚 chrome、阅读页模板）。默认值下 `contentHeightPx` 等计算与旧契约**逐字节等价**。
 */
data class EpubCoreLayoutConfig(
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val paddingLeftPx: Int = 0,
    val paddingTopPx: Int = 0,
    val paddingRightPx: Int = 0,
    val paddingBottomPx: Int = 0,
    val readerPaddingLeftPx: Int = 0,
    val readerPaddingTopPx: Int = 0,
    val readerPaddingRightPx: Int = 0,
    val readerPaddingBottomPx: Int = 0,
    val readerSafeInsetLeftPx: Int = 0,
    val readerSafeInsetTopPx: Int = 0,
    val readerSafeInsetRightPx: Int = 0,
    val readerSafeInsetBottomPx: Int = 0,
    val paragraphSpacingPx: Int = 16,
    val paragraphIndentPx: Float = 0f,
    val textPaint: TextPaint,
    val textFontWeight: Int = 400,
    val textFontItalic: Boolean = false,
    val readerFontFamily: String? = null,
    val readerFontUrl: String? = null,
    val readerFontPath: String? = null,
    val readerFontRevision: String? = null,
    val readerFontMimeType: String? = null,
    val readerFontLength: Long? = null,
    val readerFontOverridePublisher: Boolean = false,
    val alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    val textFullJustify: Boolean = false,
    val textBottomJustify: Boolean = true,
    val lineHeightPx: Float = textPaint.textSize,
    val lineSpacingMultiplier: Float = 1.0f,
    val lineSpacingExtraPx: Float = 0f,
    val scrollMode: Boolean = false,
    val backgroundColor: Int = Color.WHITE,
    val selectionColor: Int = Color.argb(20, 0, 0, 0),
    val readerBackgroundImage: Boolean = false,
    val readerChrome: EpubReaderChromeConfig = EpubReaderChromeConfig.DISABLED,
    val readerTemplate: EpubReaderTemplate? = null
) {
    val horizontalPaddingPx: Int
        get() = paddingLeftPx + paddingRightPx

    val verticalPaddingPx: Int
        get() = paddingTopPx + paddingBottomPx

    val contentWidthPx: Int
        get() = (pageWidthPx - horizontalPaddingPx).coerceAtLeast(1)

    /** 扣掉页面内边距与页眉/页脚保留高（默认 chrome 为 DISABLED ⇒ 与旧契约等价）。 */
    val contentHeightPx: Int
        get() = (
            pageHeightPx - verticalPaddingPx -
                readerChrome.reservedHeaderHeightPx - readerChrome.reservedFooterHeightPx
            ).coerceAtLeast(1)

    val readerContentPaddingTopPx: Int
        get() = readerPaddingTopPx + readerChrome.reservedHeaderHeightPx

    val readerContentPaddingBottomPx: Int
        get() = readerPaddingBottomPx + readerChrome.reservedFooterHeightPx

    /** 禁用/默认契约下为空串，保证既有章节缓存键逐字节兼容。 */
    val readerChromeGeometryKey: String
        get() = readerChrome.takeIf {
            it.reservedHeaderHeightPx > 0 || it.reservedFooterHeightPx > 0
        }?.geometryKey().orEmpty()

    val readerTemplateKey: String = readerTemplate?.contentHash().orEmpty()
}