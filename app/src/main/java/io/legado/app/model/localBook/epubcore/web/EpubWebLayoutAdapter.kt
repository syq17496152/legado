package io.legado.app.model.localBook.epubcore.web

import android.graphics.RectF
import android.net.Uri
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import io.legado.app.model.localBook.epubcore.layout.EpubCorePage
import io.legado.app.model.localBook.epubcore.layout.EpubImageFragment
import io.legado.app.model.localBook.epubcore.layout.EpubMeasuredGlyph
import io.legado.app.model.localBook.epubcore.layout.EpubMeasuredBoxFragment
import io.legado.app.model.localBook.epubcore.layout.EpubMeasuredTextFragment
import io.legado.app.model.localBook.epubcore.layout.EpubMeasuredTextKind
import io.legado.app.model.localBook.epubcore.layout.EpubPageFragment
import io.legado.app.model.localBook.epubcore.model.SourceRange
import io.legado.app.model.localBook.epubcore.style.EpubBackground
import io.legado.app.model.localBook.epubcore.style.EpubBackgroundPosition
import io.legado.app.model.localBook.epubcore.style.EpubBackgroundRepeat
import io.legado.app.model.localBook.epubcore.style.EpubBackgroundSize
import io.legado.app.model.localBook.epubcore.style.EpubBorderRadius
import io.legado.app.model.localBook.epubcore.style.EpubCss
import io.legado.app.model.localBook.epubcore.style.EpubSizeValue
import java.util.Locale

class EpubWebLayoutAdapter {

    fun toPages(document: EpubWebLayoutDocument): List<EpubCorePage> {
        val pageCount = document.pages.size.coerceAtLeast(1)
        return document.pages.map { page ->
            EpubCorePage(
                chapterIndex = document.chapterIndex,
                chapterHref = document.chapterHref,
                pageIndex = page.pageIndex,
                totalPagesInChapter = pageCount,
                text = page.text,
                fragments = page.fragments.mapNotNull { fragment ->
                    fragment.toPageFragment(document.chapterHref)
                },
                start = page.start.toSourceRange(document.chapterHref),
                end = page.end.toSourceRange(document.chapterHref)
            )
        }
    }

    private fun EpubWebLayoutFragment.toPageFragment(chapterHref: String): EpubPageFragment? {
        return when (this) {
            is EpubWebTextFragment -> {
                if (text.isBlank() || frame.isEmpty) return null
                val normalizedGlyphs = normalizeGlyphs()
                val normalizedFrame = normalizedGlyphs.normalizedFrame(frame)
                val normalizedWidth = normalizedGlyphs.normalizedWidth(frame)
                EpubMeasuredTextFragment(
                    text = text,
                    frame = normalizedFrame,
                    source = source.toSourceRange(chapterHref),
                    kind = kind.toMeasuredKind(),
                    baselinePx = baselinePx,
                    baselineSource = baselineSource,
                    fontSizePx = fontSizePx,
                    lineHeightPx = lineHeightPx,
                    letterSpacingPx = letterSpacingPx,
                    textScaleX = textScaleX,
                    rectWidthPx = normalizedWidth ?: rectWidthPx,
                    measuredTextWidthPx = normalizedWidth ?: measuredTextWidthPx,
                    lineId = lineId,
                    lineLeftPx = lineLeftPx,
                    lineRightPx = lineRightPx,
                    tagName = tagName,
                    fontFamily = fontFamily,
                    readerFontInherited = readerFontInherited,
                    direction = direction,
                    writingMode = writingMode,
                    textDecoration = textDecoration,
                    color = color,
                    bold = bold,
                    italic = italic,
                    opacity = opacity,
                    webAscentPx = webAscentPx,
                    webDescentPx = webDescentPx,
                    glyphs = normalizedGlyphs
                )
            }
            is EpubWebImageFragment -> {
                if (href.isBlank() || frame.isEmpty) return null
                EpubImageFragment(
                    href = resolveHref(chapterHref, href),
                    alt = alt,
                    frame = RectF(frame),
                    source = source.toSourceRange(chapterHref),
                    opacity = opacity,
                    borderRadius = borderRadiusPx.toRadius()
                )
            }
            is EpubWebBoxFragment -> {
                val background = toBackground(chapterHref)
                if (frame.isEmpty ||
                    backgroundColor == null &&
                    background.imageHref == null &&
                    (borderColor == null || borderWidthPx <= 0f)
                ) return null
                EpubMeasuredBoxFragment(
                    frame = RectF(frame),
                    source = source.toSourceRange(chapterHref),
                    backgroundColor = backgroundColor,
                    background = background,
                    borderColor = borderColor,
                    borderWidthPx = borderWidthPx,
                    borderRadius = borderRadiusPx.toRadius(),
                    opacity = opacity,
                    isPageBackground = isPageBackground
                )
            }
        }
    }

    private fun resolveHref(chapterHref: String, href: String): String {
        val raw = href.trim()
        if (raw.startsWith("data:", ignoreCase = true)) return raw
        epubLocalPath(raw)?.let { return it }
        val cleanHref = stripResourceDecorations(raw)
        if (cleanHref.isBlank()) {
            return EpubPath.normalize(stripResourceDecorations(chapterHref))
        }
        if (cleanHref.startsWith('/')) {
            return EpubPath.normalize(Uri.decode(cleanHref.trimStart('/')))
        }
        if (hasExternalScheme(cleanHref)) return cleanHref
        return EpubPath.resolve(stripResourceDecorations(chapterHref), cleanHref)
    }

    private fun EpubWebBoxFragment.toBackground(chapterHref: String): EpubBackground {
        val href = backgroundImageHref
            ?.takeIf { it.isNotBlank() }
            ?.let { resolveHref(chapterHref, it) }
        return EpubBackground(
            imageHref = href,
            repeat = backgroundRepeat.toBackgroundRepeat(),
            size = backgroundSize.toBackgroundSize(),
            position = backgroundPosition.toBackgroundPosition()
        )
    }

    private fun epubLocalPath(href: String): String? {
        val candidate = if (href.startsWith("//")) "https:$href" else href
        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        if (!uri.host.equals("epub.local", ignoreCase = true)) return null
        val path = uri.encodedPath
            ?.takeIf { it.isNotBlank() }
            ?.trimStart('/')
            ?: return null
        return EpubPath.normalize(Uri.decode(path))
    }

    private fun stripResourceDecorations(href: String): String {
        return href.substringBefore('#').substringBefore('?')
    }

    private fun hasExternalScheme(href: String): Boolean {
        val schemeEnd = href.indexOf(':')
        if (schemeEnd <= 0) return false
        return href.take(schemeEnd).all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
    }

    private fun String?.toBackgroundRepeat(): EpubBackgroundRepeat {
        val clean = firstCssLayer()
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?: return EpubBackgroundRepeat.Repeat
        return when (clean) {
            "no-repeat" -> EpubBackgroundRepeat.NoRepeat
            "repeat-x", "repeat no-repeat" -> EpubBackgroundRepeat.RepeatX
            "repeat-y", "no-repeat repeat" -> EpubBackgroundRepeat.RepeatY
            "repeat" -> EpubBackgroundRepeat.Repeat
            else -> EpubBackgroundRepeat.Repeat
        }
    }

    private fun String?.toBackgroundSize(): EpubBackgroundSize {
        val clean = firstCssLayer()
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?: return EpubBackgroundSize.Auto
        return when (clean) {
            "auto" -> EpubBackgroundSize.Auto
            "cover" -> EpubBackgroundSize.Cover
            "contain" -> EpubBackgroundSize.Contain
            else -> {
                val values = EpubCss.splitValueList(clean)
                val width = values.getOrNull(0).toSizeValue()
                val height = values.getOrNull(1).toSizeValue()
                if (width != EpubSizeValue.Auto || height != EpubSizeValue.Auto) {
                    EpubBackgroundSize.Explicit(width, height)
                } else {
                    EpubBackgroundSize.Auto
                }
            }
        }
    }

    private fun String?.toBackgroundPosition(): EpubBackgroundPosition {
        val clean = firstCssLayer()
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?: return EpubBackgroundPosition.Center
        val values = EpubCss.splitValueList(clean)
        if (values.isEmpty()) return EpubBackgroundPosition.Center
        fun axisPercent(token: String, isX: Boolean): Float? {
            return when (token) {
                "left", "top" -> 0f
                "center" -> 0.5f
                "right", "bottom" -> 1f
                else -> token.removeSuffix("%").toFloatOrNull()
                    ?.takeIf { token.endsWith("%") }
                    ?.let { it / 100f }
            }?.takeIf { isX || token !in setOf("left", "right") }
        }
        val first = values.getOrNull(0).orEmpty()
        val second = values.getOrNull(1).orEmpty()
        val x = axisPercent(first, true) ?: axisPercent(second, true) ?: 0.5f
        val y = axisPercent(second, false) ?: axisPercent(first, false) ?: 0.5f
        return EpubBackgroundPosition(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }

    private fun String?.toSizeValue(): EpubSizeValue {
        val clean = this?.trim()?.lowercase(Locale.ROOT) ?: return EpubSizeValue.Auto
        return when {
            clean == "auto" -> EpubSizeValue.Auto
            clean.endsWith("%") -> clean.dropLast(1).toFloatOrNull()
                ?.let { EpubSizeValue.Percent(it / 100f) }
                ?: EpubSizeValue.Auto
            clean.endsWith("px") -> clean.dropLast(2).toFloatOrNull()
                ?.let { EpubSizeValue.Px(it) }
                ?: EpubSizeValue.Auto
            else -> clean.toFloatOrNull()
                ?.let { EpubSizeValue.Px(it) }
                ?: EpubSizeValue.Auto
        }
    }

    private fun String?.firstCssLayer(): String? {
        return this?.substringBefore(',')?.takeIf { it.isNotBlank() }
    }

    private fun EpubWebAnchor?.toSourceRange(chapterHref: String): SourceRange? {
        this ?: return null
        return SourceRange(
            chapterHref = chapterHref,
            blockIndex = 0,
            startOffset = textOffset,
            endOffset = textOffset,
            nodePath = nodePath
        )
    }

    private fun Float.toRadius(): EpubBorderRadius {
        val radius = coerceAtLeast(0f)
        return EpubBorderRadius(radius, radius, radius, radius)
    }

    private fun EpubWebTextFragmentKind.toMeasuredKind(): EpubMeasuredTextKind {
        return when (this) {
            EpubWebTextFragmentKind.Ruby -> EpubMeasuredTextKind.Ruby
            EpubWebTextFragmentKind.RubyText -> EpubMeasuredTextKind.RubyText
            EpubWebTextFragmentKind.Superscript -> EpubMeasuredTextKind.Superscript
            EpubWebTextFragmentKind.Subscript -> EpubMeasuredTextKind.Subscript
            EpubWebTextFragmentKind.Text -> EpubMeasuredTextKind.Text
        }
    }

    private fun EpubWebTextFragment.normalizeGlyphs(): List<EpubMeasuredGlyph> {
        if (glyphs.isEmpty()) return emptyList()
        if (kind != EpubWebTextFragmentKind.Text) {
            return glyphs.map { it.toMeasuredGlyph() }
        }
        if (glyphs.hasValidAdvances()) {
            return glyphs.map { it.toMeasuredGlyph() }
        }
        val advance = normalizedCjkAdvance()
        if (!advance.isFinite() || advance <= 0f) return glyphs.map { it.toMeasuredGlyph() }
        val startX = glyphs.minOfOrNull { it.xPx } ?: frame.left
        var cursorX = startX
        return glyphs.map { glyph ->
            val measured = glyph.toMeasuredGlyph(
                xPx = cursorX,
                widthPx = advance
            )
            cursorX += advance
            measured
        }
    }

    private fun List<EpubWebGlyph>.hasValidAdvances(): Boolean {
        if (isEmpty()) return false
        return all { glyph ->
            glyph.xPx.isFinite() && glyph.widthPx.isFinite() && glyph.widthPx > 0f
        }
    }

    private fun EpubWebTextFragment.normalizedCjkAdvance(): Float {
        val size = fontSizePx
            ?.takeIf { it.isFinite() && it > 0f }
            ?: lineHeightPx
                ?.takeIf { it.isFinite() && it > 0f }
                ?.let { it * 0.72f }
            ?: glyphs
                .mapNotNull { it.heightPx.takeIf { height -> height.isFinite() && height > 0f } }
                .maxOrNull()
            ?: frame.height().takeIf { it.isFinite() && it > 0f }
            ?: 0f
        return (size + (letterSpacingPx ?: 0f)).coerceAtLeast(1f)
    }

    private fun List<EpubMeasuredGlyph>.normalizedWidth(originalFrame: RectF): Float? {
        if (isEmpty()) return null
        val left = minOfOrNull { it.xPx } ?: return null
        val right = maxOfOrNull { it.xPx + it.widthPx } ?: return null
        val width = right - left
        return width.takeIf { it.isFinite() && it > 0f } ?: originalFrame.width()
    }

    private fun List<EpubMeasuredGlyph>.normalizedFrame(originalFrame: RectF): RectF {
        if (isEmpty()) return RectF(originalFrame)
        val left = minOfOrNull { it.xPx } ?: return RectF(originalFrame)
        val right = maxOfOrNull { it.xPx + it.widthPx } ?: return RectF(originalFrame)
        if (!left.isFinite() || !right.isFinite() || right <= left) return RectF(originalFrame)
        return RectF(left, originalFrame.top, right, originalFrame.bottom)
    }

    private fun EpubWebGlyph.toMeasuredGlyph(
        xPx: Float = this.xPx,
        widthPx: Float = this.widthPx
    ): EpubMeasuredGlyph {
        return EpubMeasuredGlyph(
            text = text,
            xPx = xPx,
            yPx = yPx,
            widthPx = widthPx,
            heightPx = heightPx,
            baselinePx = baselinePx
        )
    }
}
