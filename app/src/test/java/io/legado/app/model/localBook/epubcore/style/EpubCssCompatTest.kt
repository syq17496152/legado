package io.legado.app.model.localBook.epubcore.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSS 旧/私有属性兼容层单测（epub-md-rich-rendering 阶段 1.5）。
 *
 * 覆盖：①私有/前缀别名归一为规范名；②未知属性被忽略（返回 null）；
 * ③前缀旧声明按基名归一后可命中支持集；④未知声明不影响同规则其它声明；
 * ⑤`@font-face` 所需的非正文属性不被 `parseDeclarations` 丢弃。
 */
class EpubCssCompatTest {

    private val supported = setOf(
        "text-indent", "writing-mode", "text-transform", "word-break", "line-break",
        "hyphens", "text-align-last", "color", "line-height", "font-size", "width"
    )

    private val isSupported: (String) -> Boolean = { it in supported }

    @Test
    fun `duokan 私有扩展归一到标准属性`() {
        val normalized = EpubCssCompat.normalizePropertyName("Duokan-Text-Indent")
        assertEquals("text-indent", normalized.canonical)
        assertTrue(normalized.aliased)
    }

    @Test
    fun `epub 与 webkit 前缀书写模式归一为 writing-mode`() {
        assertEquals("writing-mode", EpubCssCompat.normalizePropertyName("-epub-writing-mode").canonical)
        assertEquals("writing-mode", EpubCssCompat.normalizePropertyName("-webkit-writing-mode").canonical)
    }

    @Test
    fun `resolvePropertyName 对可归一属性返回规范名`() {
        assertEquals("text-indent", EpubCssCompat.resolvePropertyName("duokan-text-indent", isSupported))
        assertEquals("writing-mode", EpubCssCompat.resolvePropertyName("-epub-writing-mode", isSupported))
        assertEquals("color", EpubCssCompat.resolvePropertyName("color", isSupported))
    }

    @Test
    fun `resolvePropertyName 对未知属性返回 null 以忽略`() {
        assertNull(EpubCssCompat.resolvePropertyName("unknown-thing", isSupported))
        assertNull(EpubCssCompat.resolvePropertyName("-webkit-box-orient", isSupported))
        assertNull(EpubCssCompat.resolvePropertyName("-epub-text-emphasis", isSupported))
    }

    @Test
    fun `前缀旧声明按基名归一后仍可命中支持集`() {
        // -webkit-line-height 无别名条目，但基名 line-height 受支持 ⇒ 归一为基名。
        assertEquals("line-height", EpubCssCompat.resolvePropertyName("-webkit-line-height", isSupported))
        assertEquals("width", EpubCssCompat.resolvePropertyName("-moz-width", isSupported))
    }

    @Test
    fun `basePropertyName 剥离已知前缀且无前缀时原样返回`() {
        assertEquals("writing-mode", EpubCssCompat.basePropertyName("-epub-writing-mode"))
        assertEquals("word-break", EpubCssCompat.basePropertyName("-ms-word-break"))
        assertEquals("color", EpubCssCompat.basePropertyName("color"))
    }

    @Test
    fun `未知声明被忽略但同规则其它声明保留`() {
        val rules = EpubCss.parseRules("p { -webkit-box-orient: vertical; color: red; }")
        assertEquals(1, rules.size)
        val names = rules.single().declarations.map { it.name }
        assertTrue("color 应保留", "color" in names)
        assertFalse("未知属性应被忽略", "-webkit-box-orient" in names)
    }

    @Test
    fun `私有与前缀旧声明在 parseRules 中被归一后生效`() {
        val rules = EpubCss.parseRules("p { duokan-text-indent: 2em; -epub-writing-mode: vertical-rl; }")
        assertEquals(1, rules.size)
        val names = rules.single().declarations.map { it.name }
        assertTrue("text-indent" in names)
        assertTrue("writing-mode" in names)
    }

    @Test
    fun `parseDeclarations 不丢弃非正文属性以支持 at-font-face`() {
        val declarations = EpubCss.parseDeclarations(
            "font-family: 'MyFont'; src: url(font.ttf); unicode-range: U+0000-00FF; font-weight: 700"
        )
        val names = declarations.map { it.name }
        assertTrue("src" in names)
        assertTrue("unicode-range" in names)
        assertTrue("font-family" in names)
    }
}