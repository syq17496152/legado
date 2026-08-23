package io.legado.app.model.localBook.epubcore.layout

import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import io.legado.app.model.localBook.epubcore.model.SourceRange
import io.legado.app.model.localBook.epubcore.style.EpubBackground
import io.legado.app.model.localBook.epubcore.style.EpubBorderRadius

data class EpubCorePage(
    val chapterIndex: Int,
    val chapterHref: String,
    val pageIndex: Int,
    val totalPagesInChapter: Int,
    val text: CharSequence = "",
    val fragments: List<EpubPageFragment> = emptyList(),
    val start: SourceRange?,
    val end: SourceRange?
) {
    val paintFragments: List<EpubPageFragment>
        get() = fragments.ifEmpty {
            listOf(
                EpubTextFragment(
                    text = text,
                    frame = RectF(),
                    source = start
                )
            )
        }
}

fun EpubCorePage.pageKey(): String {
    return "$chapterIndex:$chapterHref:$pageIndex"
}

sealed interface EpubPageFragment {
    val frame: RectF
    val source: SourceRange?
}

data class EpubTextAnchor(
    val source: SourceRange,
    val startOffset: Int,
    val endOffset: Int
)

data class EpubTextFragment(
    val text: CharSequence,
    val staticLayout: StaticLayout? = null,
    val anchors: List<EpubTextAnchor> = emptyList(),
    val lineBoxes: List<EpubTextLineBox> = emptyList(),
    override val frame: RectF,
    override val source: SourceRange?,
    val alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    val lineSpacingMultiplier: Float = 1f,
    val lineSpacingExtraPx: Float = 0f,
    val startLine: Int = 0,
    val endLineExclusive: Int = Int.MAX_VALUE,
    val lineTopOffsetPx: Float = 0f
) : EpubPageFragment

data class EpubTextLineBox(
    val lineIndex: Int,
    val textStart: Int,
    val textEnd: Int,
    val rect: RectF,
    val baselinePx: Float
)

data class EpubMeasuredTextFragment(
    val text: CharSequence,
    override val frame: RectF,
    override val source: SourceRange?,
    val kind: EpubMeasuredTextKind = EpubMeasuredTextKind.Text,
    val baselinePx: Float? = null,
    val baselineSource: String? = null,
    val fontSizePx: Float? = null,
    val lineHeightPx: Float? = null,
    val letterSpacingPx: Float? = null,
    val textScaleX: Float? = null,
    val rectWidthPx: Float? = null,
    val measuredTextWidthPx: Float? = null,
    val lineId: String? = null,
    val lineLeftPx: Float? = null,
    val lineRightPx: Float? = null,
    val tagName: String? = null,
    val fontFamily: String? = null,
    val readerFontInherited: Boolean = false,
    val direction: String? = null,
    val writingMode: String? = null,
    val textDecoration: String? = null,
    val color: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val opacity: Float = 1f,
    val webAscentPx: Float? = null,
    val webDescentPx: Float? = null,
    val glyphs: List<EpubMeasuredGlyph> = emptyList()
) : EpubPageFragment

data class EpubMeasuredGlyph(
    val text: String,
    val xPx: Float,
    val yPx: Float,
    val widthPx: Float,
    val heightPx: Float,
    val baselinePx: Float? = null
)

enum class EpubMeasuredTextKind {
    Text,
    Ruby,
    RubyText,
    Superscript,
    Subscript
}

data class EpubImageFragment(
    val href: String,
    val alt: String?,
    override val frame: RectF,
    override val source: SourceRange?,
    val opacity: Float = 1f,
    val borderRadius: EpubBorderRadius = EpubBorderRadius.Zero
) : EpubPageFragment

data class EpubContainerFragment(
    override val frame: RectF,
    override val source: SourceRange?,
    val children: List<EpubPageFragment>,
    val backgroundColor: Int? = null,
    val background: EpubBackground = EpubBackground.None,
    val borderColor: Int? = null,
    val borderWidthPx: Float = 0f,
    val borderRadius: EpubBorderRadius = EpubBorderRadius.Zero,
    val opacity: Float = 1f
) : EpubPageFragment

data class EpubMeasuredBoxFragment(
    override val frame: RectF,
    override val source: SourceRange?,
    val backgroundColor: Int? = null,
    val background: EpubBackground = EpubBackground.None,
    val borderColor: Int? = null,
    val borderWidthPx: Float = 0f,
    val borderRadius: EpubBorderRadius = EpubBorderRadius.Zero,
    val opacity: Float = 1f,
    val isPageBackground: Boolean = false
) : EpubPageFragment

data class EpubTableFragment(
    override val frame: RectF,
    override val source: SourceRange?,
    val children: List<EpubPageFragment>
) : EpubPageFragment

data class EpubFlexFragment(
    override val frame: RectF,
    override val source: SourceRange?,
    val children: List<EpubPageFragment>
) : EpubPageFragment

data class EpubWebFragment(
    override val frame: RectF,
    override val source: SourceRange?,
    val fallbackText: CharSequence? = null
) : EpubPageFragment
