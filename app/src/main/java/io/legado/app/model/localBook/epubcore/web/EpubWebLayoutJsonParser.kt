package io.legado.app.model.localBook.epubcore.web

import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject

object EpubWebLayoutJsonParser {

    fun parseJavascriptResult(
        request: EpubWebLayoutRequest,
        raw: String?
    ): EpubWebLayoutDocument? {
        val json = decodeJavascriptString(raw) ?: return null
        return parseJson(json, request)
    }

    fun parseJson(
        json: String?,
        request: EpubWebLayoutRequest? = null
    ): EpubWebLayoutDocument? {
        val root = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
        root.optString("error").takeIf { it.isNotBlank() }?.let {
            return null
        }
        val pagesJson = root.optJSONArray("pages") ?: return null
        if (!hasValidBackgroundProtocol(pagesJson)) return null
        val warnings = root.optJSONArray("warnings").toStringList()
        val pages = ArrayList<EpubWebLayoutPage>(pagesJson.length())
        for (index in 0 until pagesJson.length()) {
            val pageJson = pagesJson.optJSONObject(index) ?: continue
            val fragments = parseFragments(pageJson.optJSONArray("fragments") ?: JSONArray())
            val pageIndex = pageJson.optInt("pageIndex", index)
            pages += EpubWebLayoutPage(
                pageIndex = pageIndex,
                text = pageJson.optString("text"),
                start = pageJson.optJSONObject("start").toAnchor(),
                end = pageJson.optJSONObject("end").toAnchor(),
                columnOffsetPx = pageJson.optDouble(
                    "columnOffsetPx",
                    pageIndex.toDouble() * (request?.viewportWidthPx?.toDouble() ?: 0.0)
                ).toFloat(),
                fragments = fragments
            )
        }
        if (pages.isEmpty()) return null
        val fragmentCount = pages.sumOf { it.fragments.size }
        if (fragmentCount == 0 && request != null) {
            return EpubWebLayoutDocument(
                protocolVersion = root.optInt("protocolVersion", 1),
                chapterIndex = request.chapterIndex,
                chapterHref = request.chapterHref,
                title = request.title,
                viewportWidthPx = request.viewportWidthPx,
                viewportHeightPx = request.viewportHeightPx,
                pageCount = root.optInt("pageCount", pages.size).coerceAtLeast(pages.size),
                warnings = warnings,
                pages = pages
            )
        }
        return EpubWebLayoutDocument(
            protocolVersion = root.optInt("protocolVersion", 1),
            chapterIndex = root.optInt("chapterIndex", request?.chapterIndex ?: 0),
            chapterHref = root.optString("chapterHref", request?.chapterHref.orEmpty()),
            title = root.optString("title", request?.title),
            viewportWidthPx = root.optInt("viewportWidthPx", request?.viewportWidthPx ?: 0),
            viewportHeightPx = root.optInt("viewportHeightPx", request?.viewportHeightPx ?: 0),
            pageCount = root.optInt("pageCount", pages.size).coerceAtLeast(pages.size),
            warnings = warnings,
            pages = pages
        )
    }

    private fun decodeJavascriptString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching { JSONArray("[$raw]").getString(0) }
            .getOrElse { raw.trim('"') }
            .takeIf { it.isNotBlank() && it != "null" }
    }

    private fun hasValidBackgroundProtocol(pagesJson: JSONArray): Boolean {
        for (pageIndex in 0 until pagesJson.length()) {
            val fragments = pagesJson.optJSONObject(pageIndex)?.optJSONArray("fragments") ?: continue
            if (!hasValidBackgroundProtocolInFragments(fragments)) return false
        }
        return true
    }

    private fun hasValidBackgroundProtocolInFragments(fragments: JSONArray): Boolean {
        for (index in 0 until fragments.length()) {
            val item = fragments.optJSONObject(index) ?: continue
            val backgroundImage = item.optString("backgroundImageHref").takeIf { it.isNotBlank() }
                ?: item.optString("backgroundImage").takeIf { it.isNotBlank() }
            if (backgroundImage != null &&
                (!item.has("backgroundSize") || !item.has("backgroundRepeat"))
            ) {
                return false
            }
            val children = item.optJSONArray("children") ?: continue
            if (!hasValidBackgroundProtocolInFragments(children)) return false
        }
        return true
    }

    private fun parseFragments(array: JSONArray): List<EpubWebLayoutFragment> {
        val result = ArrayList<EpubWebLayoutFragment>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val rect = item.optJSONObject("rect")
                ?: item.optJSONObject("frame")
                ?: item.optJSONObject("bounds")
                ?: continue
            val frame = rect.toRectF() ?: continue
            val source = item.optJSONObject("source").toAnchor()
            when (item.optString("type").takeIf { it.isNotBlank() } ?: inferFragmentType(item)) {
                "text" -> {
                    val text = item.optString("text").takeIf { it.isNotBlank() } ?: continue
                    result += EpubWebTextFragment(
                        text = text,
                        frame = frame,
                        source = source,
                        kind = item.optTextFragmentKind("kind"),
                        baselinePx = item.optNullableFloat("baseline") ?: item.optNullableFloat("baselinePx"),
                        baselineSource = item.optString("baselineSource").takeIf { it.isNotBlank() },
                        fontSizePx = item.optNullableFloat("fontSize") ?: item.optNullableFloat("fontSizePx"),
                        lineHeightPx = item.optNullableFloat("lineHeight") ?: item.optNullableFloat("lineHeightPx"),
                        letterSpacingPx = item.optNullableFloat("letterSpacing") ?: item.optNullableFloat("letterSpacingPx"),
                        textScaleX = item.optNullableFloat("textScaleX"),
                        rectWidthPx = item.optNullableFloat("rectWidth") ?: item.optNullableFloat("rectWidthPx"),
                        measuredTextWidthPx = item.optNullableFloat("measuredTextWidth") ?: item.optNullableFloat("measuredTextWidthPx"),
                        tagName = item.optString("tagName").takeIf { it.isNotBlank() },
                        fontFamily = item.optString("fontFamily").takeIf { it.isNotBlank() },
                        readerFontInherited = item.optBoolean("readerFontInherited", false),
                        direction = item.optString("direction").takeIf { it.isNotBlank() },
                        writingMode = item.optString("writingMode").takeIf { it.isNotBlank() },
                        textDecoration = item.optString("textDecoration").takeIf { it.isNotBlank() },
                        color = item.optNullableColor("color"),
                        bold = item.optBoolean("bold", false),
                        italic = item.optBoolean("italic", false),
                        opacity = item.optFloat("opacity", 1f),
                        webAscentPx = item.optNullableFloat("webAscent") ?: item.optNullableFloat("webAscentPx"),
                        webDescentPx = item.optNullableFloat("webDescent") ?: item.optNullableFloat("webDescentPx"),
                        lineId = item.optString("lineId").takeIf { it.isNotBlank() },
                        lineLeftPx = item.optNullableFloat("lineLeft") ?: item.optNullableFloat("lineLeftPx"),
                        lineRightPx = item.optNullableFloat("lineRight") ?: item.optNullableFloat("lineRightPx"),
                        glyphs = item.optJSONArray("glyphs").toGlyphs()
                    )
                }
                "image" -> {
                    result += EpubWebImageFragment(
                        href = item.optString("href"),
                        alt = item.optString("alt").takeIf { it.isNotBlank() },
                        frame = frame,
                        source = source,
                        opacity = item.optFloat("opacity", 1f),
                        borderRadiusPx = item.optFloat("borderRadius", 0f)
                    )
                }
                "box" -> {
                    result += EpubWebBoxFragment(
                        frame = frame,
                        source = source,
                        backgroundColor = item.optNullableColor("backgroundColor"),
                        backgroundImageHref = item.optString("backgroundImageHref")
                            .takeIf { it.isNotBlank() }
                            ?: item.optString("backgroundImage").takeIf { it.isNotBlank() },
                        backgroundRepeat = item.optString("backgroundRepeat").takeIf { it.isNotBlank() },
                        backgroundSize = item.optString("backgroundSize").takeIf { it.isNotBlank() },
                        backgroundPosition = item.optString("backgroundPosition").takeIf { it.isNotBlank() },
                        borderColor = item.optNullableColor("borderColor"),
                        borderWidthPx = item.optFloat("borderWidthPx", item.optFloat("borderWidth", 0f)),
                        borderRadiusPx = item.optFloat("borderRadiusPx", item.optFloat("borderRadius", 0f)),
                        opacity = item.optFloat("opacity", 1f),
                        isPageBackground = item.optBoolean("pageBackground", false)
                    )
                }
            }
        }
        return result
    }

    private fun inferFragmentType(item: JSONObject): String {
        return when {
            item.has("backgroundColor") ||
                item.has("backgroundImageHref") ||
                item.has("backgroundImage") ||
                item.has("backgroundRepeat") ||
                item.has("backgroundSize") ||
                item.has("backgroundPosition") ||
                item.has("borderColor") ||
                item.has("borderWidth") ||
                item.has("borderWidthPx") ||
                item.optBoolean("pageBackground", false) -> "box"
            item.has("href") -> "image"
            item.has("text") -> "text"
            else -> "text"
        }
    }

    private fun JSONObject?.toAnchor(): EpubWebAnchor? {
        this ?: return null
        val path = optString("nodePath").takeIf { it.isNotBlank() } ?: return null
        return EpubWebAnchor(path, optInt("textOffset", 0))
    }

    private fun JSONObject?.toRectF(): RectF? {
        this ?: return null
        val left = optFloat("left", 0f)
        val top = optFloat("top", 0f)
        val width = if (has("width")) optFloat("width", 0f) else optFloat("right", left) - left
        val height = if (has("height")) optFloat("height", 0f) else optFloat("bottom", top) - top
        if (width <= 0f || height <= 0f) return null
        return RectF(left, top, left + width, top + height)
    }

    private fun JSONObject.optNullableFloat(key: String): Float? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        if (value.isNaN() || value.isInfinite()) return null
        return value.toFloat()
    }

    private fun JSONObject.optFloat(key: String, fallback: Float): Float {
        val value = optDouble(key, fallback.toDouble())
        return if (value.isNaN() || value.isInfinite()) fallback else value.toFloat()
    }

    private fun JSONObject.optNullableColor(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optLong(key).toInt()
    }

    private fun JSONObject.optTextFragmentKind(key: String): EpubWebTextFragmentKind {
        return when (optString(key)) {
            "ruby" -> EpubWebTextFragmentKind.Ruby
            "rubyText" -> EpubWebTextFragmentKind.RubyText
            "superscript" -> EpubWebTextFragmentKind.Superscript
            "subscript" -> EpubWebTextFragmentKind.Subscript
            else -> EpubWebTextFragmentKind.Text
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        this ?: return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun JSONArray?.toGlyphs(): List<EpubWebGlyph> {
        this ?: return emptyList()
        if (length() == 0) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val text = item.optString("text").takeIf { it.isNotEmpty() } ?: continue
                val x = item.optNullableFloat("x") ?: item.optNullableFloat("xPx") ?: continue
                val y = item.optNullableFloat("y") ?: item.optNullableFloat("yPx") ?: continue
                val width = item.optNullableFloat("width") ?: item.optNullableFloat("widthPx") ?: 0f
                val height = item.optNullableFloat("height") ?: item.optNullableFloat("heightPx") ?: 0f
                if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite()) continue
                add(
                    EpubWebGlyph(
                        text = text,
                        xPx = x,
                        yPx = y,
                        widthPx = width.coerceAtLeast(0f),
                        heightPx = height.coerceAtLeast(0f),
                        baselinePx = item.optNullableFloat("baseline") ?: item.optNullableFloat("baselinePx")
                    )
                )
            }
        }
    }
}
