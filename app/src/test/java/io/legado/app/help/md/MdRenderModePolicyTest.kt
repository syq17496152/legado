package io.legado.app.help.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 渲染降级判据单测（epub-md-rich-rendering 阶段 3.10 配对）。
 *
 * 覆盖【验证标准】「模拟旧内核/异常不白屏不崩溃」的**决策面**：
 * ①能力齐备且需要富渲染 ⇒ RICH；②旧内核/JS 不可用 ⇒ BASIC_HTML（**不得**留空容器）；
 * ③富渲染开关关闭 ⇒ 即使能力齐备也降级；④HTML 渲染不可用 ⇒ PLAIN_TEXT 兜底；
 * ⑤三种模式的"必须按源码展示"与"用户提示"语义。
 */
class MdRenderModePolicyTest {

    private fun capability(
        modernWebView: Boolean = true,
        javaScriptUsable: Boolean = true,
        htmlRenderAvailable: Boolean = true
    ) = MdRenderModePolicy.Capability(modernWebView, javaScriptUsable, htmlRenderAvailable)

    @Test
    fun `能力齐备且需要富渲染时走 RICH`() {
        assertEquals(
            MdRenderModePolicy.Mode.RICH,
            MdRenderModePolicy.decide(capability(), richRenderWanted = true)
        )
    }

    @Test
    fun `旧内核降级到 BASIC_HTML`() {
        assertEquals(
            MdRenderModePolicy.Mode.BASIC_HTML,
            MdRenderModePolicy.decide(capability(modernWebView = false), richRenderWanted = true)
        )
    }

    @Test
    fun `JS 不可用降级到 BASIC_HTML`() {
        assertEquals(
            MdRenderModePolicy.Mode.BASIC_HTML,
            MdRenderModePolicy.decide(capability(javaScriptUsable = false), richRenderWanted = true)
        )
    }

    @Test
    fun `富渲染开关关闭时即使能力齐备也不注入`() {
        assertEquals(
            MdRenderModePolicy.Mode.BASIC_HTML,
            MdRenderModePolicy.decide(capability(), richRenderWanted = true, richRenderEnabled = false)
        )
    }

    @Test
    fun `内容无富渲染元素时无需 RICH`() {
        assertEquals(
            MdRenderModePolicy.Mode.BASIC_HTML,
            MdRenderModePolicy.decide(capability(), richRenderWanted = false)
        )
    }

    @Test
    fun `HTML 渲染不可用时退到纯文本兜底`() {
        assertEquals(
            MdRenderModePolicy.Mode.PLAIN_TEXT,
            MdRenderModePolicy.decide(
                capability(modernWebView = false, htmlRenderAvailable = false),
                richRenderWanted = true
            )
        )
    }

    @Test
    fun `非 RICH 模式必须保留源码展示以防白屏`() {
        assertFalse(MdRenderModePolicy.requiresCodeBlockFallback(MdRenderModePolicy.Mode.RICH))
        assertTrue(MdRenderModePolicy.requiresCodeBlockFallback(MdRenderModePolicy.Mode.BASIC_HTML))
        assertTrue(MdRenderModePolicy.requiresCodeBlockFallback(MdRenderModePolicy.Mode.PLAIN_TEXT))
    }

    @Test
    fun `提示只在确有富渲染需求且被降级时给出`() {
        assertEquals(
            "",
            MdRenderModePolicy.noticeFor(MdRenderModePolicy.Mode.RICH, richRenderWanted = true)
        )
        assertEquals(
            "无富渲染元素时不应打扰用户",
            "",
            MdRenderModePolicy.noticeFor(MdRenderModePolicy.Mode.BASIC_HTML, richRenderWanted = false)
        )
        assertTrue(
            MdRenderModePolicy.noticeFor(MdRenderModePolicy.Mode.BASIC_HTML, richRenderWanted = true)
                .contains("图表")
        )
        assertTrue(
            MdRenderModePolicy.noticeFor(MdRenderModePolicy.Mode.PLAIN_TEXT, richRenderWanted = true)
                .contains("纯文本")
        )
    }

    @Test
    fun `探测特性清单非空且不含重复项`() {
        val features = MdRenderModePolicy.ProbeFeatures
        assertTrue("须给宿主明确的探测清单", features.isNotEmpty())
        assertEquals("清单不得重复", features.size, features.toSet().size)
    }
}