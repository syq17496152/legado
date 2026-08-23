package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Canvas
import android.os.Build
import androidx.annotation.Keep
import io.legado.app.help.HighlightStyle
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.HighlightDraw
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine
import io.legado.app.ui.book.read.page.provider.ChapterProvider

/**
 * 文字列
 */
@Keep
data class TextColumn(
    override var start: Float,
    override var end: Float,
    override val charData: String,
) : TextBaseColumn {

    override var textLine: TextLine = emptyTextLine

    override var selected: Boolean = false
        set(value) {
            if (field != value) {
                textLine.invalidate()
            }
            field = value
        }
    override var isSearchResult: Boolean = false
        set(value) {
            if (field != value) {
                textLine.invalidate()
                if (value) {
                    textLine.searchResultColumnCount++
                } else {
                    textLine.searchResultColumnCount--
                }
            }
            field = value
        }

    /** 高亮规则/手动高亮为当前列合并出的样式;null=无高亮 */
    var highlightStyle: HighlightStyle? = null
        set(value) {
            if (field != value) {
                textLine.invalidate()
                if (field?.needsPerColumnDraw == true) textLine.styledColumnCount--
                if (value?.needsPerColumnDraw == true) textLine.styledColumnCount++
            }
            field = value
        }

    override fun draw(view: ContentTextView, canvas: Canvas) {
        val hs = highlightStyle
        val textPaint = if (textLine.isTitle) {
            ChapterProvider.titlePaint
        } else {
            ChapterProvider.contentPaint
        }
        val baseColor = if (textLine.isReadAloud || isSearchResult) {
            ReadBookConfig.textAccentColor
        } else {
            ReadBookConfig.textColor
        }
        // 背景填充(文字之下)
        val fill = hs?.fill ?: 0
        if (fill != 0) {
            view.drawHighlightFill(canvas, start, 0f, end, textLine.height, fill)
        }
        // 字色: 高亮优先
        val hsTextColor = hs?.textColor ?: 0
        val textColorVal = if (hsTextColor != 0) hsTextColor else baseColor
        if (textPaint.color != textColorVal) {
            textPaint.color = textColorVal
        }
        // 字体/粗斜体
        val saved = if (hs != null && hs.needsPerColumnDraw) {
            HighlightDraw.applyTextStyle(textPaint, hs)
        } else {
            null
        }
        val enablePaperInk = !textLine.isReadAloud && !isSearchResult
        val y = textLine.lineBase - textLine.lineTop
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val letterSpacing = textPaint.letterSpacing * textPaint.textSize
            val letterSpacingHalf = letterSpacing * 0.5f
            view.drawTextWithPaperInk(canvas, charData, start + letterSpacingHalf, y, textPaint, enablePaperInk)
        } else {
            view.drawTextWithPaperInk(canvas, charData, start, y, textPaint, enablePaperInk)
        }
        saved?.let { HighlightDraw.restoreTextStyle(textPaint, it) }
        // 着重号
        val emphasis = hs?.emphasis
        if (emphasis != null) {
            val emColor = if (emphasis.color != 0) emphasis.color else textColorVal
            HighlightDraw.drawEmphasis(canvas, start, end, textLine.height, emColor)
        }
        if (selected) {
            view.drawSelectedRect(canvas, start, 0f, end, textLine.height)
        }
    }

}
