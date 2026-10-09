package io.legado.app.model.localBook.epubcore.layout

import android.graphics.Color

/**
 * 阅读器自有「页眉/页脚」几何与视觉契约。
 *
 * 本对象**刻意不含**任何随页变化的文本：文本属于 [EpubReaderChromeData]，
 * 且不得参与章节布局/缓存身份（[geometryKey] 只含几何常量）。
 *
 * 迁移自 archive v15（纯数据模型，未改）。默认 [DISABLED] 时与旧布局契约逐字节等价。
 */
data class EpubReaderChromeConfig(
    val enabled: Boolean = false,
    val headerEnabled: Boolean = false,
    val footerEnabled: Boolean = false,
    val hideHeaderOnChapterFirstPage: Boolean = true,
    val headerHeightPx: Int = 0,
    val footerHeightPx: Int = 0,
    val headerPaddingLeftPx: Int = 0,
    val headerPaddingTopPx: Int = 0,
    val headerPaddingRightPx: Int = 0,
    val headerPaddingBottomPx: Int = 0,
    val footerPaddingLeftPx: Int = 0,
    val footerPaddingTopPx: Int = 0,
    val footerPaddingRightPx: Int = 0,
    val footerPaddingBottomPx: Int = 0,
    val textSizePx: Float = 12f,
    val textColor: Int = Color.TRANSPARENT,
    val dividerColor: Int = Color.TRANSPARENT,
    val headerDividerEnabled: Boolean = false,
    val footerDividerEnabled: Boolean = false,
    val backgroundColor: Int = Color.TRANSPARENT,
    val backgroundAlpha: Float = 0f,
    val headerLeft: EpubReaderChromeField = EpubReaderChromeField.NONE,
    val headerCenter: EpubReaderChromeField = EpubReaderChromeField.NONE,
    val headerRight: EpubReaderChromeField = EpubReaderChromeField.NONE,
    val footerLeft: EpubReaderChromeField = EpubReaderChromeField.NONE,
    val footerCenter: EpubReaderChromeField = EpubReaderChromeField.NONE,
    val footerRight: EpubReaderChromeField = EpubReaderChromeField.NONE,
    val geometryRevision: Long = 0L
) {

    val reservedHeaderHeightPx: Int
        get() = if (enabled && headerEnabled) headerHeightPx.coerceAtLeast(0) else 0

    val reservedFooterHeightPx: Int
        get() = if (enabled && footerEnabled) footerHeightPx.coerceAtLeast(0) else 0

    /** 把几何收敛到页面可用高度内，且不允许正文区变负。禁用/默认配置保持与旧契约等价。 */
    fun normalizedForPage(pageHeightPx: Int): EpubReaderChromeConfig {
        if (!enabled || (!headerEnabled && !footerEnabled)) return DISABLED
        val available = pageHeightPx.coerceAtLeast(1)
        val header = if (headerEnabled) headerHeightPx.coerceIn(0, available) else 0
        val footerLimit = (available - header).coerceAtLeast(0)
        val footer = if (footerEnabled) footerHeightPx.coerceIn(0, footerLimit) else 0
        return copy(
            headerHeightPx = header,
            footerHeightPx = footer,
            headerPaddingLeftPx = headerPaddingLeftPx.coerceAtLeast(0),
            headerPaddingTopPx = headerPaddingTopPx.coerceAtLeast(0),
            headerPaddingRightPx = headerPaddingRightPx.coerceAtLeast(0),
            headerPaddingBottomPx = headerPaddingBottomPx.coerceAtLeast(0),
            footerPaddingLeftPx = footerPaddingLeftPx.coerceAtLeast(0),
            footerPaddingTopPx = footerPaddingTopPx.coerceAtLeast(0),
            footerPaddingRightPx = footerPaddingRightPx.coerceAtLeast(0),
            footerPaddingBottomPx = footerPaddingBottomPx.coerceAtLeast(0),
            textSizePx = textSizePx.takeIf { it.isFinite() && it > 0f } ?: 12f,
            backgroundAlpha = backgroundAlpha.coerceIn(0f, 1f)
        )
    }

    /** 几何/缓存身份的稳定键；随页变化的动态内容**刻意排除**。 */
    fun geometryKey(): String = buildString {
        append(enabled).append('|')
        append(headerEnabled).append('|').append(footerEnabled).append('|')
        append(hideHeaderOnChapterFirstPage).append('|')
        append(reservedHeaderHeightPx).append('|').append(reservedFooterHeightPx).append('|')
        append(headerPaddingLeftPx).append(',').append(headerPaddingTopPx).append(',')
        append(headerPaddingRightPx).append(',').append(headerPaddingBottomPx).append('|')
        append(footerPaddingLeftPx).append(',').append(footerPaddingTopPx).append(',')
        append(footerPaddingRightPx).append(',').append(footerPaddingBottomPx).append('|')
        append(textSizePx).append('|').append(textColor).append('|')
        append(dividerColor).append('|').append(headerDividerEnabled).append('|')
        append(footerDividerEnabled).append('|').append(backgroundColor).append('|')
        append(backgroundAlpha).append('|').append(geometryRevision)
    }

    companion object {
        val DISABLED = EpubReaderChromeConfig()
    }
}

/** 经典阅读器信息字段（页眉/页脚槽位可选项）。 */
enum class EpubReaderChromeField {
    NONE,
    BOOK_NAME,
    CHAPTER_TITLE,
    TIME,
    BATTERY,
    BATTERY_PERCENTAGE,
    PAGE,
    TOTAL_PROGRESS,
    CHAPTER_PROGRESS,
    PAGE_AND_TOTAL,
    TIME_BATTERY,
    TIME_BATTERY_PERCENTAGE
}