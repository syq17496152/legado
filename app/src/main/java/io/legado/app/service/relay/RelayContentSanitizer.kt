package io.legado.app.service.relay

internal object RelayContentSanitizer {
    private val imageTag = Regex(
        pattern = "<img\\b[^>]*>",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    fun removeImages(content: String): String = content.replace(imageTag, "")
}
