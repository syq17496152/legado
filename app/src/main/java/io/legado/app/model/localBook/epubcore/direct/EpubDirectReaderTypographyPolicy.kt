package io.legado.app.model.localBook.epubcore.direct

/**
 * 出版方排版保真判定：只有当章节是「可重排纯文字」时，才允许套用阅读器排版（主题/字体/行距）；
 * 固定版式/整页图/Duokan 图库/含脚本章节一律保留出版方排版。
 *
 * 迁移自 archive v15（纯算法，未改）。
 */
internal object EpubDirectReaderTypographyPolicy {

    data class Input(
        val layoutMode: EpubDirectLayoutMode,
        val fullPageArtwork: Boolean = false,
        val implicitSinglePage: Boolean = false,
        val duokanGallery: Boolean = false,
        val scripted: Boolean = false
    )

    fun isSupported(input: Input): Boolean {
        return input.layoutMode == EpubDirectLayoutMode.REFLOWABLE &&
            !input.fullPageArtwork &&
            !input.implicitSinglePage &&
            !input.duokanGallery &&
            !input.scripted
    }
}