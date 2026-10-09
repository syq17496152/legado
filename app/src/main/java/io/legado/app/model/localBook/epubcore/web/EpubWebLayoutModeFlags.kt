package io.legado.app.model.localBook.epubcore.web

/**
 * 五态分流 → WebView 布局开关（纯函数，JVM 可测）。
 *
 * 目的：把「保真章节不注入阅读器排版」的判据集中到一处，避免开关散落在 CSS 与 JS 两处导致失配；
 * 默认（REFLOWABLE）下所有开关与本版前行为一致 ⇒ 零回归。
 */
internal data class EpubWebLayoutModeFlags(
    val preservePublisherLayout: Boolean,
    val singlePage: Boolean,
    /** 是否应用阅读器字体（保真模式恒 false）。 */
    val applyReaderFont: Boolean,
    /** 是否应用阅读器字号/行高（保真模式与单页模式恒 false）。 */
    val applyReaderTypography: Boolean,
    /** 是否启用两端对齐弹性拉伸（保真模式不启用，尊重出版方对齐）。 */
    val allowJustifyStretch: Boolean
)

internal object EpubWebLayoutModeFlagsFactory {

    fun from(request: EpubWebLayoutRequest): EpubWebLayoutModeFlags {
        val preserve = request.preservePublisherLayout
        return EpubWebLayoutModeFlags(
            preservePublisherLayout = preserve,
            singlePage = request.singlePage,
            applyReaderFont = !preserve && request.readerFontFamily != null,
            applyReaderTypography = !preserve && !request.singlePage,
            allowJustifyStretch = !preserve && request.textFullJustify
        )
    }
}

/**
 * 阅读器布局样式构造（纯函数，JVM 可测）。
 *
 * 非保真（默认）路径：注入阅读器字体/字号/行高/颜色/两端对齐 + 正文容器去边距归一化；
 * 保真路径（PUBLISHER_STYLED/FIXED/MEDIA/INTERACTIVE）：只给出版方 DOM 一页画布尺寸与分栏容器，
 * 不触碰其字体/颜色/行高/盒模型（AD-06 的 padding 契约）。
 */
internal object EpubWebLayoutCssBuilder {

    fun build(
        request: EpubWebLayoutRequest,
        flags: EpubWebLayoutModeFlags = EpubWebLayoutModeFlagsFactory.from(request)
    ): String {
        val readerFontFace = if (flags.applyReaderFont) {
            """
            @font-face {
              font-family: ${request.readerFontFamily!!.cssString()};
              src: url('${request.readerFontUrl!!.cssUrl()}');
            }
            """.trimIndent()
        } else {
            ""
        }
        val justifyCss = if (flags.allowJustifyStretch) {
            """
              text-align: justify;
              text-align-last: auto;
              text-justify: inter-character;
            """.trimIndent()
        } else {
            ""
        }
        val columnCss = if (flags.singlePage) {
            """
              -webkit-column-width: auto;
              column-width: auto;
              overflow: hidden !important;
            """.trimIndent()
        } else {
            """
              -webkit-column-width: ${request.viewportWidthPx}px;
              column-width: ${request.viewportWidthPx}px;
              -webkit-column-gap: 0;
              column-gap: 0;
              -webkit-column-fill: auto;
              column-fill: auto;
            """.trimIndent()
        }
        val color = "#%06X".format(0xFFFFFF and request.textColor)
        val typographyCss = if (flags.preservePublisherLayout) {
            ""
        } else {
            """
              color: $color;
              font-size: ${request.fontSizePx}px;
              line-height: ${request.lineHeightPx}px;
              letter-spacing: ${request.letterSpacingEm}em;
            """.trimIndent()
        }
        val rootTypographyCss = if (!flags.applyReaderTypography) {
            ""
        } else {
            """
              font-size: ${request.fontSizePx}px;
              line-height: ${request.lineHeightPx}px;
            """.trimIndent()
        }
        val normalizationCss = if (flags.preservePublisherLayout) {
            ""
        } else {
            """
            body, body * {
              box-sizing: border-box;
            }
            p, li, blockquote, div {
              overflow-wrap: break-word;
              word-break: break-word;
              $justifyCss
            }
            body > article,
            body > section,
            body > main,
            body > div.book-wrapper,
            body > article.book-wrapper {
              max-width: none !important;
              width: 100% !important;
              margin-left: 0 !important;
              margin-right: 0 !important;
              padding-left: 0 !important;
              padding-right: 0 !important;
              background-color: transparent !important;
              border-left-width: 0 !important;
              border-right-width: 0 !important;
              box-shadow: none !important;
            }
            """.trimIndent()
        }
        return """
            $readerFontFace
            html {
              margin: 0 !important;
              padding: 0 !important;
              width: ${request.viewportWidthPx}px !important;
              height: ${request.viewportHeightPx}px !important;
              overflow: hidden !important;
              background: transparent !important;
              $rootTypographyCss
            }
            :root {
              $rootTypographyCss
            }
            body {
              margin: 0 !important;
              padding: 0 !important;
              width: ${request.viewportWidthPx}px !important;
              height: ${request.viewportHeightPx}px !important;
              overflow: visible !important;
              $typographyCss
              $columnCss
            }
            $normalizationCss
            img, svg, video, canvas {
              max-width: 100%;
              height: auto;
            }
        """.trimIndent()
    }

    private fun String.cssString(): String {
        return "'${replace("\\", "\\\\").replace("'", "\\'")}'"
    }

    private fun String.cssUrl(): String {
        return replace("\\", "\\\\").replace("'", "\\'")
    }
}