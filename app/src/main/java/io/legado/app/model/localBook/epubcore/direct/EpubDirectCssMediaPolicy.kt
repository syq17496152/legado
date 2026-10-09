package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.model.localBook.epubcore.EpubRegex

/**
 * CSS `@media` 策略：识别「非屏幕媒体」（print/speech 等）与惰性特性，
 * 避免把打印/朗读样式误判成屏幕布局依据。
 *
 * 迁移自 archive v15（纯算法，未改）。
 */
internal object EpubDirectCssMediaPolicy {

    fun mayApplyToScreen(media: String): Boolean {
        if (media.isBlank()) return true
        return splitQueries(media).any(::queryMayApplyToScreen)
    }

    /** 递归剔除不作用于屏幕的 `@media` 块，保留注释与字符串原样。 */
    fun screenRelevantCss(css: String): String {
        if (!css.contains("@media", ignoreCase = true)) return css
        val output = StringBuilder(css.length)
        var index = 0
        while (index < css.length) {
            when {
                css.startsWith("/*", index) -> {
                    val end = css.indexOf("*/", index + 2).let { if (it < 0) css.length else it + 2 }
                    output.append(css, index, end)
                    index = end
                }
                css[index] == '\'' || css[index] == '"' -> {
                    val end = quotedEnd(css, index)
                    output.append(css, index, end)
                    index = end
                }
                css.regionMatches(index, "@media", 0, 6, ignoreCase = true) &&
                    isAtRuleBoundary(css, index, index + 6) -> {
                    val blockStart = blockStart(css, index + 6)
                    if (blockStart < 0) {
                        output.append(css, index, css.length)
                        break
                    }
                    val blockEnd = matchingBrace(css, blockStart)
                    if (blockEnd < 0) {
                        output.append(css, index, css.length)
                        break
                    }
                    val query = css.substring(index + 6, blockStart).trim()
                    if (mayApplyToScreen(query)) {
                        output.append(css, index, blockStart + 1)
                        output.append(screenRelevantCss(css.substring(blockStart + 1, blockEnd)))
                        output.append('}')
                    }
                    index = blockEnd + 1
                }
                else -> output.append(css[index++])
            }
        }
        return output.toString()
    }

    private fun queryMayApplyToScreen(query: String): Boolean {
        val normalized = COMMENTS.replace(query, " ").trim().lowercase()
        if (normalized.isBlank()) return true
        val tokens = normalized.split(WHITESPACE).filter(String::isNotBlank)
        if (tokens.isEmpty()) return true
        val negated = tokens.first() == "not"
        val typeIndex = when {
            negated && tokens.getOrNull(1) == "only" -> 2
            negated -> 1
            tokens.first() == "only" -> 1
            else -> 0
        }
        val rawType = tokens.getOrNull(typeIndex).orEmpty()
        if (rawType.isBlank() || rawType.startsWith('(')) {
            // 仅含特性条件的查询可能命中屏幕（含取反特性）。
            return true
        }
        val mediaType = rawType.substringBefore('(').trim()
        val typeMatchesScreen = mediaType == "screen" || mediaType == "all"
        if (!negated) return typeMatchesScreen

        // `not` 取反整个查询：`not screen and (...)` 在某些条件下仍为真，故保留。
        val hasFeatureCondition = tokens.drop(typeIndex + 1).any { '(' in it }
        return !typeMatchesScreen || hasFeatureCondition
    }

    private fun splitQueries(media: String): List<String> {
        val result = arrayListOf<String>()
        var start = 0
        var parentheses = 0
        var index = 0
        while (index < media.length) {
            when {
                media.startsWith("/*", index) -> {
                    index = media.indexOf("*/", index + 2).let { if (it < 0) media.length else it + 2 }
                    continue
                }
                media[index] == '\'' || media[index] == '"' -> {
                    index = quotedEnd(media, index)
                    continue
                }
                media[index] == '(' -> parentheses++
                media[index] == ')' && parentheses > 0 -> parentheses--
                media[index] == ',' && parentheses == 0 -> {
                    result += media.substring(start, index)
                    start = index + 1
                }
            }
            index++
        }
        result += media.substring(start)
        return result
    }

    private fun blockStart(css: String, from: Int): Int {
        var index = from
        while (index < css.length) {
            when {
                css.startsWith("/*", index) -> {
                    index = css.indexOf("*/", index + 2).let { if (it < 0) return -1 else it + 2 }
                    continue
                }
                css[index] == '\'' || css[index] == '"' -> {
                    index = quotedEnd(css, index)
                    continue
                }
                css[index] == '{' -> return index
                css[index] == ';' -> return -1
            }
            index++
        }
        return -1
    }

    private fun matchingBrace(css: String, opening: Int): Int {
        var depth = 1
        var index = opening + 1
        while (index < css.length) {
            when {
                css.startsWith("/*", index) -> {
                    index = css.indexOf("*/", index + 2).let { if (it < 0) return -1 else it + 2 }
                    continue
                }
                css[index] == '\'' || css[index] == '"' -> {
                    index = quotedEnd(css, index)
                    continue
                }
                css[index] == '{' -> depth++
                css[index] == '}' && --depth == 0 -> return index
            }
            index++
        }
        return -1
    }

    private fun quotedEnd(source: String, opening: Int): Int {
        val quote = source[opening]
        var index = opening + 1
        while (index < source.length) {
            when {
                source[index] == '\\' -> index += 2
                source[index] == quote -> return index + 1
                else -> index++
            }
        }
        return source.length
    }

    private fun isAtRuleBoundary(source: String, start: Int, end: Int): Boolean {
        val before = source.getOrNull(start - 1)
        val after = source.getOrNull(end)
        return before?.let { !it.isLetterOrDigit() && it != '-' && it != '_' } != false &&
            after?.let { !it.isLetterOrDigit() && it != '-' && it != '_' } != false
    }

    private val COMMENTS = EpubRegex.compile("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val WHITESPACE = EpubRegex.compile("\\s+")
}