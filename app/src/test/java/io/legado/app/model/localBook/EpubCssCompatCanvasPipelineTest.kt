package io.legado.app.model.localBook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * canvas 管线（[EpubCss]）旧属性兼容单测（epub-md-rich-rendering 阶段 1.5 配对）。
 *
 * 覆盖：①`duokan-text-indent` 归一为 `text-indent` 且取最后一个值（多看语义）；
 * ②前缀旧声明（`-epub-writing-mode`）在支持集内归一后保留；③未知属性被忽略；
 * ④行内样式解析（`declarations`）不丢非正文属性。
 */
class EpubCssCompatCanvasPipelineTest {

    @Test
    fun `duokan-text-indent 归一为 text-indent 并取最后一个值`() {
        val declarations = EpubCss.normalizeSupportedDeclarations("duokan-text-indent: 1em 2em")
        val indent = declarations.firstOrNull { it.name == "text-indent" }
        assertEquals("2em", indent?.value)
        assertTrue("旧名不得残留", declarations.none { it.name == "duokan-text-indent" })
    }

    @Test
    fun `前缀旧声明归一后保留在支持集内`() {
        val declarations = EpubCss.normalizeSupportedDeclarations("-epub-writing-mode: vertical-rl")
        assertTrue(declarations.any { it.name == "writing-mode" && it.value == "vertical-rl" })
    }

    @Test
    fun `未知属性被忽略且不影响同规则其它声明`() {
        val declarations = EpubCss.normalizeSupportedDeclarations("color: red; -webkit-box-orient: vertical")
        assertEquals(listOf("color"), declarations.filter { it.name == "color" }.map { it.name })
        assertTrue("未知前缀属性应被忽略", declarations.none { it.name.contains("box-orient") })
    }

    @Test
    fun `declarations 保留全部可解析声明供行内样式消费`() {
        val map = EpubCss.declarations("duokan-text-indent: 2em; color: red")
        assertEquals("2em", map["text-indent"])
        assertEquals("red", map["color"])
    }
}