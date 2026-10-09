package io.legado.app.model.localBook.epubcore.facade

import android.graphics.RectF
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import io.legado.app.model.localBook.epubcore.layout.EpubCorePage
import io.legado.app.model.localBook.epubcore.layout.EpubMeasuredTextFragment
import io.legado.app.model.localBook.epubcore.layout.EpubMeasuredTextKind

/**
 * 降级页工厂（阶段 1.6 骨架）。
 *
 * 当保真渲染链路不可用时，产出**可直接绘制**的单页结果，保证「损坏 EPUB 按级回退不崩溃」：
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