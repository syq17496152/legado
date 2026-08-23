package io.legado.app.model.localBook.epubcore.archive

import java.net.URI

object EpubPath {

    fun normalize(path: String): String {
        val raw = path.replace('\\', '/').substringBefore('#')
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
        val cleanHref = href.substringBefore('#')
        if (cleanHref.isBlank()) {
            return withFragment(normalize(basePath), fragment)
        }
        val baseDir = normalize(basePath).substringBeforeLast('/', "")
        val combined = if (baseDir.isBlank()) cleanHref else "$baseDir/$cleanHref"
        return withFragment(normalize(decodePath(combined)), fragment)
    }

    fun fragment(href: String?): String? {
        if (href.isNullOrBlank()) return null
        val index = href.indexOf('#')
        return if (index >= 0 && index + 1 < href.length) href.substring(index + 1) else null
    }

    fun stripFragment(path: String): String = path.substringBefore('#')

    private fun withFragment(path: String, fragment: String?): String {
        return if (fragment.isNullOrBlank()) path else "$path#$fragment"
    }

    private fun decodePath(path: String): String {
        return runCatching { URI(null, null, path, null).path }.getOrDefault(path)
    }
}
