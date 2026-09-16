package io.legado.app.ui.book.read.page.entities

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint.FontMetrics
import android.os.Build
import android.text.TextPaint
import androidx.annotation.Keep
import io.legado.app.help.HighlightFillRuns
import io.legado.app.help.HighlightGeometry
import io.legado.app.help.HighlightPalette
import io.legado.app.help.HighlightStyle
import io.legado.app.help.PaintPool
import io.legado.app.help.book.isImage
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.HighlightDraw
import io.legado.app.ui.book.read.page.entities.TextPage.Companion.emptyTextPage
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.canvasrecorder.recordIfNeededThenDraw
import io.legado.app.utils.dpToPx

/**
 * 行信息
 */
@Keep
@Suppress("unused", "MemberVisibilityCanBePrivate")
data class TextLine(
    var text: String = "",
    private val textColumns: ArrayList<BaseColumn> = arrayListOf(),
    var lineTop: Float = 0f,
    var lineBase: Float = 0f,
    var lineBottom: Float = 0f,
    var indentWidth: Float = 0f,
    var paragraphNum: Int = 0,
    var sourceIndex: Int = -1,
    var chapterPosition: Int = 0,
    var pagePosition: Int = 0,
    val isTitle: Boolean = false,
    var isParagraphEnd: Boolean = false,
    var isImage: Boolean = false,
    var isHtml: Boolean = false,
    var startX: Float = 0f,
    var indentSize: Int = 0,
    var extraLetterSpacing: Float = 0f,
    var extraLetterSpacingOffsetX: Float = 0f,
    var wordSpacing: Float = 0f,
    var exceed: Boolean = false,
    var onlyTextColumn: Boolean = true,
) {

    val columns: List<BaseColumn> get() = textColumns
    val charSize: Int get() = text.length
    val lineStart: Float get() = textColumns.firstOrNull()?.start ?: 0f
    val lineEnd: Float get() = textColumns.lastOrNull()?.end ?: 0f
    val chapterIndices: IntRange get() = chapterPosition..chapterPosition + charSize
    val height: Float inline get() = lineBottom - lineTop
    val canvasRecorder = CanvasRecorderFactory.create()
    var searchResultColumnCount = 0
    /** 有「需要逐列绘制」高亮装饰(非纯背景 fill)的列数,>0 时禁用快绘路径 */
    var styledColumnCount = 0

    /** R1a：本行各列的最终样式缓存（一次 resolve，填充/装饰 run 共用），随行绘制重建 */
    private var resolvedStyles: Array<HighlightStyle?> = emptyArray()
    var isReadAloud: Boolean = false
        set(value) {
            if (field != value) {
                invalidate()
            }
            if (value) {
                textPage.hasReadAloudSpan = true
            }
            field = value
        }
    var textPage: TextPage = emptyTextPage
    var isLeftLine = true

    fun addColumn(column: BaseColumn) {
        if (column !is TextColumn) {
            onlyTextColumn = false
        }
        column.textLine = this
        textColumns.add(column)
    }

    fun addColumns(columns: Collection<BaseColumn>) {
        onlyTextColumn = false
        columns.forEach { column ->
            column.textLine = this
        }
        textColumns.addAll(columns)
    }

    fun getColumn(index: Int): BaseColumn {
        return textColumns.getOrElse(index) {
            textColumns.last()
        }
    }

    fun getColumnReverseAt(index: Int, offset: Int = 0): BaseColumn {
        return textColumns[textColumns.lastIndex - offset - index]
    }

    fun getColumnsCount(): Int {
        return textColumns.size
    }

    fun upTopBottom(durY: Float, textHeight: Float, fontMetrics: FontMetrics) {
        lineTop = ChapterProvider.paddingTop + durY
        lineBottom = lineTop + textHeight
        lineBase = lineBottom - fontMetrics.descent
    }

    fun isTouch(x: Float, y: Float, relativeOffset: Float): Boolean {
        return y > lineTop + relativeOffset
                && y < lineBottom + relativeOffset
                && x >= lineStart
                && x <= lineEnd + 20.dpToPx()
    }

    fun isTouchY(y: Float, relativeOffset: Float): Boolean {
        return y > lineTop + relativeOffset
                && y < lineBottom + relativeOffset
    }

    fun isVisible(relativeOffset: Float): Boolean {
        val top = lineTop + relativeOffset
        val bottom = lineBottom + relativeOffset
        val width = bottom - top
        val visibleTop = ChapterProvider.paddingTop
        val visibleBottom = ChapterProvider.visibleBottom
        val visible = when {
            // 完全可视
            top >= visibleTop && bottom <= visibleBottom -> true
            top <= visibleTop && bottom >= visibleBottom -> true
            // 上方第一行部分可视
            top < visibleTop && bottom > visibleTop && bottom < visibleBottom -> {
                if (isImage) {
                    true
                } else {
                    val visibleRate = (bottom - visibleTop) / width
                    visibleRate > 0.6
                }
            }
            // 下方第一行部分可视
            top > visibleTop && top < visibleBottom && bottom > visibleBottom -> {
                if (isImage) {
                    true
                } else {
                    val visibleRate = (visibleBottom - top) / width
                    visibleRate > 0.6
                }
            }
            // 不可视
            else -> false
        }
        return visible
    }

    fun draw(view: ContentTextView, canvas: Canvas) {
        if (AppConfig.optimizeRender) {
            canvasRecorder.recordIfNeededThenDraw(canvas, view.width, height.toInt()) {
                drawTextLine(view, this)
            }
        } else {
            drawTextLine(view, canvas)
        }
    }

    private fun drawTextLine(view: ContentTextView, canvas: Canvas) {
        // R1a：填充恒在文字之下——统一在两条路径之前按 run 合并绘制（含缩进列与非矩形形状判定）。
        // D5：此处统一 resolve 色板（快绘路径此前不解析 → 墨水屏"纯填充"派生样式缺下划线）。
        resolveStyles()
        drawHighlightFillRuns(view, canvas)
        if (checkFastDraw()) {
            fastDrawTextLine(view, canvas)
        } else {
            for (i in columns.indices) {
                columns[i].draw(view, canvas)
            }
            drawHighlightRuns(view, canvas)
        }

        // 墨水屏模式下的朗读和搜索下划线
        if (AppConfig.isEInkMode && (isReadAloud || searchResultColumnCount > 0)) {
            val underlinePaint = PaintPool.obtain()
            underlinePaint.set(ChapterProvider.contentPaint)
            underlinePaint.strokeWidth = 1.dpToPx().toFloat()
            val lineY = height - 1.dpToPx()
            canvas.drawLine(lineStart + indentWidth, lineY, lineEnd, lineY, underlinePaint)
            PaintPool.recycle(underlinePaint)
        }

        val underlineMode = ReadBookConfig.underlineMode
        if (underlineMode == 0) return
        if (!isImage && !isHtml && ReadBook.book?.isImage != true) {
            drawUnderline(canvas, underlineMode)
        }
    }

    @SuppressLint("NewApi")
    private fun fastDrawTextLine(view: ContentTextView, canvas: Canvas) {
        val textPaint = if (isTitle) {
            ChapterProvider.titlePaint
        } else {
            ChapterProvider.contentPaint
        }
        val textColor = if (isReadAloud) {
            ReadBookConfig.textAccentColor
        } else {
            ReadBookConfig.textColor
        }
        if (textPaint.color != textColor) {
            textPaint.color = textColor
        }
        val paint = PaintPool.obtain()
        paint.set(textPaint)
        val letterSpacing = paint.letterSpacing * paint.textSize
        val letterSpacingHalf = letterSpacing * 0.5f
        if (extraLetterSpacing != 0f) {
            paint.letterSpacing += extraLetterSpacing
        }
        if (wordSpacing != 0f) {
            paint.wordSpacing = wordSpacing
        }
        val offsetX = if (atLeastApi35) letterSpacingHalf else extraLetterSpacingOffsetX
        view.drawTextWithPaperInk(
            canvas = canvas,
            text = text,
            start = indentSize,
            end = text.length,
            x = startX + offsetX,
            y = lineBase - lineTop,
            paint = paint,
            enableBlend = !isReadAloud
        )
        PaintPool.recycle(paint)
        for (i in columns.indices) {
            val column = columns[i] as TextColumn
            if (column.selected) {
                view.drawSelectedRect(canvas, column.start, 0f, column.end, height)
            }
        }
    }

    /**
     * R1a：一次性解析本行各列的最终样式（色板按当前阅读器态解析），
     * 供「填充 run」与「装饰 run」共用 → **避免同一列被重复 resolve**（原逐列二次解析的开销）。
     */
    private fun resolveStyles(): Array<HighlightStyle?> {
        val n = columns.size
        if (resolvedStyles.size != n) {
            resolvedStyles = arrayOfNulls(n)
        }
        for (i in 0 until n) {
            resolvedStyles[i] = (columns[i] as? TextColumn)?.highlightStyle?.let {
                HighlightPalette.resolve(it)
            }
        }
        return resolvedStyles
    }

    /**
     * R1a：合并连续「同填充色 + 同形状」的列，一次性绘制背景填充（6 形状）。
     *
     * 与装饰 run 分离：填充必须在文字之下，故在 [drawTextLine] 最前调用。
     * 缩进列：非矩形形状跳过（矩形照常铺满，避免整段底色缺口）。
     */
    private fun drawHighlightFillRuns(view: ContentTextView, canvas: Canvas) {
        val cols = columns
        val n = cols.size
        val baseline = lineBase - lineTop
        val textSize = if (isTitle) ChapterProvider.titlePaint.textSize
        else ChapterProvider.contentPaint.textSize
        var i = 0
        while (i < n) {
            val col = cols[i]
            if (col !is TextColumn) { i++; continue }
            val hs = resolvedStyles[i] ?: run { i++; continue }
            val fill = hs.fill
            if (fill == 0) { i++; continue }
            val shape = hs.resolvedFillShape
            // 缩进列不承载非矩形形状填充
            if (col.isParagraphIndent && hs.hasNonRectFillShape) { i++; continue }
            // 合并连续同样式列（断 run：样式不同 / 非 TextColumn / 需跳过的缩进列）
            var runEnd = col.end
            var j = i + 1
            while (j < n) {
                val next = cols[j] as? TextColumn ?: break
                val nhs = resolvedStyles[j] ?: break
                // R1a：run 合并判等抽为纯函数（`HighlightFillRuns`，可 JVM 单测）
                if (!HighlightFillRuns.sameFillRun(hs, nhs)) break
                if (next.isParagraphIndent && nhs.hasNonRectFillShape) break
                runEnd = next.end
                j++
            }
            val band = HighlightGeometry.fillBand(baseline, textSize, height, shape)
            val pillRadius = if (shape == HighlightStyle.FillShape.PILL) {
                HighlightGeometry.pillRadiusX(band)
            } else {
                0f
            }
            view.drawHighlightFill(
                canvas, col.start, band.top, runEnd, band.bottom,
                fill, shape, pillRadius, pillRadius
            )
            i = j
        }
    }

    /**
     * 绘制下划线
     */
    private fun drawUnderline(canvas: Canvas, underlineMode: Int) {
        val paint = ChapterProvider.contentPaint
        val distance = (ChapterProvider.lineSpacingExtra * 10 - 11).coerceIn(-1f, 10f)
        val lineY = height + distance.dpToPx()
        if (underlineMode == 1) {
            canvas.drawLine(
                lineStart + indentWidth,
                lineY,
                lineEnd,
                lineY,
                paint
            )
        } else if (underlineMode == 2) { // 虚线
            val dashPathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
            val dashPath = TextPaint(paint)
            dashPath.pathEffect = dashPathEffect
            canvas.drawLine(
                lineStart + indentWidth,
                lineY,
                lineEnd,
                lineY,
                dashPath
            )
        }
    }

    fun checkFastDraw(): Boolean {
        if (!AppConfig.optimizeRender || exceed || !onlyTextColumn || textPage.isMsgPage) {
            return false
        }
        if (wordSpacing != 0f && (!atLeastApi26 || !wordSpacingWorking)) {
            return false
        }
        if (styledColumnCount != 0) {
            // 有需要逐列绘制的高亮装饰时,快绘路径无法绘制,降级为普通路径
            return false
        }
        return searchResultColumnCount == 0
    }

    /** 合并连续同样式列, 一次性绘制下划线/删除线/方框装饰 */
    private fun drawHighlightRuns(view: ContentTextView, canvas: Canvas) {
        var i = 0
        val cols = columns
        val n = cols.size
        val baseline = lineBase - lineTop
        while (i < n) {
            val col = cols[i]
            if (col !is TextColumn) { i++; continue }
            // R12.4：色板派生样式按当前阅读器态解析后再判定/绘制（复用本行一次性解析结果，避免逐列二次解析）
            val hs = resolvedStyles[i] ?: run { i++; continue }
            // R3：段首缩进列不承载装饰类高亮（方框/下划线等不覆盖缩进）
            if (col.isParagraphIndent && !HighlightDraw.shouldRenderOnIndentColumn(hs)) { i++; continue }
            // 合并起点
            val runStart = col.start
            var runEnd = col.end
            var underline = hs.underline
            var strike = hs.strike
            var box = hs.box
            j@ for (j in i + 1 until n) {
                val next = cols[j]
                if (next !is TextColumn) continue@j
                // R3：遇到需要跳过的缩进列即中断本 run，避免装饰跨越缩进
                val nextResolved = resolvedStyles[j] ?: break@j
                if (next.isParagraphIndent &&
                    !HighlightDraw.shouldRenderOnIndentColumn(nextResolved)
                ) break@j
                val nhs = nextResolved
                if (nhs.underline != underline || nhs.strike != strike || nhs.box != box) {
                    break@j
                }
                runEnd = next.end
            }
            HighlightDraw.drawRun(
                canvas, runStart, runEnd, baseline, height,
                underline, strike, box, ReadBookConfig.textColor
            )
            // 跳到下一个不同样式的列（R3：需跳过的缩进列同样不并入本 run）
            var k = i + 1
            while (k < n) {
                val next = cols[k] as? TextColumn ?: break
                val nextResolved = resolvedStyles[k]
                if (next.isParagraphIndent &&
                    !HighlightDraw.shouldRenderOnIndentColumn(nextResolved)
                ) break
                val sameStyle = nextResolved?.let {
                    it.underline == underline && it.strike == strike && it.box == box
                } == true
                if (!sameStyle) break
                k++
            }
            i = k
        }
    }

    fun invalidate() {
        invalidateSelf()
        textPage.invalidate()
    }

    fun invalidateSelf() {
        canvasRecorder.invalidate()
    }

    fun recycleRecorder() {
        canvasRecorder.recycle()
    }

    @SuppressLint("NewApi")
    companion object {
        val emptyTextLine = TextLine()
        private val atLeastApi26 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        val atLeastApi28 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        private val atLeastApi35 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
        private val wordSpacingWorking by lazy {
            // issue 3785 3846
            val paint = PaintPool.obtain()
            val text = "一二 三"
            val width1 = paint.measureText(text)
            try {
                paint.wordSpacing = 10f
                val width2 = paint.measureText(text)
                width2 - width1 == 10f
            } catch (e: NoSuchMethodError) {
                false
            } finally {
                PaintPool.recycle(paint)
            }
        }
    }

}
