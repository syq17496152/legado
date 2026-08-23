package io.legado.app.model.localBook.epubcore.selector

import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import io.legado.app.model.localBook.epubcore.model.SourceAnchor

data class EpubSelectablePage(
    val pageKey: String,
    val blocks: List<EpubSelectableBlock>
)

data class EpubSelectableBlock(
    val id: String,
    val sourceAnchor: SourceAnchor?,
    val text: String,
    val rect: RectF,
    val lines: List<EpubSelectableLine>
)

data class EpubSelectableLine(
    val blockId: String,
    val textStart: Int,
    val textEnd: Int,
    val sourceStart: Int,
    val sourceEnd: Int,
    val lineIndex: Int,
    val rect: RectF,
    val textOriginX: Float,
    val baselinePx: Float,
    val layoutLineIndex: Int? = null,
    val layout: Layout? = null,
    val cells: List<EpubSelectableCell> = emptyList()
)

data class EpubSelectableCell(
    val textStart: Int,
    val textEnd: Int,
    val rect: RectF
)

data class EpubTextHit(
    val block: EpubSelectableBlock,
    val line: EpubSelectableLine,
    val textOffset: Int
)

data class EpubSelectionGeometry(
    val selectedText: String,
    val rects: List<RectF>,
    val paths: List<Path> = emptyList(),
    val anchorStartX: Float,
    val anchorTopY: Float,
    val anchorEndX: Float,
    val anchorBottomY: Float,
    val anchorStartBottomY: Float,
    val anchorEndBottomY: Float
)
