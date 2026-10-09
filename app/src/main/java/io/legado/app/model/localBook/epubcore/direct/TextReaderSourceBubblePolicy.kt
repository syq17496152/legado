package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.help.ImageSourceOptions
import io.legado.app.help.ParsedImageSource
import okio.ByteString.Companion.decodeBase64
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * Applies only the explicitly enabled image-display preference. This policy never
 * evaluates source scripts, changes chapter text, or supplies a click action.
 * A null result leaves the source image on its normal loading path.
 */
internal object TextReaderSourceBubblePolicy {
    private const val MAX_SOURCE_CHARS = 2 * 1024 * 1024
    private const val MAX_LABEL_CHARS = 24
    private val displayKeys = listOf("displayText", "num", "\$num", "\${num}", "{{num}}", "count", "text", "label")
    private val colorKeys = listOf("displayColor", "color", "\$color", "\${color}", "{{color}}")
    private val types = setOf("qd", "fqpl", "fanqie", "cmt", "comment", "comments", "review", "paragraph", "paragraphcomment")
    private val displayParameter = parameterPattern(displayKeys, 48)
    private val colorParameter = parameterPattern(colorKeys, 32)
    private val statusParameter = parameterPattern(listOf("status"), 32)
    private val createSvgCount = Regex(
        """createSvg2?\s*\((?:[^,)]*,){3}\s*([0-9]{1,8})""",
        RegexOption.IGNORE_CASE
    )

    fun resolve(
        original: String,
        resolved: String,
        style: String? = null,
        click: String? = null,
        enabled: Boolean
    ): String? {
        if (!enabled || original.length > MAX_SOURCE_CHARS || resolved.length > MAX_SOURCE_CHARS) return null
        val source = ImageSourceOptions.parse(original) ?: return null
        val result = ImageSourceOptions.parse(resolved)?.source.orEmpty()
        // Explicit virtual images already have a dedicated renderer and metadata.
        if (TextReaderImageSource.isBubble(source.source) || TextReaderImageSource.isBubble(result)) return null
        if (!isCandidate(source, result, style, click)) return null
        val sources = listOf(source.source, result).filter(String::isNotBlank).distinct()
        val displayText = displayKeys.firstNotNullOfOrNull { normalizeMarkup(source.option(it)) }
            ?: listOfNotNull(source.click, source.option("pclick"), source.option("js"), click)
                .firstNotNullOfOrNull { script ->
                    createSvgCount.find(script)?.groupValues?.getOrNull(1)?.let(::normalizeLiteral)
                }
            ?: sources.firstNotNullOfOrNull { value ->
                parameter(value, displayParameter) ?: svgText(value)
            }
            ?: return null
        val color = colorKeys.firstNotNullOfOrNull { normalizeMarkup(source.option(it)) }
            ?: sources.firstNotNullOfOrNull { parameter(it, colorParameter) }
        val status = normalizeMarkup(source.option("status"))
            ?: sources.firstNotNullOfOrNull { parameter(it, statusParameter) }
            ?: "normal"
        return "bubble://paragraph?displayText=${encoded(displayText)}&num=${encoded(displayText)}&status=${encoded(status)}" +
            (color?.let { "&displayColor=${encoded(it)}" } ?: "")
    }

    // Recognize paragraph-comment images before TextReaderDocument splits blocks.
    // Pixel replacement is optional; their inline position is not. No image
    // decoding or source script execution is needed to read this metadata.
    fun isImplicitInlineComment(source: ParsedImageSource, style: String?, click: String?): Boolean =
        (source.style ?: style).isNullOrBlank() && hasCommentSemantics(source, click)

    private fun hasCommentSemantics(source: ParsedImageSource, click: String?): Boolean {
        val knownType = source.option("type")?.lowercase(Locale.ROOT) in types
        val actions = listOfNotNull(source.click, source.option("pclick"), click)
            .joinToString("\n").lowercase(Locale.ROOT)
        return knownType || actions.contains("showcmt(") ||
            actions.contains("showcomment(") || actions.contains("paragraph")
    }

    private fun isCandidate(
        source: ParsedImageSource,
        resolved: String,
        style: String?,
        click: String?
    ): Boolean {
        val effectiveStyle = (source.style ?: style).orEmpty().trim()
        val inline = effectiveStyle.equals("TEXT", true)
        // A source's explicit standalone layout always wins over heuristics.
        if (effectiveStyle.isNotEmpty() && !inline) return false
        val inlineSvg = inline && (isDataSvg(source.source) || isDataSvg(resolved))
        return hasCommentSemantics(source, click) || inlineSvg
    }

    private fun svgText(source: String): String? {
        val svg = decodeSvg(source) ?: return null
        // XML parsing is inert and avoids repeatedly scanning malformed <text>
        // tags with an unbounded regular expression.
        val document = runCatching { Jsoup.parse(svg, "", Parser.xmlParser()) }.getOrNull() ?: return null
        if (document.allElements.none { it.normalName().substringAfter(':') == "svg" }) return null
        val texts = document.allElements.asSequence()
            .filter { it.normalName().substringAfter(':') == "text" }
            .mapNotNull { normalizeLiteral(it.text()) }
            .toList()
        return texts.firstOrNull { value -> value.any(Char::isDigit) } ?: texts.firstOrNull()
    }

    private fun decodeSvg(source: String): String? {
        if (!isDataSvg(source)) return null
        val comma = source.indexOf(',')
        if (comma < 0) return null
        val metadata = source.substring(0, comma)
        val payload = decodePercent(source.substring(comma + 1)) ?: return null
        val svg = if (metadata.contains(";base64", true)) {
            payload.decodeBase64()?.toByteArray()?.toString(Charsets.UTF_8)
        } else payload
        return svg?.takeIf { it.length <= MAX_SOURCE_CHARS }
    }

    private fun isDataSvg(source: String) = source.startsWith("data:image/svg+xml", true)

    private fun parameter(source: String, pattern: Regex): String? =
        pattern.find(source)?.groupValues?.getOrNull(1)?.let(::decodePercent)?.let(::normalizeMarkup)

    private fun parameterPattern(names: List<String>, maximum: Int): Regex {
        val keys = names.joinToString("|", transform = Regex::escape)
        return Regex("""(?:^|[?&,])(?:$keys)=([^&,\s]{1,$maximum})""", RegexOption.IGNORE_CASE)
    }

    private fun normalizeMarkup(value: String?): String? = value?.let {
        normalizeLiteral(Jsoup.parseBodyFragment(it).text())
    }

    private fun normalizeLiteral(value: String?): String? = value?.trim()
        ?.takeIf(String::isNotEmpty)?.let {
            val length = it.codePointCount(0, it.length).coerceAtMost(MAX_LABEL_CHARS)
            it.substring(0, it.offsetByCodePoints(0, length))
        }

    private fun decodePercent(value: String): String? = runCatching {
        // Android Uri.decode, used by the native reader, preserves literal '+'.
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }.getOrNull()

    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
