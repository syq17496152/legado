package io.legado.app.model.localBook.epubcore.direct

import android.text.Layout
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import java.util.Locale

/**
 * 阅读器文档 CSS 生成（按职责从 `EpubDirectDocumentBuilder` 拆出，NFR-04 取能力不取结构）。
 *
 * 职责：把 [EpubCoreLayoutConfig] + 五态分类结果翻译成注入到出版方文档的 CSS 文本
 * （正文排版/分栏/固定版式缩放/媒体与交互形态/杜坎图库/页眉页脚安全区/阅读字体规则）。
 * 该对象**不修改文档结构**，只产出 CSS 字符串。
 */
internal object EpubDirectReaderCss {

    internal fun readerCss(
        config: EpubCoreLayoutConfig,
        density: Float,
        layoutMode: EpubDirectLayoutMode,
        viewport: Pair<Float, Float>?,
        fullPageArtwork: Boolean,
        implicitSinglePage: Boolean,
        duokanGallery: Boolean,
        scripted: Boolean = false,
        readerFontUrl: String?
    ): String {
        fun cssPx(value: Number): String = String.format(Locale.US, "%.3fpx", value.toFloat() / density)
        // Reader chrome is deliberately admitted only to ordinary horizontal
        // reflow.  Unsupported publisher-controlled modes retain their exact
        // previous padding contract even if a caller passes an invalid config.
        val chrome = EpubDirectReaderChromePolicy.resolve(
            requested = config.readerChrome,
            input = EpubDirectReaderChromePolicy.Input(
                layoutMode = layoutMode,
                fullPageArtwork = fullPageArtwork,
                implicitSinglePage = implicitSinglePage,
                duokanGallery = duokanGallery,
                scripted = scripted,
                scrollMode = config.scrollMode
            ),
            pageHeightPx = config.pageHeightPx
        )
        val readerContentPaddingTopPx = config.readerPaddingTopPx + chrome.reservedHeaderHeightPx
        val readerContentPaddingBottomPx = config.readerPaddingBottomPx + chrome.reservedFooterHeightPx
        val safeInsetsEnabled = layoutMode == EpubDirectLayoutMode.REFLOWABLE
        val readerContentPaddingLeftPx = config.readerPaddingLeftPx +
            if (safeInsetsEnabled) config.readerSafeInsetLeftPx else 0
        val readerContentPaddingRightPx = config.readerPaddingRightPx +
            if (safeInsetsEnabled) config.readerSafeInsetRightPx else 0
        val readerContentPaddingTopWithSafeInsetPx = readerContentPaddingTopPx +
            if (safeInsetsEnabled) config.readerSafeInsetTopPx else 0
        val readerContentPaddingBottomWithSafeInsetPx = readerContentPaddingBottomPx +
            if (safeInsetsEnabled) config.readerSafeInsetBottomPx else 0
        val foreground = color(config.textPaint.color)
        val background = color(config.backgroundColor)
        val selectionCss = "::selection{background-color:${color(config.selectionColor)}!important;}"
        val fontSize = cssPx(config.textPaint.textSize)
        val lineHeight = cssPx(config.lineHeightPx.coerceAtLeast(0f))
        val contentWidth = cssPx(
            (config.pageWidthPx - readerContentPaddingLeftPx - readerContentPaddingRightPx)
                .coerceAtLeast(1)
        )
        val contentHeight = cssPx(
            (config.pageHeightPx - readerContentPaddingTopWithSafeInsetPx - readerContentPaddingBottomWithSafeInsetPx)
                .coerceAtLeast(1)
        )
        val paragraphIndent = "text-indent:${cssPx(config.paragraphIndentPx.coerceAtLeast(0f))}!important;"
        val paragraphPagination = if (config.scrollMode) {
            ""
        } else {
            "orphans:1!important;widows:1!important;break-inside:auto!important;page-break-inside:auto!important;"
        }
        val fontStyle = if (config.textFontItalic) "italic" else "normal"
        val alignment = when (config.alignment) {
            Layout.Alignment.ALIGN_CENTER -> "center"
            Layout.Alignment.ALIGN_OPPOSITE -> "end"
            else -> if (config.textFullJustify) "justify" else "start"
        }
        val readerFontRules = readerFontRules(
            config.readerFontFamily,
            config.readerFontOverridePublisher
        )
        val fontFace = readerFontUrl?.takeIf { it.isNotBlank() }?.let {
            "@font-face{font-family:'legado-reader-font';src:url('${it.replace("'", "%27")}');font-display:block;}"
        }.orEmpty()
        val overlayCss = """
            #legado-epub-image-overlay{position:fixed!important;inset:0!important;z-index:2147483646!important;box-sizing:border-box!important;display:none!important;align-items:center!important;justify-content:center!important;width:100vw!important;height:100vh!important;margin:0!important;padding:16px!important;background:rgba(0,0,0,.96)!important;touch-action:none!important;}
            #legado-epub-image-overlay.legado-visible{display:flex!important;}
            #legado-epub-image-overlay>img{display:block!important;width:auto!important;height:auto!important;max-width:calc(100vw - 32px)!important;max-height:calc(100vh - 32px)!important;object-fit:contain!important;transform-origin:center center!important;user-select:none!important;-webkit-user-drag:none!important;}
        """.trimIndent()
        if (layoutMode == EpubDirectLayoutMode.MEDIA) {
            return """
                $fontFace
                :root{color-scheme:light dark;--legado-fg:$foreground;--legado-bg:$background;}
                ${readerFontRules.inherited}
                html,body{box-sizing:border-box!important;margin:0!important;width:100vw!important;height:100vh!important;overflow:hidden!important;}
                html{padding:0!important;overscroll-behavior:none;}
                body{padding:${cssPx(config.readerPaddingTopPx)} ${cssPx(config.readerPaddingRightPx)} ${cssPx(config.readerPaddingBottomPx)} ${cssPx(config.readerPaddingLeftPx)}!important;display:flex!important;flex-direction:column!important;align-items:center!important;justify-content:center!important;color:$foreground!important;font-size:$fontSize;line-height:$lineHeight;}
                body>h1,body>h2,body>h3,body>h4,body>h5,body>h6{flex:0 0 auto!important;margin:0 0 1em!important;}
                body>img,body>svg,body>video,body>audio,body>canvas{display:block!important;flex:0 1 auto!important;width:auto!important;height:auto!important;max-width:100%!important;max-height:calc(100% - 4em)!important;object-fit:contain!important;}
                body>video,body>audio{width:100%!important;}
                $selectionCss
                $overlayCss
            """.trimIndent()
        }
        if (layoutMode == EpubDirectLayoutMode.PUBLISHER_STYLED) {
            val pageWidth = cssPx(config.pageWidthPx)
            val pageHeight = cssPx(config.pageHeightPx)
            val bodyFlowCss = publisherStyledBodyFlowCss(pageWidth, pageHeight)
            return """
                $fontFace
                ${readerFontRules.publisherOverride}
                ${readerFontRules.inherited}
                html{box-sizing:border-box!important;margin:0!important;padding:0!important;width:$pageWidth!important;height:$pageHeight!important;overflow:hidden!important;overscroll-behavior:none;}
                body{box-sizing:border-box!important;margin:0!important;$bodyFlowCss}
                img,svg,video,canvas{max-width:100%;height:auto;}
                $selectionCss
                $overlayCss
            """.trimIndent()
        }
        if (layoutMode == EpubDirectLayoutMode.INTERACTIVE && duokanGallery) {
            return """
                $fontFace
                ${readerFontRules.publisherOverride}
                ${readerFontRules.inherited}
                :root{color-scheme:dark;--legado-bg:#000;}
                html,body{box-sizing:border-box!important;margin:0!important;padding:0!important;width:100vw!important;height:100vh!important;overflow:hidden!important;background:#000!important;color:#fff;overscroll-behavior:none;}
                body>:not(.duokan-image-gallery):not(#legado-epub-image-overlay){display:none!important;}
                .duokan-image-gallery{position:absolute!important;inset:0!important;box-sizing:border-box!important;display:flex!important;width:100%!important;height:100%!important;max-width:none!important;margin:0!important;padding:0!important;overflow-x:auto!important;overflow-y:hidden!important;gap:0!important;scroll-snap-type:x mandatory;scroll-behavior:smooth;overscroll-behavior-x:contain;touch-action:pan-x;-webkit-overflow-scrolling:touch;scrollbar-width:none;}
                .duokan-image-gallery::-webkit-scrollbar{display:none;}
                .duokan-image-gallery-cell{box-sizing:border-box!important;display:flex!important;flex:0 0 100%!important;flex-direction:column!important;align-items:center!important;justify-content:center!important;width:100%!important;height:100%!important;max-width:none!important;margin:0!important;padding:20px 0!important;border:0!important;box-shadow:none!important;overflow:hidden!important;scroll-snap-align:center;scroll-snap-stop:always;}
                .duokan-image-gallery-cell img{display:block!important;flex:0 1 auto!important;width:auto!important;height:auto!important;max-width:100%!important;max-height:72%!important;margin:auto 0 0!important;object-fit:contain!important;cursor:zoom-in;user-select:none!important;-webkit-user-drag:none!important;}
                .duokan-image-maintitle,.duokan-image-subtitle{box-sizing:border-box!important;flex:0 0 auto!important;width:100%!important;max-width:100%!important;padding:0 14px!important;color:#fff!important;text-align:center!important;text-indent:0!important;}
                .duokan-image-maintitle{margin:auto 0 0!important;font-size:1em!important;line-height:1.35!important;}
                .duokan-image-subtitle{margin:.65em 0 0!important;font-size:.78em!important;line-height:1.35!important;opacity:.82;}
                $overlayCss
            """.trimIndent()
        }
        if (layoutMode == EpubDirectLayoutMode.INTERACTIVE) {
            return """
                $fontFace
                ${readerFontRules.publisherOverride}
                ${readerFontRules.inherited}
                :root{color-scheme:light dark;--legado-bg:$background;}
                html{box-sizing:border-box!important;margin:0!important;padding:0!important;width:100vw!important;height:100vh!important;overflow:hidden!important;overscroll-behavior:none;}
                *,*::before,*::after{box-sizing:inherit;}
                body{box-sizing:border-box!important;min-width:100%!important;min-height:100%!important;margin:0!important;overflow:auto!important;-webkit-overflow-scrolling:touch;}
                iframe,object,embed{max-width:100%;max-height:100%;}
                $selectionCss
                $overlayCss
            """.trimIndent()
        }
        val common = """
            $fontFace
            ${readerFontRules.publisherOverride}
            :root{color-scheme:light dark;--legado-fg:$foreground;--legado-bg:$background;}
            ${readerFontRules.inherited}
            html{box-sizing:border-box;margin:0!important;padding:0!important;overscroll-behavior:none;}
            body{--legado-line-height-base:$lineHeight;--legado-highlight-room-left:${cssPx(readerContentPaddingLeftPx)};--legado-highlight-room-right:${cssPx(readerContentPaddingRightPx)};box-sizing:border-box;color:$foreground!important;font-size:$fontSize;line-height:$lineHeight;font-weight:${config.textFontWeight};font-style:$fontStyle;letter-spacing:${config.textPaint.letterSpacing}em;text-align:$alignment;overflow-wrap:break-word;-webkit-text-size-adjust:none;}
            :where(p,li,blockquote,div){overflow-wrap:break-word;}
            :where(p){margin-top:0;margin-bottom:${cssPx(config.paragraphSpacingPx)}}
            p[data-legado-reader-paragraph]{line-height:var(--legado-line-grid,var(--legado-line-height-base))!important;$paragraphIndent$paragraphPagination}
            p[data-legado-page-gap]{margin-top:var(--legado-page-gap)!important;}
            body[data-legado-text-reader] p.reader-paragraph{$paragraphIndent}
            :where(img:not(.emoji):not(.emoji1),video,canvas,object){max-width:100%;height:auto;}
            :where(svg){max-width:100%;}
            :where(table){max-width:100%;border-collapse:collapse;}
            :where(th,td){max-width:$contentWidth;overflow-wrap:anywhere;}
            :where(pre,code){white-space:pre-wrap;overflow-wrap:anywhere;}
            :where(iframe,object,embed){max-width:100%;max-height:$contentHeight;}
            :where(img:not(.emoji):not(.emoji1),svg,video,canvas){max-height:$contentHeight;object-fit:contain;break-inside:avoid;page-break-inside:avoid;}
            :where(figure,picture){max-width:100%;height:auto;break-inside:avoid;page-break-inside:avoid;}
            :where(figure){margin-left:auto;margin-right:auto;}
            .duokan-image-gallery{display:flex!important;max-width:100%!important;overflow-x:auto!important;overflow-y:hidden!important;gap:0!important;scroll-snap-type:x mandatory;scroll-behavior:smooth;overscroll-behavior-x:contain;touch-action:pan-x;-webkit-overflow-scrolling:touch;}
            .duokan-image-gallery-cell{box-sizing:border-box;flex:0 0 100%!important;width:100%!important;max-width:100%!important;margin-left:0!important;margin-right:0!important;scroll-snap-align:center;scroll-snap-stop:always;break-inside:avoid;page-break-inside:avoid;}
            .duokan-image-gallery-cell img{cursor:zoom-in;}
            ruby{ruby-position:over;}
            $selectionCss
            $overlayCss
        """.trimIndent()
        if (layoutMode.scalesPublisherViewport) {
            val sourceWidth = viewport?.first?.takeIf { it > 0f } ?: config.pageWidthPx / density
            val sourceHeight = viewport?.second?.takeIf { it > 0f } ?: config.pageHeightPx / density
            val artworkCss = if (fullPageArtwork) {
                "body>:only-child,body>:only-child>:only-child{box-sizing:border-box!important;width:100%!important;height:100%!important;margin:0!important;}body img,body svg,body video,body canvas{display:block!important;width:100%!important;height:100%!important;max-width:100%!important;max-height:100%!important;object-fit:contain!important;}"
            } else {
                ""
            }
            val centeredCanvasCss = if (implicitSinglePage) {
                "body{display:flex!important;flex-direction:column!important;align-items:center!important;justify-content:center!important;}body>:only-child{max-width:100%!important;margin:0 auto!important;transform-origin:50% 50%!important;}"
            } else {
                ""
            }
            return """
                $fontFace
                ${readerFontRules.publisherOverride}
                ${readerFontRules.inherited}
                :root{color-scheme:light dark;--legado-bg:$background;}
                html{width:100vw!important;height:100vh!important;overflow:hidden!important;}
                html{box-sizing:border-box;margin:0!important;padding:0!important;overscroll-behavior:none;}
                body{box-sizing:border-box;position:absolute!important;left:50%!important;top:50%!important;width:${sourceWidth}px!important;height:${sourceHeight}px!important;margin:${-sourceHeight / 2f}px 0 0 ${-sourceWidth / 2f}px!important;padding:0!important;overflow:hidden!important;transform:scale(var(--legado-fixed-scale,1));transform-origin:50% 50%;}
                $artworkCss
                $centeredCanvasCss
                $selectionCss
                $overlayCss
            """.trimIndent()
        }
        if (layoutMode.singlePage) {
            return common + """
                html{width:100%!important;height:100%!important;overflow:hidden!important;}
                body{width:100%!important;min-height:100%!important;height:100%!important;margin:0!important;padding:${cssPx(readerContentPaddingTopWithSafeInsetPx)} ${cssPx(readerContentPaddingRightPx)} ${cssPx(readerContentPaddingBottomWithSafeInsetPx)} ${cssPx(readerContentPaddingLeftPx)}!important;overflow:auto!important;}
                img,svg,video,canvas,object{max-width:100%!important;max-height:100%!important;object-fit:contain;}
            """.trimIndent()
        }
        if (config.scrollMode) {
            return common + """
                html,body{width:100%!important;min-height:100%!important;overflow-x:hidden!important;}
                body{margin:0!important;padding:${cssPx(readerContentPaddingTopWithSafeInsetPx)} ${cssPx(readerContentPaddingRightPx)} ${cssPx(readerContentPaddingBottomWithSafeInsetPx)} ${cssPx(readerContentPaddingLeftPx)}!important;}
            """.trimIndent()
        }
        val horizontalGap = cssPx(readerContentPaddingLeftPx + readerContentPaddingRightPx)
        return common + """
            html{width:100vw!important;height:100vh!important;overflow:hidden!important;}
            body{width:100vw!important;height:100vh!important;margin:0!important;padding:${cssPx(readerContentPaddingTopWithSafeInsetPx)} ${cssPx(readerContentPaddingRightPx)} ${cssPx(readerContentPaddingBottomWithSafeInsetPx)} ${cssPx(readerContentPaddingLeftPx)}!important;overflow:visible!important;column-width:$contentWidth!important;column-gap:$horizontalGap!important;column-fill:auto!important;transform-origin:left top!important;}
        """.trimIndent()
    }

    internal fun publisherStyledBodyFlowCss(pageWidth: String, pageHeight: String): String {
        return "width:$pageWidth!important;height:$pageHeight!important;overflow:visible!important;" +
            "-webkit-column-width:$pageWidth;column-width:$pageWidth;" +
            "-webkit-column-gap:0;column-gap:0;" +
            "-webkit-column-fill:auto;column-fill:auto;"
    }

    internal data class ReaderFontRules(
        val inherited: String,
        val publisherOverride: String
    )

    internal fun readerFontRules(
        readerFontFamily: String?,
        overridePublisher: Boolean
    ): ReaderFontRules {
        val escapedFamily = readerFontFamily
            ?.takeIf { it.isNotBlank() }
            ?.replace("\\", "\\\\")
            ?.replace("'", "\\'")
            ?.let { "'$it'" }
            ?: return ReaderFontRules("", "")
        return ReaderFontRules(
            inherited = ":where(html){font-family:$escapedFamily,sans-serif;}",
            publisherOverride = if (overridePublisher) {
                "body,body *{font-family:$escapedFamily,sans-serif!important;}"
            } else {
                ""
            }
        )
    }

    internal fun readerFontResourceUrl(
        resourceHost: String,
        readerFontUrl: String?,
        readerFontRevision: String?
    ): String? {
        if (readerFontUrl.isNullOrBlank() || readerFontRevision.isNullOrBlank()) return null
        val baseUrl = EpubDirectSession.baseUrl(READER_FONT_PATH, resourceHost)
        return "$baseUrl?v=$readerFontRevision"
    }

    private fun color(value: Int): String = String.format(
        Locale.US,
        "rgba(%d,%d,%d,%.3f)",
        (value ushr 16) and 0xff,
        (value ushr 8) and 0xff,
        value and 0xff,
        ((value ushr 24) and 0xff) / 255f
    )

    private const val READER_FONT_PATH = "__legado_reader_font__"
}
