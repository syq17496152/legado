package io.legado.app.model.localBook.epubcore.archive

import java.net.URLDecoder
import java.net.URI

/**
 * EPUB 内部路径工具（迁移自 archive v15，为我方同名文件的**超集**）。
 *
 * 相对我方旧版的增强：
 * - [normalize] 同时剥离 `#fragment` 与 `?query`；
 * - [resolve] 支持绝对 href（`//` 与带 scheme）、以 `/` 开头的站点根路径、`?` 剥离；
 * - 新增 [stripDecorations] / [encodeFragment] / [decodedFragment] / [encodePathSegment]；
 * - 解码改用 URLDecoder（`+` 先转义为 `%2B`），避免路径中的 `+` 被误解为空格。
 */
object EpubPath {

    fun normalize(path: String): String {
        val raw = stripDecorations(path).replace('\\', '/')
        val parts = ArrayDeque<String>()
        raw.split('/').forEach { part ->
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    fun resolve(basePath: String, href: String): String {
        val fragment = fragment(href)
        val cleanHref = stripDecorations(href)
        if (cleanHref.isBlank()) {
            return withFragment(normalize(basePath), fragment)
        }
        if (cleanHref.startsWith("//") || hasScheme(cleanHref)) {
            return href
        }
        if (basePath.startsWith("//") || hasScheme(basePath)) {
            return runCatching { URI(basePath).resolve(href).toString() }.getOrDefault(href)
        }
        val normalizedBase = normalize(basePath)
        val baseDir = if (stripDecorations(basePath).replace('\\', '/').endsWith('/')) {
            normalizedBase
        } else {
            normalizedBase.substringBeforeLast('/', "")
        }
        val combined = when {
            cleanHref.startsWith('/') -> cleanHref.trimStart('/')
            baseDir.isBlank() -> cleanHref
            else -> "$baseDir/$cleanHref"
        }
        return withFragment(normalize(decodePath(combined)), fragment)
    }

    fun fragment(href: String?): String? {
        if (href.isNullOrBlank()) return null
        val index = href.indexOf('#')
        return if (index >= 0 && index + 1 < href.length) href.substring(index + 1) else null
    }

    fun stripFragment(path: String): String = path.substringBefore('#')

    fun encodeFragment(fragment: String): String {
        return encodeComponent(decodeComponent(fragment))
    }

    fun decodedFragment(href: String?): String? {
        return fragment(href)?.let(::decodeComponent)
    }

    fun encodePathSegment(segment: String): String {
        return encodeComponent(decodeComponent(segment))
    }

    private fun encodeComponent(decoded: String): String {
        return buildString(decoded.length) {
            decoded.toByteArray(Charsets.UTF_8).forEach { byte ->
                val value = byte.toInt() and 0xff
                if ((value in 'a'.code..'z'.code) ||
                    (value in 'A'.code..'Z'.code) ||
                    (value in '0'.code..'9'.code) ||
                    value == '-'.code || value == '.'.code || value == '_'.code || value == '~'.code
                ) {
                    append(value.toChar())
                } else {
                    append('%')
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0f])
                }
            }
        }
    }

    fun stripDecorations(path: String): String {
        return path.substringBefore('#').substringBefore('?')
    }

    private fun withFragment(path: String, fragment: String?): String {
        return if (fragment.isNullOrBlank()) path else "$path#$fragment"
    }

    private fun decodePath(path: String): String {
        return decodeComponent(path)
    }

    private fun decodeComponent(value: String): String {
        return runCatching {
            URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrDefault(value)
    }

    private fun hasScheme(path: String): Boolean {
        val separator = path.indexOf(':')
        if (separator <= 0 || !path[0].isLetter()) return false
        return path.substring(1, separator).all { char ->
            char.isLetterOrDigit() || char == '+' || char == '-' || char == '.'
        }
    }

    private const val HEX = "0123456789ABCDEF"
}