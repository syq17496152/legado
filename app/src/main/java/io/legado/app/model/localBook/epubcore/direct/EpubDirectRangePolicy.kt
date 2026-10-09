package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.model.localBook.epubcore.EpubRegex

/** HTTP `Range: bytes=...` 请求区间（闭区间）。 */
internal data class EpubDirectByteRange(
    val start: Long,
    val endInclusive: Long
) {
    val length: Long get() = endInclusive - start + 1L
}

/**
 * Range 头解析策略：媒体资源按需读时用于判断是否可切片、是否需要预热磁盘缓存。
 *
 * 迁移自 archive v15（纯算法，未改）。
 */
internal object EpubDirectRangePolicy {

    fun parse(header: String?, size: Long): EpubDirectByteRange? {
        if (header.isNullOrBlank() || size <= 0L) return null
        val match = RANGE_HEADER.matchEntire(header.trim()) ?: return null
        val value = match.groupValues[1].trim()
        if ('-' !in value) return null
        val startText = value.substringBefore('-').trim()
        val endText = value.substringAfter('-').trim()
        if (startText.isBlank()) {
            // 后缀形式 `bytes=-N`：取末尾 N 字节
            val suffixLength = endText.toLongOrNull()?.coerceAtMost(size) ?: return null
            if (suffixLength <= 0L) return null
            return EpubDirectByteRange(size - suffixLength, size - 1L)
        }
        val start = startText.toLongOrNull() ?: return null
        if (start !in 0 until size) return null
        val end = endText.toLongOrNull()?.coerceAtMost(size - 1L) ?: (size - 1L)
        if (end < start) return null
        return EpubDirectByteRange(start, end)
    }

    /** 非零起点（seek 场景）才值得预热磁盘缓存。 */
    fun shouldPrepareDiskCache(header: String?, size: Long?): Boolean {
        if (size == null) return false
        return parse(header, size)?.start?.let { it > 0L } == true
    }

    private val RANGE_HEADER = EpubRegex.compile(
        "bytes\\s*=\\s*([^,]+)",
        RegexOption.IGNORE_CASE
    )
}