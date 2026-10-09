package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.model.localBook.epubcore.layout.EpubReaderChromeConfig

/**
 * 阅读器自有页眉/页脚「可用性闸门」。
 *
 * 仅**普通可重排章节**可用。出版方背景由文档自身保留，因此不会禁用透明的文档内 chrome；
 * 整页图与交互内容保持既有渲染路径不变。
 *
 * 迁移自 archive v15（纯算法，未改）。
 */
internal object EpubDirectReaderChromePolicy {

    data class Input(
        val layoutMode: EpubDirectLayoutMode,
        val fullPageArtwork: Boolean = false,
        val implicitSinglePage: Boolean = false,
        val duokanGallery: Boolean = false,
        val scripted: Boolean = false,
        val scrollMode: Boolean = false
    )

    fun isSupported(input: Input): Boolean {
        return input.layoutMode == EpubDirectLayoutMode.REFLOWABLE &&
            !input.fullPageArtwork &&
            !input.implicitSinglePage &&
            !input.duokanGallery &&
            !input.scripted &&
            !input.scrollMode
    }

    fun resolve(
        requested: EpubReaderChromeConfig,
        input: Input,
        pageHeightPx: Int
    ): EpubReaderChromeConfig {
        if (!requested.enabled || !isSupported(input)) return EpubReaderChromeConfig.DISABLED
        return requested.normalizedForPage(pageHeightPx)
    }
}