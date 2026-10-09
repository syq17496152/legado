package io.legado.app.model.localBook.epubcore.direct

internal object EpubDirectMediaDocument {

    fun read(
        href: String,
        title: String,
        mediaType: String?,
        textFallback: () -> String
    ): String {
        return build(href, title, mediaType) ?: textFallback()
    }

    fun build(href: String, title: String, mediaType: String?): String? {
        val normalizedType = mediaType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            .orEmpty()
        val source = escapeHtml(href.substringAfterLast('/'))
        val escapedTitle = escapeHtml(title)
        val body = when {
            normalizedType.startsWith("image/") ->
                "<img src=\"$source\" alt=\"$escapedTitle\"/>"
            normalizedType.startsWith("video/") ->
                "<video src=\"$source\" controls=\"controls\" playsinline=\"playsinline\" preload=\"metadata\"></video>"
            normalizedType.startsWith("audio/") ->
                "<audio src=\"$source\" controls=\"controls\" preload=\"metadata\"></audio>"
            else -> return null
        }
        return "<!doctype html><html xmlns=\"${EpubDirectDocumentBuilder.XHTML_NAMESPACE}\"><head><title>$escapedTitle</title></head><body>$body</body></html>"
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
