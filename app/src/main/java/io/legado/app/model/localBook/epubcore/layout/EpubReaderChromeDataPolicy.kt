package io.legado.app.model.localBook.epubcore.layout

import java.text.DecimalFormat

/**
 * 解析「随页标签」与六个经典阅读信息槽位。
 *
 * 计算**刻意与 Android View / WebView 解耦**；更新这些值不得改变章节布局或缓存身份。
 *
 * 迁移自 archive v15（纯算法，未改）。
 */
object EpubReaderChromeDataPolicy {

    data class Page(
        val chapterIndex: Int,
        val chapterTitle: String,
        val pageIndex: Int,
        val pageCount: Int
    )

    fun resolve(
        config: EpubReaderChromeConfig,
        template: EpubReaderChromeData,
        page: Page
    ): EpubReaderChromeData {
        val safePageCount = page.pageCount.coerceAtLeast(1)
        val safePageIndex = page.pageIndex.coerceIn(0, safePageCount - 1)
        val safeChapterCount = template.chapterCount.coerceAtLeast(0)
        val safeChapterIndex = if (safeChapterCount > 0) {
            page.chapterIndex.coerceIn(0, safeChapterCount - 1)
        } else {
            page.chapterIndex.coerceAtLeast(0)
        }
        val pageLabel = "${safePageIndex + 1}/$safePageCount"
        val chapterProgressLabel = if (safeChapterCount > 0) {
            "${safeChapterIndex + 1}/$safeChapterCount"
        } else {
            ""
        }
        val progressLabel = if (safeChapterCount > 0) {
            val progress = (
                safeChapterIndex.toDouble() / safeChapterCount.toDouble() +
                    (safePageIndex + 1.0) / safePageCount.toDouble() / safeChapterCount.toDouble()
                ).coerceIn(0.0, 1.0)
            DecimalFormat("0.0%").format(progress)
        } else {
            template.progressLabel
        }
        val resolved = template.copy(
            chapterTitle = page.chapterTitle,
            pageLabel = pageLabel,
            progressLabel = progressLabel,
            chapterProgressLabel = chapterProgressLabel,
            chapterFirstPage = safePageIndex == 0
        )
        return resolved.copy(
            headerLeft = text(config.headerLeft, resolved),
            headerCenter = text(config.headerCenter, resolved),
            headerRight = text(config.headerRight, resolved),
            footerLeft = text(config.footerLeft, resolved),
            footerCenter = text(config.footerCenter, resolved),
            footerRight = text(config.footerRight, resolved)
        )
    }

    private fun text(field: EpubReaderChromeField, data: EpubReaderChromeData): String {
        return when (field) {
            EpubReaderChromeField.NONE -> ""
            EpubReaderChromeField.BOOK_NAME -> data.bookName
            EpubReaderChromeField.CHAPTER_TITLE -> data.chapterTitle
            EpubReaderChromeField.TIME -> data.timeLabel
            EpubReaderChromeField.BATTERY -> data.batteryLabel
            EpubReaderChromeField.BATTERY_PERCENTAGE -> data.batteryPercentageLabel
            EpubReaderChromeField.PAGE -> data.pageLabel
            EpubReaderChromeField.TOTAL_PROGRESS -> data.progressLabel
            EpubReaderChromeField.CHAPTER_PROGRESS -> data.chapterProgressLabel
            EpubReaderChromeField.PAGE_AND_TOTAL -> join(data.pageLabel, data.progressLabel)
            EpubReaderChromeField.TIME_BATTERY -> join(data.timeLabel, data.batteryLabel)
            EpubReaderChromeField.TIME_BATTERY_PERCENTAGE ->
                join(data.timeLabel, data.batteryPercentageLabel)
        }
    }

    private fun join(first: String, second: String): String {
        return listOf(first, second).filter(String::isNotBlank).joinToString(" ")
    }
}