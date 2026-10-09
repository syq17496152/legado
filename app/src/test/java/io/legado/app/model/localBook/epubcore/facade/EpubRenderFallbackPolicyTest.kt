package io.legado.app.model.localBook.epubcore.facade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染多级回退判据单测（epub-md-rich-rendering 阶段 1.6 / 台账 C1 / 缺点 X7）。
 *
 * 覆盖：①首选级由内容态决定（保真→WEB，可重排→CANVAS 快路径）；
 * ②引擎不可用/结构损坏跳过 WEB 直落 TEXT；③逐级下探至 RAW 终态；④降级链与"是否还有下级"。
 */
class EpubRenderFallbackPolicyTest {

    @Test
    fun `首选级由内容态决定`() {
        assertEquals(
            EpubRenderFallbackPolicy.Stage.WEB_LAYOUT,
            EpubRenderFallbackPolicy.initialStage(prefersPublisherLayout = true)
        )
        assertEquals(
            "可重排走 canvas 快路径（AD-15）",
            EpubRenderFallbackPolicy.Stage.CANVAS_PAGE,
            EpubRenderFallbackPolicy.initialStage(prefersPublisherLayout = false)
        )
    }

    @Test
    fun `引擎不可用或结构损坏时跳过保真级直落纯文本`() {
        assertEquals(
            EpubRenderFallbackPolicy.Stage.TEXT,
            EpubRenderFallbackPolicy.nextAfter(
                EpubRenderFallbackPolicy.Stage.WEB_LAYOUT,
                EpubRenderFallbackPolicy.Failure.ENGINE_UNAVAILABLE
            )
        )
        assertEquals(
            EpubRenderFallbackPolicy.Stage.TEXT,
            EpubRenderFallbackPolicy.nextAfter(
                EpubRenderFallbackPolicy.Stage.WEB_LAYOUT,
                EpubRenderFallbackPolicy.Failure.MALFORMED_CONTENT
            )
        )
    }

    @Test
    fun `普通布局失败先降到归一化分页`() {
        assertEquals(
            EpubRenderFallbackPolicy.Stage.CANVAS_PAGE,
            EpubRenderFallbackPolicy.nextAfter(
                EpubRenderFallbackPolicy.Stage.WEB_LAYOUT,
                EpubRenderFallbackPolicy.Failure.LAYOUT_ERROR
            )
        )
        assertEquals(
            EpubRenderFallbackPolicy.Stage.CANVAS_PAGE,
            EpubRenderFallbackPolicy.nextAfter(
                EpubRenderFallbackPolicy.Stage.WEB_LAYOUT,
                EpubRenderFallbackPolicy.Failure.EMPTY_RESULT
            )
        )
    }

    @Test
    fun `超时不再等待保真链直接降文本`() {
        assertEquals(
            EpubRenderFallbackPolicy.Stage.TEXT,
            EpubRenderFallbackPolicy.nextAfter(
                EpubRenderFallbackPolicy.Stage.WEB_LAYOUT,
                EpubRenderFallbackPolicy.Failure.TIMEOUT
            )
        )
    }

    @Test
    fun `逐级下探并以 RAW 为终态`() {
        assertEquals(
            EpubRenderFallbackPolicy.Stage.RAW,
            EpubRenderFallbackPolicy.nextAfter(
                EpubRenderFallbackPolicy.Stage.TEXT,
                EpubRenderFallbackPolicy.Failure.LAYOUT_ERROR
            )
        )
        assertNull(
            "RAW 为终态，不再下探（调用方必须产出兜底内容）",
            EpubRenderFallbackPolicy.nextAfter(
                EpubRenderFallbackPolicy.Stage.RAW,
                EpubRenderFallbackPolicy.Failure.LAYOUT_ERROR
            )
        )
    }

    @Test
    fun `完整降级链覆盖中间各级且不含起点`() {
        val chain = EpubRenderFallbackPolicy.fallbackChain(
            EpubRenderFallbackPolicy.Stage.WEB_LAYOUT,
            EpubRenderFallbackPolicy.Failure.LAYOUT_ERROR
        )
        assertEquals(
            listOf(
                EpubRenderFallbackPolicy.Stage.CANVAS_PAGE,
                EpubRenderFallbackPolicy.Stage.TEXT,
                EpubRenderFallbackPolicy.Stage.RAW
            ),
            chain
        )
    }

    @Test
    fun `hasFallback 在终态为 false`() {
        assertTrue(
            EpubRenderFallbackPolicy.hasFallback(
                EpubRenderFallbackPolicy.Stage.WEB_LAYOUT,
                EpubRenderFallbackPolicy.Failure.LAYOUT_ERROR
            )
        )
        assertFalse(
            EpubRenderFallbackPolicy.hasFallback(
                EpubRenderFallbackPolicy.Stage.RAW,
                EpubRenderFallbackPolicy.Failure.LAYOUT_ERROR
            )
        )
    }
}