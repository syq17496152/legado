package io.legado.app.model.localBook.epubcore.style

import java.util.Locale

object EpubCss {

    data class Rule(
        val selector: String,
        val declarations: List<Declaration>,
        val specificity: Int,
        val order: Int
    )

    data class Declaration(
        val name: String,
        val value: String,
        val important: Boolean,
        val order: Int
    )

    fun parseRules(css: String): List<Rule> {
        if (css.isBlank()) return emptyList()
        val cleanCss = css.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").expandSupportedAtRules()
        val rules = arrayListOf<Rule>()
        var order = 0
        var index = 0
        while (index < cleanCss.length) {
            val start = cleanCss.indexOf('{', index)
            if (start < 0) break
            val end = cleanCss.findMatchingCssBrace(start)
            if (end < 0) break
            val declarations = parseDeclarations(cleanCss.substring(start + 1, end))
                .filter { it.name in supportedProperties }
            if (declarations.isNotEmpty()) {
                cleanCss.substring(index, start)
                    .split(',')
                    .map { it.trim() }
                    .mapNotNull { it.toSupportedSelector() }
                    .forEach { selector ->
                        rules += Rule(selector, declarations, selector.cssSpecificity(), order)
                    }
                order++
            }
            index = end + 1
        }
        return rules
    }

    fun parseDeclarations(style: String): List<Declaration> {
        val declarations = arrayListOf<Declaration>()
        splitDeclarations(style).forEach { item ->
            val index = item.indexOf(':')
            if (index <= 0) return@forEach
            val name = item.substring(0, index).trim().lowercase(Locale.ROOT)
            val rawValue = item.substring(index + 1)
            val importantIndex = rawValue.indexOf("!important", ignoreCase = true)
            val value = (if (importantIndex >= 0) rawValue.substring(0, importantIndex) else rawValue)
                .trim()
                .replace("\"", "'")
            if (name.isBlank() || value.isBlank()) return@forEach
            val normalizedName = if (name == "duokan-text-indent") "text-indent" else name
            declarations += Declaration(normalizedName, value, importantIndex >= 0, declarations.size)
        }
        return declarations
            .expandFontShorthand()
            .expandBoxShorthand()
            .expandBorderShorthand()
            .expandBorderRadiusShorthand()
            .expandBackgroundShorthand()
            .expandListStyleShorthand()
            .expandTextDecorationShorthand()
    }

    fun splitValueList(value: String): List<String> {
        val result = arrayListOf<String>()
        var quote: Char? = null
        var parenDepth = 0
        var start = 0
        for (index in value.indices) {
            val char = value[index]
            if (quote != null) {
                if (char == quote && value.getOrNull(index - 1) != '\\') quote = null
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                ' ', '\t', '\r', '\n' -> if (parenDepth == 0) {
                    value.substring(start, index).trim().takeIf { it.isNotBlank() }?.let(result::add)
                    start = index + 1
                }
            }
        }
        value.substring(start).trim().takeIf { it.isNotBlank() }?.let(result::add)
        return result
    }

    private fun splitDeclarations(style: String): List<String> {
        val result = arrayListOf<String>()
        var quote: Char? = null
        var parenDepth = 0
        var start = 0
        for (index in style.indices) {
            val char = style[index]
            if (quote != null) {
                if (char == quote && style.getOrNull(index - 1) != '\\') quote = null
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                ';' -> if (parenDepth == 0) {
                    result += style.substring(start, index)
                    start = index + 1
                }
            }
        }
        if (start <= style.lastIndex) result += style.substring(start)
        return result
    }

    private fun String.expandSupportedAtRules(): String {
        val builder = StringBuilder(length)
        var index = 0
        while (index < length) {
            val at = indexOf('@', index)
            if (at < 0) {
                builder.append(substring(index))
                break
            }
            builder.append(substring(index, at))
            val nameEnd = indexOfAny(charArrayOf(' ', '\t', '\r', '\n', '{', ';'), at + 1)
                .takeIf { it >= 0 } ?: length
            val name = substring(at + 1, nameEnd).trim().lowercase(Locale.ROOT)
            val blockStart = indexOf('{', nameEnd)
            val semicolon = indexOf(';', nameEnd).takeIf { it >= 0 }
            if (blockStart < 0 || (semicolon != null && semicolon < blockStart)) {
                index = (semicolon ?: nameEnd) + 1
                continue
            }
            val blockEnd = findMatchingCssBrace(blockStart)
            if (blockEnd < 0) break
            if (name == "media" || name == "supports") {
                builder.append(substring(blockStart + 1, blockEnd))
            }
            index = blockEnd + 1
        }
        return builder.toString()
    }

    private fun String.findMatchingCssBrace(start: Int): Int {
        var depth = 0
        var quote: Char? = null
        for (index in start until length) {
            val char = this[index]
            if (quote != null) {
                if (char == quote && getOrNull(index - 1) != '\\') quote = null
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun String.toSupportedSelector(): String? {
        val selector = trim()
            .dropUnsupportedSelectorPseudo()
            .replace("|", "\\:")
        if (selector.isBlank()) return null
        if (selector.indexOfAny(charArrayOf('{', '}', ';')) >= 0) return null
        return selector
    }

    private fun String.dropUnsupportedSelectorPseudo(): String {
        val builder = StringBuilder(length)
        var index = 0
        var bracketDepth = 0
        while (index < length) {
            val char = this[index]
            when {
                char == '[' -> {
                    bracketDepth++
                    builder.append(char)
                    index++
                }
                char == ']' -> {
                    if (bracketDepth > 0) bracketDepth--
                    builder.append(char)
                    index++
                }
                char == ':' && bracketDepth == 0 -> {
                    index++
                    while (index < length && (this[index].isLetterOrDigit() || this[index] == '-' || this[index] == '_')) index++
                    if (index < length && this[index] == '(') {
                        val end = findMatchingParenthesis(index)
                        index = if (end >= 0) end + 1 else length
                    }
                }
                else -> {
                    builder.append(char)
                    index++
                }
            }
        }
        return builder.toString().trim()
    }

    private fun String.findMatchingParenthesis(start: Int): Int {
        var depth = 0
        var quote: Char? = null
        for (index in start until length) {
            val char = this[index]
            if (quote != null) {
                if (char == quote && getOrNull(index - 1) != '\\') quote = null
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun String.cssSpecificity(): Int {
        val ids = count { it == '#' }
        val classes = count { it == '.' } + count { it == '[' }
        val tags = split(Regex("[\\s>+~]+")).count { part ->
            part.isNotBlank() && !part.startsWith(".") && !part.startsWith("#") && part != "*"
        }
        return ids * 100 + classes * 10 + tags
    }

    private fun List<Declaration>.expandBoxShorthand(): List<Declaration> {
        val expanded = arrayListOf<Declaration>()
        forEach { declaration ->
            expanded += declaration
            if (declaration.name != "margin" && declaration.name != "padding") return@forEach
            val values = splitValueList(declaration.value).takeIf { it.isNotEmpty() } ?: return@forEach
            val top = values.getOrNull(0).orEmpty()
            val right = values.getOrNull(1) ?: top
            val bottom = values.getOrNull(2) ?: top
            val left = values.getOrNull(3) ?: right
            expanded += declaration.copy(name = "${declaration.name}-top", value = top, order = expanded.size)
            expanded += declaration.copy(name = "${declaration.name}-right", value = right, order = expanded.size)
            expanded += declaration.copy(name = "${declaration.name}-bottom", value = bottom, order = expanded.size)
            expanded += declaration.copy(name = "${declaration.name}-left", value = left, order = expanded.size)
        }
        return expanded
    }

    private fun List<Declaration>.expandBorderShorthand(): List<Declaration> {
        val expanded = arrayListOf<Declaration>()
        forEach { declaration ->
            expanded += declaration
            val sides = when (declaration.name) {
                "border" -> listOf("top", "right", "bottom", "left")
                "border-top" -> listOf("top")
                "border-right" -> listOf("right")
                "border-bottom" -> listOf("bottom")
                "border-left" -> listOf("left")
                else -> emptyList()
            }
            val tokens = splitValueList(declaration.value)
            val width = tokens.firstOrNull { it.isCssLengthToken() || it in borderWidthKeywords }
            val style = tokens.firstOrNull { it.lowercase(Locale.ROOT) in borderStyles }
            val color = tokens.firstOrNull { it.isCssColorToken() }
            sides.forEach { side ->
                width?.let { expanded += declaration.copy(name = "border-$side-width", value = it, order = expanded.size) }
                style?.let { expanded += declaration.copy(name = "border-$side-style", value = it, order = expanded.size) }
                color?.let { expanded += declaration.copy(name = "border-$side-color", value = it, order = expanded.size) }
            }
        }
        return expanded
    }

    private fun List<Declaration>.expandBackgroundShorthand(): List<Declaration> {
        val expanded = arrayListOf<Declaration>()
        forEach { declaration ->
            expanded += declaration
            if (declaration.name != "background") return@forEach
            val tokens = splitValueList(declaration.value)
            declaration.value.extractCssColor()?.let {
                expanded += declaration.copy(name = "background-color", value = it, order = expanded.size)
            }
            declaration.value.extractCssUrl()?.let {
                expanded += declaration.copy(name = "background-image", value = "url('$it')", order = expanded.size)
            }
            tokens.firstOrNull { it.lowercase(Locale.ROOT) in backgroundRepeatTokens }?.let {
                expanded += declaration.copy(name = "background-repeat", value = it, order = expanded.size)
            }
            tokens.indexOf("/").takeIf { it >= 0 && it + 1 < tokens.size }?.let { slash ->
                expanded += declaration.copy(name = "background-size", value = tokens.drop(slash + 1).joinToString(" "), order = expanded.size)
            }
        }
        return expanded
    }

    private fun List<Declaration>.expandBorderRadiusShorthand(): List<Declaration> {
        val expanded = arrayListOf<Declaration>()
        forEach { declaration ->
            expanded += declaration
            if (declaration.name != "border-radius") return@forEach
            val values = splitValueList(declaration.value.substringBefore('/')).takeIf { it.isNotEmpty() } ?: return@forEach
            val topLeft = values.getOrNull(0).orEmpty()
            val topRight = values.getOrNull(1) ?: topLeft
            val bottomRight = values.getOrNull(2) ?: topLeft
            val bottomLeft = values.getOrNull(3) ?: topRight
            expanded += declaration.copy(name = "border-top-left-radius", value = topLeft, order = expanded.size)
            expanded += declaration.copy(name = "border-top-right-radius", value = topRight, order = expanded.size)
            expanded += declaration.copy(name = "border-bottom-right-radius", value = bottomRight, order = expanded.size)
            expanded += declaration.copy(name = "border-bottom-left-radius", value = bottomLeft, order = expanded.size)
        }
        return expanded
    }

    private fun List<Declaration>.expandListStyleShorthand(): List<Declaration> {
        val expanded = arrayListOf<Declaration>()
        forEach { declaration ->
            expanded += declaration
            if (declaration.name != "list-style") return@forEach
            val tokens = splitValueList(declaration.value)
            tokens.firstOrNull { it.lowercase(Locale.ROOT) in listStyleTypes }?.let {
                expanded += declaration.copy(name = "list-style-type", value = it, order = expanded.size)
            }
            tokens.firstOrNull { it.lowercase(Locale.ROOT) in listStylePositions }?.let {
                expanded += declaration.copy(name = "list-style-position", value = it, order = expanded.size)
            }
        }
        return expanded
    }

    private fun List<Declaration>.expandTextDecorationShorthand(): List<Declaration> {
        val expanded = arrayListOf<Declaration>()
        forEach { declaration ->
            expanded += declaration
            if (declaration.name != "text-decoration") return@forEach
            val tokens = splitValueList(declaration.value)
            val line = tokens.filter { it.lowercase(Locale.ROOT) in textDecorationLines }
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
            line?.let { expanded += declaration.copy(name = "text-decoration-line", value = it, order = expanded.size) }
        }
        return expanded
    }

    private fun List<Declaration>.expandFontShorthand(): List<Declaration> {
        val expanded = arrayListOf<Declaration>()
        forEach { declaration ->
            expanded += declaration
            if (declaration.name != "font") return@forEach
            val tokens = splitValueList(declaration.value)
            var sizeIndex = -1
            tokens.forEachIndexed { index, token ->
                val lower = token.lowercase(Locale.ROOT)
                when {
                    lower == "italic" || lower == "oblique" -> expanded += declaration.copy(name = "font-style", value = lower, order = expanded.size)
                    lower == "bold" || lower == "bolder" || lower == "lighter" || lower.toIntOrNull() != null -> {
                        expanded += declaration.copy(name = "font-weight", value = lower, order = expanded.size)
                    }
                    sizeIndex < 0 && lower.containsFontSizeToken() -> {
                        sizeIndex = index
                        val parts = lower.split('/', limit = 2)
                        expanded += declaration.copy(name = "font-size", value = parts[0], order = expanded.size)
                        parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let {
                            expanded += declaration.copy(name = "line-height", value = it, order = expanded.size)
                        }
                    }
                }
            }
            if (sizeIndex >= 0 && sizeIndex + 1 < tokens.size) {
                expanded += declaration.copy(name = "font-family", value = tokens.drop(sizeIndex + 1).joinToString(" "), order = expanded.size)
            }
        }
        return expanded
    }

    fun String.extractCssUrl(): String? {
        val start = indexOf("url(", ignoreCase = true)
        if (start < 0) return null
        val end = indexOf(')', start + 4)
        if (end < 0) return null
        return substring(start + 4, end).trim().trim('\'', '"').takeIf { it.isNotBlank() && !it.equals("none", true) }
    }

    private fun String.extractCssColor(): String? {
        val clean = trim()
        if (clean.startsWith("#") || clean.startsWith("rgb", true)) return clean
        return splitValueList(clean).firstOrNull { it.isCssColorToken() }
    }

    private fun String.containsFontSizeToken(): Boolean {
        val sizePart = substringBefore('/')
        return sizePart.isCssLengthToken() ||
            sizePart.endsWith("%") ||
            sizePart in fontSizeKeywords ||
            sizePart.toFloatOrNull() != null
    }

    private fun String.isCssLengthToken(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.endsWith("px") || lower.endsWith("em") || lower.endsWith("rem") ||
            lower.endsWith("pt") || lower.endsWith("pc") || lower.endsWith("in") ||
            lower.endsWith("cm") || lower.endsWith("mm") || lower.endsWith("vw") ||
            lower.endsWith("vh")
    }

    private fun String.isCssColorToken(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.startsWith("#") ||
            lower.startsWith("rgb") ||
            lower == "transparent" ||
            lower in namedColorTokens
    }

    private val supportedProperties = setOf(
        "display", "visibility", "position", "float",
        "text-align", "text-indent", "text-decoration", "text-decoration-line",
        "text-transform", "letter-spacing", "word-spacing", "vertical-align",
        "color", "opacity", "background", "background-color", "background-image",
        "background-repeat", "background-size", "background-position",
        "font", "font-family", "font-size", "font-style", "font-weight", "line-height",
        "white-space", "margin", "margin-left", "margin-right", "margin-top", "margin-bottom",
        "padding", "padding-left", "padding-right", "padding-top", "padding-bottom",
        "border", "border-left", "border-right", "border-top", "border-bottom",
        "border-width", "border-style", "border-color",
        "border-left-width", "border-right-width", "border-top-width", "border-bottom-width",
        "border-left-style", "border-right-style", "border-top-style", "border-bottom-style",
        "border-left-color", "border-right-color", "border-top-color", "border-bottom-color",
        "border-radius", "border-top-left-radius", "border-top-right-radius",
        "border-bottom-right-radius", "border-bottom-left-radius",
        "width", "height", "min-width", "max-width", "min-height", "max-height",
        "list-style", "list-style-type", "list-style-position",
        "page-break-before", "page-break-after", "page-break-inside"
    )

    private val borderStyles = setOf("none", "hidden", "dotted", "dashed", "solid", "double", "groove", "ridge", "inset", "outset")
    private val borderWidthKeywords = setOf("thin", "medium", "thick")
    private val backgroundRepeatTokens = setOf("repeat", "no-repeat", "repeat-x", "repeat-y")
    private val listStylePositions = setOf("inside", "outside")
    private val listStyleTypes = setOf(
        "none", "disc", "circle", "square", "decimal",
        "lower-alpha", "upper-alpha", "lower-latin", "upper-latin",
        "lower-roman", "upper-roman"
    )
    private val textDecorationLines = setOf("none", "underline", "overline", "line-through")
    private val fontSizeKeywords = setOf("xx-small", "x-small", "small", "medium", "large", "x-large", "xx-large", "smaller", "larger")
    private val namedColorTokens = setOf(
        "black", "white", "red", "green", "blue", "cyan", "aqua", "magenta", "fuchsia",
        "yellow", "gray", "grey", "silver", "maroon", "purple", "teal", "navy", "orange"
    )
}
