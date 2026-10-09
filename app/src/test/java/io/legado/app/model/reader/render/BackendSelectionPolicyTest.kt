package io.legado.app.model.reader.render

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 后端分流判据单测（epub-md-rich-rendering 阶段 2.1 配对；AD-30 统一判据）。
 *
 * 覆盖：①富渲染元素强制 WebView（防 md 落 canvas 丢 mermaid）；②显式 TEXT 引擎一键回退；
 * ③书级一致性（多数出版方控制 ⇒ 全书 WebView，防章节间样式割裂）；
 * ④可重排走 canvas 快路径；⑤出版方控制型/固定/媒体/交互走 WebView。
 */
class BackendSelectionPolicyTest {

    private fun select(
        hasRichRender: Boolean = false,
        engine: BackendSelectionPolicy.Engine = BackendSelectionPolicy.Engine.AUTO,
        mode: BackendSelectionPolicy.ContentMode = BackendSelectionPolicy.ContentMode.REFLOWABLE,
        consistency: BackendSelectionPolicy.BookConsistency? = null
    ) = BackendSelectionPolicy.select(hasRichRender, engine, mode, consistency)

    @Test
    fun `富渲染元素强制 WebView 后端`() {
        assertEquals(
            BackendSelectionPolicy.Backend.DIRECT_WEB,
            select(hasRichRender = true, engine = BackendSelectionPolicy.Engine.TEXT)
        )
        assertEquals(
            "即使内容可重排，含 mermaid 公式也必须走 WebView",
            BackendSelectionPolicy.Backend.DIRECT_WEB,
            select(hasRichRender = true)
        )
    }

    @Test
    fun `显式 TEXT 引擎一键回退到纯文本`() {
        assertEquals(
            BackendSelectionPolicy.Backend.TEXT,
            select(engine = BackendSelectionPolicy.Engine.TEXT)
        )
    }

    @Test
    fun `可重排走 canvas 快路径`() {
        assertEquals(BackendSelectionPolicy.Backend.CANVAS, select())
    }

    @Test
    fun `出版方控制型四态均走 WebView`() {
        val modes = listOf(
            BackendSelectionPolicy.ContentMode.PUBLISHER_STYLED,
            BackendSelectionPolicy.ContentMode.FIXED,
            BackendSelectionPolicy.ContentMode.MEDIA,
            BackendSelectionPolicy.ContentMode.INTERACTIVE
        )
        modes.forEach { mode ->
            assertEquals("$mode 应走 WebView", BackendSelectionPolicy.Backend.DIRECT_WEB, select(mode = mode))
        }
    }

    @Test
    fun `书级一致性在多数章节出版方控制时全书走 WebView`() {
        val mostly = BackendSelectionPolicy.BookConsistency(publisherControlledChapters = 6, totalChapters = 10)
        assertEquals(
            BackendSelectionPolicy.Backend.DIRECT_WEB,
            select(mode = BackendSelectionPolicy.ContentMode.REFLOWABLE, consistency = mostly)
        )
        val minority = BackendSelectionPolicy.BookConsistency(publisherControlledChapters = 4, totalChapters = 10)
        assertEquals(
            BackendSelectionPolicy.Backend.CANVAS,
            select(mode = BackendSelectionPolicy.ContentMode.REFLOWABLE, consistency = minority)
        )
    }

    @Test
    fun `恰好半数不构成多数`() {
        val half = BackendSelectionPolicy.BookConsistency(publisherControlledChapters = 5, totalChapters = 10)
        assertEquals(false, half.mostlyPublisherControlled)
    }

    @Test
    fun `空书一致性不触发书级切换`() {
        val empty = BackendSelectionPolicy.BookConsistency(publisherControlledChapters = 0, totalChapters = 0)
        assertEquals(false, empty.mostlyPublisherControlled)
    }

    @Test
    fun `显式 TEXT 引擎优先于书级一致性`() {
        val mostly = BackendSelectionPolicy.BookConsistency(publisherControlledChapters = 9, totalChapters = 10)
        assertEquals(
            BackendSelectionPolicy.Backend.TEXT,
            select(engine = BackendSelectionPolicy.Engine.TEXT, consistency = mostly)
        )
    }
}