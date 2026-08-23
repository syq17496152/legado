package io.legado.app.model.localBook.epubcore.web

import android.graphics.RectF

data class EpubWebLayoutRequest(
    val chapterIndex: Int,
    val chapterHref: String,
    val title: String?,
    val html: String,
    val startFragmentId: String? = null,
    val endFragmentId: String? = null,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val fontSizePx: Float,
    val textColor: Int,
    val lineHeightPx: Float,
    val readerPaddingLeftPx: Int = 0,
    val readerPaddingTopPx: Int = 0,
    val readerPaddingRightPx: Int = 0,
    val readerPaddingBottomPx: Int = 0,
    val readerFontFamily: String? = null,
    val readerFontUrl: String? = null,
    val readerFontPath: String? = null,
    val letterSpacingEm: Float = 0f,
    val textFullJustify: Boolean = false,
    val timeoutMillis: Long = 12_000L
)

data class EpubWebLayoutDocument(
    val protocolVersion: Int,
    val chapterIndex: Int,
    val chapterHref: String,
    val title: String?,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val pageCount: Int,
    val warnings: List<String>,
    val pages: List<EpubWebLayoutPage>
)

data class EpubWebLayoutSnapshot(
    val document: EpubWebLayoutDocument,
    val rawJson: String
)

data class EpubWebLayoutPage(
    val pageIndex: Int,
    val text: String,
    val start: EpubWebAnchor?,
    val end: EpubWebAnchor?,
    val columnOffsetPx: Float,
    val fragments: List<EpubWebLayoutFragment>
)

sealed interface EpubWebLayoutFragment {
    val frame: RectF
    val source: EpubWebAnchor?
}

data class EpubWebTextFragment(
    val text: String,
    override val frame: RectF,
    override val source: EpubWebAnchor?,
    val baselinePx: Float?,
    val fontSizePx: Float?,
    val lineHeightPx: Float?,
    val letterSpacingPx: Float?,
    val textScaleX: Float?,
    val tagName: String?,
    val fontFamily: String?,
    val direction: String?,
    val writingMode: String?,
    val textDecoration: String?,
    val color: Int?,
    val bold: Boolean,
    val italic: Boolean,
    val opacity: Float,
    val readerFontInherited: Boolean = false,
    val kind: EpubWebTextFragmentKind = EpubWebTextFragmentKind.Text,
    val baselineSource: String? = null,
    val webAscentPx: Float? = null,
    val webDescentPx: Float? = null,
    val rectWidthPx: Float? = null,
    val measuredTextWidthPx: Float? = null,
    val lineId: String? = null,
    val lineLeftPx: Float? = null,
    val lineRightPx: Float? = null,
    val glyphs: List<EpubWebGlyph> = emptyList()
) : EpubWebLayoutFragment

data class EpubWebGlyph(
    val text: String,
    val xPx: Float,
    val yPx: Float,
    val widthPx: Float,
    val heightPx: Float,
    val baselinePx: Float? = null
)

enum class EpubWebTextFragmentKind {
    Text,
    Ruby,
    RubyText,
    Superscript,
    Subscript
}

data class EpubWebImageFragment(
    val href: String,
    val alt: String?,
    override val frame: RectF,
    override val source: EpubWebAnchor?,
    val opacity: Float,
    val borderRadiusPx: Float
) : EpubWebLayoutFragment

data class EpubWebBoxFragment(
    override val frame: RectF,
    override val source: EpubWebAnchor?,
    val backgroundColor: Int?,
    val backgroundImageHref: String?,
    val backgroundRepeat: String?,
    val backgroundSize: String?,
    val backgroundPosition: String?,
    val borderColor: Int?,
    val borderWidthPx: Float,
    val borderRadiusPx: Float,
    val opacity: Float,
    val isPageBackground: Boolean = false
) : EpubWebLayoutFragment

data class EpubWebAnchor(
    val nodePath: String,
    val textOffset: Int
)
