package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplate
import java.io.InputStream

/**
 * Direct 内容管线模型（迁移自 archive v15 `EpubDirectModels.kt`）。
 *
 * 拆分说明：archive 把 [EpubDirectSession]（会话/租约/章节缓存）也放在本文件，
 * 本仓按职责拆为独立文件 `EpubDirectSession.kt`；本文件只保留纯数据模型与常量。
 */

/**
 * 五态分类结果：决定该章节走「归一化（canvas 阅读器主题）」还是「保留出版方排版（WebView 保真）」。
 */
enum class EpubDirectLayoutMode(
    /** 单页独占视口（固定版式/媒体/交互） */
    val singlePage: Boolean,
    /** 是否按出版方视口缩放 */
    val scalesPublisherViewport: Boolean,
    /** 是否含交互（脚本/媒体） */
    val interactive: Boolean
) {
    REFLOWABLE(false, false, false),
    PUBLISHER_STYLED(false, false, false),
    FIXED(true, true, false),
    MEDIA(true, false, true),
    INTERACTIVE(true, false, true)
}

/** 一个「逻辑章节」在 Direct 管线中的完整产物。 */
data class EpubDirectChapter(
    val chapterIndex: Int,
    val href: String,
    val title: String,
    val baseUrl: String,
    val html: String,
    val plainText: String,
    val startFragmentId: String?,
    val endFragmentId: String?,
    val layoutMode: EpubDirectLayoutMode,
    val viewportWidth: Float?,
    val viewportHeight: Float?,
    val publisherOrientation: String,
    val publisherSpread: String,
    val publisherFullscreen: Boolean,
    val fullPageArtwork: Boolean,
    val implicitSinglePage: Boolean,
    val duokanGallery: Boolean,
    val scripted: Boolean,
    val pageProgressionDirection: String?,
    val pageLayoutDirection: String? = pageProgressionDirection,
    val publisherPageBackground: Boolean = false,
    val sourceChapterUrl: String? = null,
    val sourceImages: TextReaderSourceImages? = null,
    val readerTemplate: EpubReaderTemplate? = null,
    val templateSourceHtml: String? = null
)

/** 阅读位置（章节 + 页 + 字符偏移）。 */
data class EpubDirectPosition(
    val chapterIndex: Int,
    val chapterHref: String,
    val pageIndex: Int,
    val pageCount: Int,
    val progress: Float,
    val characterPosition: Int? = null
)

/** 链接目标（章号 + 片段）。 */
data class EpubDirectLinkTarget(
    val chapterIndex: Int,
    val fragmentId: String?
)

/** 虚拟主机资源响应（供 WebView 直渲染后端回源）。 */
data class EpubDirectResource(
    val mimeType: String,
    val encoding: String?,
    val statusCode: Int,
    val reasonPhrase: String,
    val headers: Map<String, String>,
    val stream: InputStream
)