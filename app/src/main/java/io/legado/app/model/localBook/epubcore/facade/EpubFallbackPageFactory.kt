package io.legado.app.model.localBook.epubcore.facade

import android.graphics.RectF
import android.text.TextPaint
import io.legado.app.model.localBook.epubcore.direct.TextReaderDocument
import io.legado.app.model.localBook.epubcore.direct.TextReaderImage
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import io.legado.app.model.localBook.epubcore.layout.EpubCorePage
import io.legado.app.model.localBook.epubcore.layout.EpubImageFragment
import io.legado.app.model.localBook.epubcore.layout.EpubMeasuredTextFragment
import io.legado.app.model.localBook.epubcore.layout.EpubMeasuredTextKind
import io.legado.app.model.localBook.epubcore.layout.EpubPageFragment
import io.legado.app.model.localBook.epubcore.web.EpubResourceHref
import java.util.Locale

/**
 * 降级页工厂（阶段 1.6 骨架）。
 *
 * 当保真渲染链路不可用时，产出**可直接绘制**的结果，保证「损坏 EPUB 按级回退不崩溃」：
 * - CANVAS 级：归一化正文按阅读区尺寸**分页**（可翻页，见 [canvasPages]）；
 * - TEXT 级：正文纯文本（已剥离标签）单页；
 * - RAW 级：错误说明页（终态兜底，绝不抛异常）。
 *
 * 纯函数（只依赖 [EpubCoreLayoutConfig] 的数值 + Android 几何类型，无 Context/IO）⇒ JVM 可测。
 */
internal object EpubFallbackPageFactory {

    /** 正文兜底页：整章文本按阅读区尺寸给出一页（不切分，避免二次失败）。 */
    fun textPages(
        chapterIndex: Int,
        chapterHref: String,
        text: String,
        config: EpubCoreLayoutConfig
    ): List<EpubCorePage> {
        val body = text.trim()
        if (body.isEmpty()) return rawPages(chapterIndex, chapterHref, EmptyContentMessage, config)
        val frame = contentFrame(config)
        return listOf(
            EpubCorePage(
                chapterIndex = chapterIndex,
                chapterHref = chapterHref,
                pageIndex = 0,
                totalPagesInChapter = 1,
                text = body,
                fragments = listOf(
                    EpubMeasuredTextFragment(
                        text = body,
                        frame = frame,
                        source = null,
                        kind = EpubMeasuredTextKind.Text,
                        fontSizePx = config.textPaint.textSize,
                        lineHeightPx = config.lineHeightPx,
                        letterSpacingPx = config.textPaint.letterSpacing
                    )
                ),
                start = null,
                end = null
            )
        )
    }

    /**
     * CANVAS 级兜底页：把归一化正文（[TextReaderDocument] 同一模型）按阅读区尺寸切成**多页**。
     *
     * 与 [textPages] 的差别是「分页 + 保留图片块」—— TEXT 级把整章挤成一页且只留纯文本
     * （超出部分被裁掉、无法翻页），本级别按行高/图块高度把内容铺到多页
     * ⇒ WEB_LAYOUT 失败时应先经本级别再下探 TEXT
     * （见 [EpubRenderFallbackPolicy.nextAfter]：`WEB_LAYOUT --LAYOUT_ERROR--> CANVAS_PAGE --> TEXT`）。
     *
     * 图片块的 `src` 经 [imageHref] 解析为**归档内规范路径**（与保真级共用 [EpubResourceHref] 单源口径，
     * 否则同一张图会出现「一条链路能显示、另一条恒裂图」），绘制期由 `EpubImageResolver` 按帧尺寸解码；
     * 帧高优先取 `width`/`height` 声明，缺声明时给保守默认（不依赖 IO ⇒ 保持纯函数）。
     * 正文解析超限（`ReaderDocumentLimits`）或正文为空时返回空列表，由调用方沿降级链继续下探
     * —— **本级别绝不抛异常**。
     *
     * 换行测量经 [breaker] 注入（设备侧用 [paintLineBreaker] 走真实字体，单测注入假实现）⇒ JVM 可测。
     */
    fun canvasPages(
        chapterIndex: Int,
        chapterHref: String,
        title: String,
        html: String,
        config: EpubCoreLayoutConfig,
        breaker: CanvasLineBreaker = paintLineBreaker(config.textPaint),
        imageHref: CanvasImageHrefResolver = CanvasImageHrefResolver(EpubResourceHref::resolve)
    ): List<EpubCorePage> {
        val content = runCatching { TextReaderDocument.prepare(title, html) }.getOrNull()
            ?: return emptyList()
        val blocks = content.blocks
        val heading = title.trim()
        if (heading.isEmpty() && blocks.isEmpty()) return emptyList()
        val maxWidth = config.contentWidthPx.toFloat().coerceAtLeast(1f)
        val pageHeight = config.contentHeightPx.toFloat().coerceAtLeast(1f)
        // JVM stub 下 textSize/lineHeightPx 可能为 0 ⇒ 步长兜底，避免零步长导致死循环。
        val fontPx = config.textPaint.textSize.takeIf { it.isFinite() && it > 0f } ?: 0f
        val lineHeight = config.lineHeightPx.takeIf { it.isFinite() && it > 0f } ?: 1f
        val paragraphGap = config.paragraphSpacingPx.toFloat().coerceAtLeast(0f)

        val skeletons = ArrayList<Pair<String, List<EpubPageFragment>>>()
        var fragments = ArrayList<EpubPageFragment>()
        var lines = ArrayList<String>()
        var top = 0f

        fun flush() {
            if (fragments.isEmpty()) return
            skeletons += (lines.joinToString("\n") to fragments)
            fragments = ArrayList()
            lines = ArrayList()
            top = 0f
        }

        /** 放一个占高 [height] 的片段；放不下且本页已有内容时先结算本页（空页不结算 ⇒ 超高块也能画出来）。 */
        fun place(height: Float, build: (Float) -> EpubPageFragment) {
            if (top + height > pageHeight && fragments.isNotEmpty()) flush()
            fragments += build(top)
            top += height
        }

        fun gapIfRoom() {
            if (paragraphGap > 0f && fragments.isNotEmpty() && top + paragraphGap <= pageHeight) {
                top += paragraphGap
            }
        }

        fun emitText(text: String) {
            val body = text.trim()
            if (body.isEmpty()) return
            breaker.breakLines(body, maxWidth).filter { it.isNotEmpty() }.ifEmpty { listOf(body) }
                .forEach { line ->
                    place(lineHeight) { y ->
                        EpubMeasuredTextFragment(
                            text = line,
                            frame = RectF(0f, y, maxWidth, y + lineHeight),
                            source = null,
                            kind = EpubMeasuredTextKind.Text,
                            fontSizePx = config.textPaint.textSize,
                            lineHeightPx = lineHeight,
                            letterSpacingPx = config.textPaint.letterSpacing
                        )
                    }
                    lines += line
                }
        }

        fun emitImage(image: TextReaderImage) {
            val frameWidth = (cssLengthPx(image.width, maxWidth, fontPx) ?: maxWidth).coerceIn(1f, maxWidth)
            val frameHeight = (cssLengthPx(image.height, pageHeight, fontPx) ?: (frameWidth * DefaultImageHeightRatio))
                .coerceIn(1f, pageHeight)
            place(frameHeight) { y ->
                EpubImageFragment(
                    href = imageHref.resolve(chapterHref, image.source),
                    alt = null,
                    frame = RectF(0f, y, frameWidth, y + frameHeight),
                    source = null
                )
            }
        }

        if (heading.isNotEmpty()) {
            emitText(heading)
            if (blocks.isNotEmpty()) gapIfRoom()
        }
        blocks.forEachIndexed { index, block ->
            when {
                block.image != null -> emitImage(block.image)
                block.text.isNotEmpty() -> emitText(block.text)
            }
            if (index < blocks.lastIndex) gapIfRoom()
        }
        flush()
        if (skeletons.isEmpty()) return emptyList()
        val total = skeletons.size
        return skeletons.mapIndexed { pageIndex, (text, pageFragments) ->
            EpubCorePage(
                chapterIndex = chapterIndex,
                chapterHref = chapterHref,
                pageIndex = pageIndex,
                totalPagesInChapter = total,
                text = text,
                fragments = pageFragments,
                start = null,
                end = null
            )
        }
    }

    /** 换行测量 seam：把一段文本按可用宽度折成若干行（JVM 单测注入假实现）。 */
    fun interface CanvasLineBreaker {
        fun breakLines(text: String, maxWidthPx: Float): List<String>
    }

    /** 图片引用解析 seam：章节内引用 → 归档内规范路径（默认走 [EpubResourceHref] 单源口径）。 */
    fun interface CanvasImageHrefResolver {
        fun resolve(chapterHref: String, src: String): String
    }

    /** 设备实现：用阅读字体真实测量换行（[TextPaint.breakText]）。 */
    fun paintLineBreaker(paint: TextPaint): CanvasLineBreaker = CanvasLineBreaker { text, maxWidthPx ->
        if (!maxWidthPx.isFinite() || maxWidthPx <= 0f || text.isEmpty()) {
            listOf(text)
        } else {
            val lines = ArrayList<String>()
            var start = 0
            while (start < text.length) {
                val count = paint.breakText(text, start, text.length, true, maxWidthPx, null)
                // 极窄可用宽度下 breakText 可能返回 0 ⇒ 至少推进 1 字，杜绝死循环。
                val size = count.takeIf { it > 0 } ?: 1
                lines += text.substring(start, start + size)
                start += size
            }
            lines.ifEmpty { listOf(text) }
        }
    }

    /**
     * CSS 长度 → px：`px/dp/dip/sp` 记 1:1；`%` 按 [basePx] 折算；`em/rem` 按字号折算。
     * 无法解析或非正数 ⇒ null（调用方改用默认值），**不抛异常**。
     */
    private val CssLength = Regex("^([0-9]+(?:\\.[0-9]+)?)(px|dp|dip|sp|em|rem|%)?$", RegexOption.IGNORE_CASE)

    private fun cssLengthPx(value: String?, basePx: Float, fontPx: Float): Float? {
        val match = CssLength.matchEntire(value?.trim().orEmpty()) ?: return null
        val number = match.groupValues[1].toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: return null
        return when (match.groupValues[2].lowercase(Locale.ROOT)) {
            "", "px", "dp", "dip", "sp" -> number
            "%" -> basePx * number / 100f
            "em", "rem" -> fontPx * number
            else -> null
        }
    }

    /** 图片无尺寸声明时的保守帧高比（帧宽 × 该比值），避免撑破整页。 */
    private const val DefaultImageHeightRatio = 0.6f

    /** 终态兜底页：携带可诊断的原因说明，保证阅读器有内容可画、不崩溃。 */
    fun rawPages(
        chapterIndex: Int,
        chapterHref: String,
        reason: String,
        config: EpubCoreLayoutConfig
    ): List<EpubCorePage> {
        val message = reason.ifBlank { UnknownFailureMessage }
        val frame = contentFrame(config)
        return listOf(
            EpubCorePage(
                chapterIndex = chapterIndex,
                chapterHref = chapterHref,
                pageIndex = 0,
                totalPagesInChapter = 1,
                text = message,
                fragments = listOf(
                    EpubMeasuredTextFragment(
                        text = message,
                        frame = frame,
                        source = null,
                        kind = EpubMeasuredTextKind.Text,
                        fontSizePx = config.textPaint.textSize,
                        lineHeightPx = config.lineHeightPx,
                        letterSpacingPx = config.textPaint.letterSpacing
                    )
                ),
                start = null,
                end = null
            )
        )
    }

    private fun contentFrame(config: EpubCoreLayoutConfig): RectF {
        return RectF(
            0f,
            0f,
            config.contentWidthPx.toFloat(),
            config.contentHeightPx.toFloat()
        )
    }

    private const val EmptyContentMessage = "本章内容为空或无法解析"
    private const val UnknownFailureMessage = "本章渲染失败，请尝试刷新或重新导入"
}