package io.legado.app.model.localBook.epubcore.layout

/** 渲染进固定尺寸页眉/页脚节点的「随页值」。 */
data class EpubReaderChromeData(
    val bookName: String = "",
    val chapterTitle: String = "",
    val pageLabel: String = "",
    val progressLabel: String = "",
    val chapterProgressLabel: String = "",
    val timeLabel: String = "",
    val batteryLabel: String = "",
    val batteryPercentageLabel: String = "",
    val headerLeft: String = "",
    val headerCenter: String = "",
    val headerRight: String = "",
    val footerLeft: String = "",
    val footerCenter: String = "",
    val footerRight: String = "",
    val chapterCount: Int = 0,
    val chapterFirstPage: Boolean = false,
    val contentRevision: Long = 0L
)