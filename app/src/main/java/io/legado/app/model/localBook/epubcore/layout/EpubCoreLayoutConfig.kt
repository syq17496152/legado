package io.legado.app.model.localBook.epubcore.layout

import android.text.Layout
import android.text.TextPaint

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
    val paragraphSpacingPx: Int = 16,
    val textPaint: TextPaint,
    val readerFontFamily: String? = null,
    val readerFontUrl: String? = null,
    val readerFontPath: String? = null,
    val alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    val textFullJustify: Boolean = false,
    val lineSpacingMultiplier: Float = 1.0f,
    val lineSpacingExtraPx: Float = 0f,
    val scrollMode: Boolean = false
) {
    val horizontalPaddingPx: Int
        get() = paddingLeftPx + paddingRightPx

    val verticalPaddingPx: Int
        get() = paddingTopPx + paddingBottomPx

    val contentWidthPx: Int
        get() = (pageWidthPx - horizontalPaddingPx).coerceAtLeast(1)

    val contentHeightPx: Int
        get() = (pageHeightPx - verticalPaddingPx).coerceAtLeast(1)
}
