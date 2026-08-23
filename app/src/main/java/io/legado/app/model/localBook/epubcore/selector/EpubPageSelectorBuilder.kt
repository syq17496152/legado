package io.legado.app.model.localBook.epubcore.selector

import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import io.legado.app.model.localBook.epubcore.layout.EpubContainerFragment
import io.legado.app.model.localBook.epubcore.layout.EpubCorePage
import io.legado.app.model.localBook.epubcore.layout.EpubFlexFragment
import io.legado.app.model.localBook.epubcore.layout.EpubMeasuredTextKind
import io.legado.app.model.localBook.epubcore.layout.EpubMeasuredTextFragment
import io.legado.app.model.localBook.epubcore.layout.EpubPageFragment
import io.legado.app.model.localBook.epubcore.layout.EpubTableFragment
import io.legado.app.model.localBook.epubcore.layout.EpubTextFragment
import io.legado.app.model.localBook.epubcore.layout.pageKey
import kotlin.math.max
import kotlin.math.min

object EpubPageSelectorBuilder {

    fun build(page: EpubCorePage): EpubSelectablePage {
        val fragments = mutableListOf<AbsoluteTextFragment>()
        collectTextFragments(page.paintFragments, 0f, 0f, fragments)
        val blocks = fragments
            .groupBy { it.nodePath }
            .toList()
            .sortedWith(compareBy<Pair<String, List<AbsoluteTextFragment>>> { pair ->
                pair.second.minOfOrNull { it.nodeOrder } ?: Int.MAX_VALUE
            }.thenBy { it.first })
            .map { it.second }
            .mapIndexedNotNull { index, group -> buildBlock(index, group) }
        return EpubSelectablePage(page.pageKey(), blocks)
    }

    private fun buildBlock(index: Int, fragments: List<AbsoluteTextFragment>): EpubSelectableBlock? {
        if (fragments.isEmpty()) return null
        val textBuilder = StringBuilder()
        val lines = mutableListOf<EpubSelectableLine>()
        val rect = RectF()
        val blockId = fragments.first().blockKey.ifBlank { "block:$index" }
        var cursor = 0
        fragments.forEach { item ->
            if (item.text.isBlank()) return@forEach
            rect.union(item.frame)
            val fragmentTextStart = cursor
            textBuilder.append(item.text)
            cursor += item.text.length
            lines += item.toLines(blockId, fragmentTextStart)
        }
        val text = textBuilder.toString()
        if (text.isBlank() || lines.isEmpty()) return null
        return EpubSelectableBlock(
            id = blockId,
            sourceAnchor = fragments.firstNotNullOfOrNull { it.sourceAnchor },
            text = text,
            rect = rect,
            lines = lines.sortedWith(compareBy<EpubSelectableLine> { it.rect.top }.thenBy { it.rect.left })
        )
    }

    private fun AbsoluteTextFragment.toLines(blockId: String, fragmentTextStart: Int): List<EpubSelectableLine> {
        val textFragment = fragment as? EpubTextFragment
        val layout = textFragment?.staticLayout
        if (textFragment != null && layout != null) {
            val startLine = textFragment.startLine.coerceIn(0, layout.lineCount)
            val endLine = textFragment.endLineExclusive.coerceIn(startLine, layout.lineCount)
            return (startLine until endLine).mapNotNull { lineIndex ->
                val sourceStart = layout.getLineStart(lineIndex).coerceIn(0, layout.text.length)
                val sourceEnd = layout.getLineEnd(lineIndex).coerceIn(sourceStart, layout.text.length)
                val lineText = layout.text.subSequence(sourceStart, sourceEnd).toString()
                if (lineText.isBlank()) return@mapNotNull null
                val localStart = fragmentTextStart + (sourceStart - sourceOffset).coerceAtLeast(0)
                val localEnd = (localStart + lineText.length).coerceAtMost(fragmentTextStart + text.length)
                val top = frame.top - textFragment.lineTopOffsetPx + layout.getLineTop(lineIndex)
                val bottom = frame.top - textFragment.lineTopOffsetPx + layout.getLineBottom(lineIndex)
                val left = frame.left + layout.getLineLeft(lineIndex)
                val right = frame.left + layout.getLineRight(lineIndex)
                EpubSelectableLine(
                    blockId = blockId,
                    textStart = localStart,
                    textEnd = localEnd,
                    sourceStart = sourceStart,
                    sourceEnd = sourceEnd,
                    lineIndex = lineIndex - startLine,
                    rect = RectF(left, top, right.coerceAtLeast(left + 1f), bottom.coerceAtLeast(top + 1f)),
                    textOriginX = frame.left,
                    baselinePx = frame.top - textFragment.lineTopOffsetPx + layout.getLineBaseline(lineIndex),
                    layoutLineIndex = lineIndex,
                    layout = layout
                )
            }
        }
        if (textFragment != null && layout != null && textFragment.lineBoxes.isNotEmpty()) {
            return textFragment.lineBoxes.mapNotNull { lineBox ->
                val sourceStart = lineBox.textStart.coerceIn(0, layout.text.length)
                val sourceEnd = lineBox.textEnd.coerceIn(sourceStart, layout.text.length)
                val lineText = layout.text.subSequence(sourceStart, sourceEnd).toString()
                if (lineText.isBlank()) return@mapNotNull null
                val localStart = fragmentTextStart + (sourceStart - sourceOffset).coerceAtLeast(0)
                val localEnd = (localStart + lineText.length).coerceAtMost(fragmentTextStart + text.length)
                val rect = RectF(lineBox.rect)
                rect.offset(frame.left, frame.top)
                EpubSelectableLine(
                    blockId = blockId,
                    textStart = localStart,
                    textEnd = localEnd,
                    sourceStart = sourceStart,
                    sourceEnd = sourceEnd,
                    lineIndex = lineBox.lineIndex,
                    rect = rect,
                    textOriginX = frame.left,
                    baselinePx = frame.top + lineBox.baselinePx,
                    layoutLineIndex = lineBox.lineIndex,
                    layout = layout
                )
            }
        }
        return listOf(
            EpubSelectableLine(
                blockId = blockId,
                textStart = fragmentTextStart,
                textEnd = fragmentTextStart + text.length,
                sourceStart = sourceOffset,
                sourceEnd = sourceOffset + text.length,
                lineIndex = 0,
                rect = RectF(frame),
                textOriginX = frame.left,
                    baselinePx = frame.bottom,
                    layoutLineIndex = null,
                    layout = null,
                    cells = measuredCells(fragmentTextStart)
            )
        )
    }

    private fun collectTextFragments(
        fragments: List<EpubPageFragment>,
        offsetX: Float,
        offsetY: Float,
        out: MutableList<AbsoluteTextFragment>
    ) {
        fragments.forEach { fragment ->
            when (fragment) {
                is EpubTextFragment -> {
                    val text = fragment.visibleText()
                    val frame = fragment.frame.offsetBy(offsetX, offsetY)
                    if (text.isSelectableText() && frame.isSelectableFrame()) {
                        out += AbsoluteTextFragment(
                            fragment = fragment,
                            text = text,
                            frame = frame,
                            nodePath = fragment.source?.start?.nodePath ?: "text:${out.size}",
                            blockKey = fragment.source?.start?.nodePath ?: "text:${out.size}",
                            sourceAnchor = fragment.source?.start,
                            sourceOffset = fragment.startLineOffset(),
                            nodeOrder = fragment.source?.start?.blockIndex ?: out.size
                        )
                    }
                }
                is EpubMeasuredTextFragment -> {
                    val text = fragment.text.toString()
                    val frame = fragment.frame.offsetBy(offsetX, offsetY)
                    if (fragment.isSelectableMeasuredText() && text.isSelectableText() && frame.isSelectableFrame()) {
                        out += AbsoluteTextFragment(
                            fragment = fragment,
                            text = text,
                            frame = frame,
                            nodePath = fragment.source?.start?.nodePath ?: "measured:${out.size}",
                            blockKey = fragment.source?.start?.nodePath ?: "measured:${out.size}",
                            sourceAnchor = fragment.source?.start,
                            sourceOffset = fragment.source?.startOffset ?: 0,
                            nodeOrder = fragment.source?.start?.blockIndex ?: out.size
                        )
                    }
                }
                is EpubContainerFragment -> collectTextFragments(fragment.children, offsetX + fragment.frame.left, offsetY + fragment.frame.top, out)
                is EpubTableFragment -> collectTextFragments(fragment.children, offsetX + fragment.frame.left, offsetY + fragment.frame.top, out)
                is EpubFlexFragment -> collectTextFragments(fragment.children, offsetX + fragment.frame.left, offsetY + fragment.frame.top, out)
                else -> Unit
            }
        }
    }

    fun hitTest(page: EpubSelectablePage, x: Float, y: Float, strict: Boolean, maxDistance: Float? = null): EpubTextHit? {
        val candidates = page.blocks.flatMap { block -> block.lines.map { block to it } }
        val hit = candidates.firstOrNull { (_, line) -> line.rect.contains(x, y) }
            ?: if (strict) null else candidates.minByOrNull { (_, line) ->
                val cx = x.coerceIn(line.rect.left, line.rect.right)
                val cy = y.coerceIn(line.rect.top, line.rect.bottom)
                val dx = x - cx
                val dy = y - cy
                dx * dx + dy * dy
            }?.takeIf { (_, line) ->
                val limit = maxDistance ?: return@takeIf true
                val cx = x.coerceIn(line.rect.left, line.rect.right)
                val cy = y.coerceIn(line.rect.top, line.rect.bottom)
                val dx = x - cx
                val dy = y - cy
                dx * dx + dy * dy <= limit * limit
            }
        val pair = hit ?: return null
        val offset = offsetForLine(pair.first, pair.second, x)
        return EpubTextHit(pair.first, pair.second, offset)
    }

    fun selectionGeometry(
        page: EpubSelectablePage,
        startBlock: EpubSelectableBlock,
        startOffset: Int,
        endBlock: EpubSelectableBlock,
        endOffset: Int,
        offsetX: Float,
        offsetY: Float,
        leftLimit: Float,
        rightLimit: Float
    ): EpubSelectionGeometry {
        val orderedBlocks = page.blocks
        val startIndex = orderedBlocks.indexOf(startBlock)
        val endIndex = orderedBlocks.indexOf(endBlock)
        val forward = startIndex < endIndex || startIndex == endIndex && startOffset <= endOffset
        val firstIndex = if (forward) startIndex else endIndex
        val lastIndex = if (forward) endIndex else startIndex
        val firstOffset = if (forward) startOffset else endOffset
        val lastOffset = if (forward) endOffset else startOffset
        val rects = mutableListOf<RectF>()
        val paths = mutableListOf<Path>()
        val text = StringBuilder()
        var anchorStart: RectF? = null
        var anchorEnd: RectF? = null
        for (blockIndex in firstIndex..lastIndex) {
            val block = orderedBlocks.getOrNull(blockIndex) ?: continue
            val from = if (blockIndex == firstIndex) firstOffset.coerceIn(0, block.text.length) else 0
            val to = if (blockIndex == lastIndex) lastOffset.coerceIn(from, block.text.length) else block.text.length
            if (to <= from) continue
            if (text.isNotEmpty()) text.append('\n')
            text.append(block.text.substring(from, to))
            block.lines.forEach { line ->
                val lineFrom = max(from, line.textStart)
                val lineTo = min(to, line.textEnd)
                if (lineTo <= lineFrom) return@forEach
                val startX = xForOffset(block, line, lineFrom).coerceIn(leftLimit, rightLimit)
                val endX = xForOffset(block, line, lineTo).coerceIn(leftLimit, rightLimit)
                val rect = RectF(
                    min(startX, endX) + offsetX,
                    line.rect.top + offsetY,
                    max(startX, endX) + offsetX,
                    line.rect.bottom + offsetY
                )
                if (rect.width() > 0f && rect.height() > 0f) {
                    rects += rect
                    paths += Path().apply {
                        addRect(rect, Path.Direction.CW)
                    }
                    if (anchorStart == null) anchorStart = rect
                    anchorEnd = rect
                }
            }
        }
        val firstRect = anchorStart ?: RectF()
        val lastRect = anchorEnd ?: firstRect
        return EpubSelectionGeometry(
            selectedText = text.toString(),
            rects = rects,
            paths = paths,
            anchorStartX = firstRect.left,
            anchorTopY = firstRect.top,
            anchorEndX = lastRect.right,
            anchorBottomY = lastRect.bottom,
            anchorStartBottomY = firstRect.bottom,
            anchorEndBottomY = lastRect.bottom
        )
    }

    private fun offsetForLine(block: EpubSelectableBlock, line: EpubSelectableLine, x: Float): Int {
        if (line.textEnd <= line.textStart) return line.textStart
        line.cells.takeIf { it.isNotEmpty() }?.let { cells ->
            val hit = cells.firstOrNull { x < it.rect.right } ?: cells.last()
            val midpoint = (hit.rect.left + hit.rect.right) / 2f
            return if (x < midpoint) hit.textStart else hit.textEnd
        }
        val layout = line.layout
        val layoutLineIndex = line.layoutLineIndex
        if (layout != null && layoutLineIndex != null) {
            val sourceOffset = layout.getOffsetForHorizontal(
                layoutLineIndex,
                x - line.textOriginX
            ).coerceIn(line.sourceStart, line.sourceEnd)
            return (line.textStart + sourceOffset - line.sourceStart).coerceIn(line.textStart, line.textEnd)
        }
        val width = line.rect.width().coerceAtLeast(1f)
        val ratio = ((x - line.rect.left) / width).coerceIn(0f, 1f)
        val length = line.textEnd - line.textStart
        return (line.textStart + (length * ratio).toInt()).coerceIn(line.textStart, line.textEnd)
    }

    private fun xForOffset(block: EpubSelectableBlock, line: EpubSelectableLine, offset: Int): Float {
        if (line.textEnd <= line.textStart) return line.rect.left
        line.cells.takeIf { it.isNotEmpty() }?.let { cells ->
            val bounded = offset.coerceIn(line.textStart, line.textEnd)
            if (bounded <= line.textStart) return cells.first().rect.left
            if (bounded >= line.textEnd) return cells.last().rect.right
            val nextCell = cells.firstOrNull { bounded <= it.textStart }
            if (nextCell != null) return nextCell.rect.left
            val currentCell = cells.firstOrNull { bounded > it.textStart && bounded <= it.textEnd }
            if (currentCell != null) return currentCell.rect.right
            return cells.last().rect.right
        }
        val layout = line.layout
        val layoutLineIndex = line.layoutLineIndex
        if (layout != null && layoutLineIndex != null) {
            val sourceOffset = (line.sourceStart + offset - line.textStart).coerceIn(line.sourceStart, line.sourceEnd)
            return line.textOriginX + layout.getPrimaryHorizontal(sourceOffset)
        }
        val length = line.textEnd - line.textStart
        val ratio = (offset - line.textStart).toFloat().coerceIn(0f, length.toFloat()) / length.toFloat()
        return line.rect.left + line.rect.width() * ratio
    }

    private data class AbsoluteTextFragment(
        val fragment: EpubPageFragment,
        val text: String,
        val frame: RectF,
        val nodePath: String,
        val blockKey: String,
        val sourceAnchor: io.legado.app.model.localBook.epubcore.model.SourceAnchor?,
        val sourceOffset: Int,
        val nodeOrder: Int
    )

    private fun AbsoluteTextFragment.measuredCells(fragmentTextStart: Int): List<EpubSelectableCell> {
        val measured = fragment as? EpubMeasuredTextFragment ?: return emptyList()
        if (measured.glyphs.isEmpty()) return emptyList()
        var cursor = fragmentTextStart
        return measured.glyphs.mapNotNull { glyph ->
            val textLength = glyph.text.length
            if (textLength <= 0) return@mapNotNull null
            val left = frame.left + (glyph.xPx - measured.frame.left)
            val top = frame.top + (glyph.yPx - measured.frame.top)
            val right = left + glyph.widthPx.coerceAtLeast(1f)
            val bottom = top + glyph.heightPx.coerceAtLeast(frame.height())
            val cell = EpubSelectableCell(
                textStart = cursor,
                textEnd = cursor + textLength,
                rect = RectF(left, top, right, bottom)
            )
            cursor += textLength
            cell
        }
    }

    private fun RectF.offsetBy(x: Float, y: Float): RectF = RectF(this).apply { offset(x, y) }

    private fun String.isSelectableText(): Boolean {
        return any { !it.isWhitespace() && it != '\u200B' && it != '\uFEFF' }
    }

    private fun RectF.isSelectableFrame(): Boolean {
        return left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            width() >= 1f && height() >= 1f
    }

    private fun EpubMeasuredTextFragment.isSelectableMeasuredText(): Boolean {
        if (kind != EpubMeasuredTextKind.Text) return false
        if (opacity <= 0.05f) return false
        val tag = tagName.orEmpty().lowercase()
        if (tag in setOf("ruby", "rt", "rp", "sup", "sub")) return false
        val path = source?.start?.nodePath.orEmpty().lowercase()
        return listOf("/ruby", "/rt", "/rp", "/sup", "/sub").none { it in path }
    }

    private fun EpubTextFragment.startLineOffset(): Int {
        val layout = staticLayout ?: return 0
        return if (startLine in 0 until layout.lineCount) {
            layout.getLineStart(startLine)
        } else {
            0
        }
    }

    private fun EpubTextFragment.visibleText(): String {
        val layout = staticLayout ?: return text.toString()
        if (layout.lineCount <= 0) return ""
        val startLine = startLine.coerceIn(0, layout.lineCount)
        val endLine = endLineExclusive.coerceIn(startLine, layout.lineCount)
        if (endLine <= startLine) return ""
        val start = layout.getLineStart(startLine)
        val end = layout.getLineEnd(endLine - 1)
        return layout.text.subSequence(start, end).toString()
    }
}
