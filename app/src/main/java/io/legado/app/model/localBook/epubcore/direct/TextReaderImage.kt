package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.help.ImageSourceOptions
import io.legado.app.help.book.ParagraphRuleProcessor
import io.legado.app.utils.GSON
import org.jsoup.parser.Parser
import java.util.Locale

// 注：archive 把 TextReaderImageAction / TextReaderSourceImages 定义在本文件；
// 本仓已按职责拆到 TextReaderSourceImages.kt（使 EpubDirectChapter 不必依赖图片渲染管线）。

data class TextReaderImage(
    val id: String,
    val source: String,
    val renderSource: String,
    val click: String?,
    val inline: Boolean,
    val width: String?,
    val height: String?,
    val alignment: String?,
    val sourceStyle: String? = null
) {
    companion object {
        private val presentationOptions = setOf("click", "onclick", "pclick", "style", "width", "height")
        private val dimension = Regex("([0-9]+(?:\\.[0-9]+)?)(px|dp|dip|sp|em|rem|%)?", RegexOption.IGNORE_CASE)

        internal fun fromAttributes(id: String, attributes: Map<String, String>): TextReaderImage? {
            val original = attributes["src"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val parsed = ImageSourceOptions.parse(original) ?: return null
            val click = (parsed.click ?: attributes["click"] ?: attributes["onclick"] ?: attributes["data-click"])
                ?.trim()?.takeIf(String::isNotEmpty)
                ?.takeUnless(ParagraphRuleProcessor::isParagraphClick)
            val style = (parsed.style ?: attributes["style"]).orEmpty().trim().lowercase(Locale.ROOT)
            val requestOptions = parsed.options.filterKeys { it.lowercase(Locale.ROOT) !in presentationOptions }
            // A data URI can be a placeholder whose loading js produces the real SVG.
            // Only the resolver may decide whether it is a complete browser resource.
            val renderSource = if (requestOptions.isEmpty()) {
                parsed.source
            } else parsed.source + "," + GSON.toJson(requestOptions)
            return TextReaderImage(
                id, original, renderSource, click, style == "text" || TextReaderImageSource.isBubble(parsed.source) ||
                    TextReaderSourceBubblePolicy.isImplicitInlineComment(parsed, style, click),
                cssDimension(parsed.width ?: attributes["width"]),
                cssDimension(parsed.option("height") ?: attributes["height"]),
                style.takeIf { it in setOf("left", "center", "right") },
                style.takeIf(String::isNotEmpty)
            )
        }

        internal fun cssDimension(value: String?): String? {
            val match = dimension.matchEntire(value?.trim().orEmpty()) ?: return null
            val size = match.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
            val unit = match.groupValues[2].lowercase(Locale.ROOT).let {
                if (it.isEmpty() || it in setOf("dp", "dip", "sp")) "px" else it
            }
            if (size > if (unit == "%") 100.0 else 4096.0) return null
            return match.groupValues[1] + unit
        }
    }
}

/**
 * Legado sources sometimes put unescaped JSON quotes inside a quoted src.
 * Read just image attributes, skipping a balanced option object, before Jsoup
 * parses the rest of the chapter. The scan is linear and never evaluates HTML.
 */
internal object TextReaderImageMarkup {
    data class Prepared(val html: String, val images: List<TextReaderImage?>)
    private data class Tag(val end: Int, val attributes: Map<String, String>?)
    private val imageStart = Regex("<img(?=[\\s/>])", RegexOption.IGNORE_CASE)
    private val retainedAttributes = setOf("src", "click", "onclick", "data-click", "style", "width", "height")

    fun prepare(source: String): Prepared {
        val images = ArrayList<TextReaderImage?>()
        val html = StringBuilder(source.length)
        var copied = 0
        var scanned = 0
        for (match in imageStart.findAll(source)) {
            if (match.range.first < scanned) continue
            val tag = readTag(source, match.range.last + 1)
            scanned = tag.end
            val attributes = tag.attributes ?: continue
            check(images.size < TextReaderDocument.MAX_BLOCKS) { "本章图片过多，请切回原生渲染" }
            val index = images.size
            images.add(TextReaderImage.fromAttributes("image-$index", attributes))
            html.append(source, copied, match.range.first)
            html.append("<img data-legado-image-index=\"").append(index).append("\">")
            copied = tag.end
        }
        if (copied == 0) return Prepared(source, images)
        return Prepared(html.append(source, copied, source.length).toString(), images)
    }

    private fun readTag(source: String, start: Int): Tag {
        val attributes = LinkedHashMap<String, String>()
        var index = start
        while (index < source.length) {
            while (index < source.length && (source[index].isWhitespace() || source[index] == '/')) index++
            if (index >= source.length) return Tag(index, null)
            if (source[index] == '>') return Tag(index + 1, attributes)
            if (source[index] == '<') return Tag(index, null)
            val nameStart = index
            while (index < source.length && !source[index].isWhitespace() && source[index] !in "=><") index++
            if (index == nameStart) { index++; continue }
            val name = source.substring(nameStart, index).lowercase(Locale.ROOT)
            while (index < source.length && source[index].isWhitespace()) index++
            if (index >= source.length || source[index] != '=') continue
            index++
            while (index < source.length && source[index].isWhitespace()) index++
            if (index >= source.length) return Tag(index, null)
            val quote = source[index].takeIf { it == '\'' || it == '"' }
            if (quote != null) index++
            val valueStart = index
            var triedOptions = false
            while (index < source.length) {
                if (quote != null && source[index] == quote ||
                    quote == null && (source[index].isWhitespace() || source[index] == '>')) break
                if (name == "src" && !triedOptions && source[index] == ',') {
                    var optionStart = index + 1
                    while (optionStart < source.length && source[optionStart].isWhitespace()) optionStart++
                    if (optionStart < source.length && source[optionStart] == '{') {
                        triedOptions = true
                        val end = optionEnd(source, optionStart)
                        if (end >= 0) { index = end + 1; continue }
                    }
                }
                index++
            }
            if (name in retainedAttributes && name !in attributes) {
                attributes[name] = Parser.unescapeEntities(source.substring(valueStart, index), true)
            }
            if (quote != null) {
                if (index >= source.length) return Tag(index, null)
                index++
            }
        }
        return Tag(index, null)
    }

    private fun optionEnd(source: String, start: Int): Int {
        var index = start + 1
        while (index < source.length && source[index].isWhitespace()) index++
        val escapedQuotes = source.startsWith("\\\"", index)
        var depth = 1
        var quote: Char? = null
        while (index < source.length) {
            val char = source[index]
            if (char == '\\') {
                var end = index
                while (end < source.length && source[end] == '\\') end++
                val count = end - index
                if (escapedQuotes && end < source.length && source[end] == '"' && count % 4 == 1) {
                    quote = if (quote == '"') null else if (quote == null) '"' else quote
                } else if (!escapedQuotes && count % 2 == 0 && end < source.length) {
                    if (source[end] == quote) quote = null
                    else if (quote == null && source[end] in "\"'") quote = source[end]
                }
                index = (end + 1).coerceAtMost(source.length)
                continue
            }
            if (quote != null) {
                if (char == quote) quote = null
            } else when (char) {
                '"', '\'' -> quote = char
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
            index++
        }
        return -1
    }
}
