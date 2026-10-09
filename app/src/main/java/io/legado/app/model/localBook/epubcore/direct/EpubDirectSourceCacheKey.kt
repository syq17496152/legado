package io.legado.app.model.localBook.epubcore.direct

/**
 * Direct 内容源缓存键：媒体型（image/video/audio）用标题区分，
 * 文本型用「续页 href 链」区分（同一 XHTML 可切出多个逻辑章节）。
 *
 * 迁移自 archive v15（纯算法，未改）。
 */
internal object EpubDirectSourceCacheKey {

    fun create(
        href: String,
        mediaType: String?,
        title: String,
        continuationHrefs: List<String>
    ): String {
        val normalizedType = mediaType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            .orEmpty()
        return buildString {
            append(href).append('|').append(normalizedType)
            if (normalizedType.startsWith("image/") ||
                normalizedType.startsWith("video/") ||
                normalizedType.startsWith("audio/")
            ) {
                append('|').append(title)
            } else {
                continuationHrefs.forEach { append('|').append(it) }
            }
        }
    }
}