package io.legado.app.model.localBook.epubcore.facade

import io.legado.app.constant.AppLog
import io.legado.app.model.localBook.epubcore.direct.EpubDirectContentClassifier
import io.legado.app.model.localBook.epubcore.direct.EpubDirectDocumentBuilder
import io.legado.app.model.localBook.epubcore.direct.EpubDirectLayoutMode
import io.legado.app.model.localBook.epubcore.direct.EpubDirectPublisherCss
import io.legado.app.model.localBook.epubcore.direct.EpubReaderContentMode
import io.legado.app.model.localBook.epubcore.direct.EpubReaderContentModePolicy

/**
 * 章节「五态分类 → 渲染参数」解析器（纯函数，JVM 可测）。
 *
 * 从 [EpubCoreFacade] 抽出，使判据可单测；`facade` 只负责缓存与调色板等宿主状态。
 * 判定证据（AD-06）：出版方声明（rendition / spine properties）+ DOM 结构 + screen 相关出版方 CSS。
 * 任何一步失败都退化为 [EpubDirectLayoutMode.REFLOWABLE]（本版前行为），**不阻断阅读**。
 */
internal object EpubChapterContentModeResolver {

    /** 样式表加载器：`(archive 内路径, 上限字节) -> 原始字节`；失败返回 null。 */
    fun interface StylesheetLoader {
        fun load(path: String, maxBytes: Long): ByteArray?
    }

    fun resolve(
        chapterHref: String,
        chapterHtml: String,
        renditionLayout: String?,
        spineProperties: Set<String>,
        manifestProperties: Set<String>,
        mediaType: String?,
        packageViewportWidth: Float?,
        packageViewportHeight: Float?,
        resourceHost: String,
        loadStylesheet: StylesheetLoader
    ): EpubReaderContentMode {
        val publisherCss = runCatching {
            EpubDirectPublisherCss.collectForClassification(
                sourceHtml = chapterHtml,
                chapterHref = chapterHref,
                resourceHost = resourceHost,
                load = loadStylesheet::load
            )
        }.getOrElse {
            AppLog.putDebug("EPUB content mode publisher css failed: ${it.localizedMessage}")
            ""
        }
        val profile = runCatching {
            EpubDirectContentClassifier.analyze(
                renditionLayout = renditionLayout,
                spineProperties = spineProperties,
                manifestProperties = manifestProperties,
                mediaType = mediaType,
                sourceHtml = chapterHtml,
                packageViewportWidth = packageViewportWidth,
                packageViewportHeight = packageViewportHeight,
                publisherCss = publisherCss
            )
        }.getOrNull() ?: return EpubReaderContentModePolicy.resolve(EpubDirectLayoutMode.REFLOWABLE)
        val writingMode = runCatching {
            EpubDirectDocumentBuilder.resolveRootWritingMode(chapterHtml)
        }.getOrNull()
        return EpubReaderContentModePolicy.resolve(
            layoutMode = profile.layoutMode,
            publisherViewportWidth = profile.viewportWidth ?: packageViewportWidth,
            publisherViewportHeight = profile.viewportHeight ?: packageViewportHeight,
            writingMode = writingMode
        )
    }
}