package io.legado.app.ui.book.read.textweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本富渲染文档组装契约测试（epub-md-rich-rendering 阶段 3.1/3.2 配对）。
 *
 * 重点守护"静默失效"类缺陷：主题变量选择器特异性不足会被 md-reader.css 的 `:root` 压掉
 * （表现为"用户改字号/夜间主题没反应"），无效数值被注入会破坏正文可见性。
 */
class TextRichHtmlComposerTest {

    private val body = "<h1 class=\"reader-chapter-title\">标题</h1><p class=\"reader-paragraph\">正文</p>"

    @Test
    fun `文档结构完整且正文置于固定容器`() {
        val html = TextRichHtmlComposer.compose(
            bodyHtml = body,
            readerCss = ".reader-paragraph{margin:0}",
            richInjectionHtml = "",
            theme = TextRichHtmlComposer.Theme(dark = false)
        )
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue("必须声明编码", html.contains("<meta charset=\"utf-8\">"))
        assertTrue("正文容器须可被脚本定位", html.contains("id=\"${TextRichHtmlComposer.BodyElementId}\""))
        assertTrue("阅读样式须在场", html.contains("legado-md-reader-css"))
        assertTrue("正文须在容器内", html.contains(body))
        assertTrue(html.trimEnd().endsWith("</html>"))
    }

    @Test
    fun `昼夜由 data-md-theme 属性驱动而非重写色值`() {
        val day = TextRichHtmlComposer.compose(body, "", "", TextRichHtmlComposer.Theme(dark = false))
        val night = TextRichHtmlComposer.compose(body, "", "", TextRichHtmlComposer.Theme(dark = true))
        assertTrue(day.contains("data-md-theme=\"day\""))
        assertTrue(night.contains("data-md-theme=\"night\""))
        // 色值单一来源在 md-reader.css；本层不得硬编码具体色值（否则昼夜会出现两套色）
        assertFalse("不得硬编码正文色值", night.contains("#121212"))
    }

    @Test
    fun `主题变量选择器特异性必须高于 md-reader_css 的 root`() {
        val block = TextRichHtmlComposer.themeStyleBlock(
            TextRichHtmlComposer.Theme(dark = false, fontSizePx = 21f, lineHeight = 1.9f)
        )
        // :root 特异性 (0,1,0) 高于 html (0,0,1)：若写成 html{...} 会覆盖不掉 md-reader.css 的 :root
        assertTrue("必须用属性选择器提升特异性", block.contains("html[data-md-theme]"))
        assertTrue(block.contains("--md-font-size:21px"))
        assertTrue(block.contains("--md-line-height:1.9"))
    }

    @Test
    fun `无效数值不写入避免正文不可见`() {
        var block = TextRichHtmlComposer.themeStyleBlock(
            TextRichHtmlComposer.Theme(dark = false, fontSizePx = 0f, lineHeight = -1f)
        )
        assertEquals("全无效时应不产出样式块", "", block)
        block = TextRichHtmlComposer.themeStyleBlock(
            TextRichHtmlComposer.Theme(dark = false, fontSizePx = Float.NaN, lineHeight = 1.8f)
        )
        assertFalse("NaN 字号不得写入", block.contains("--md-font-size"))
        assertTrue("有效行高仍应写入", block.contains("--md-line-height:1.8"))
    }

    @Test
    fun `字号小数取整到一位避免脏值`() {
        val block = TextRichHtmlComposer.themeStyleBlock(
            TextRichHtmlComposer.Theme(dark = false, fontSizePx = 17.999999f, lineHeight = 1.77777f)
        )
        assertTrue(block.contains("--md-font-size:18px"))
        assertTrue(block.contains("--md-line-height:1.8"))
        assertFalse(block.contains("17.999"))
    }

    @Test
    fun `字体族仅接受常规字体栈 拒绝声明逃逸`() {
        assertTrue(TextRichHtmlComposer.isSafeFontFamily("-apple-system, \"Noto Sans CJK SC\", sans-serif"))
        assertTrue(TextRichHtmlComposer.isSafeFontFamily("思源宋体"))
        assertFalse("分号会造成声明逃逸", TextRichHtmlComposer.isSafeFontFamily("serif;}html{display:none"))
        assertFalse("花括号同样逃逸", TextRichHtmlComposer.isSafeFontFamily("serif}"))
        assertFalse("空值不接受", TextRichHtmlComposer.isSafeFontFamily("   "))
        assertFalse("超长串不接受", TextRichHtmlComposer.isSafeFontFamily("a".repeat(TextRichHtmlComposer.MaxFontFamilyChars + 1)))
    }

    @Test
    fun `不安全字体族不写入文档`() {
        val block = TextRichHtmlComposer.themeStyleBlock(
            TextRichHtmlComposer.Theme(dark = false, fontFamily = "x;}body{display:none")
        )
        assertFalse(block.contains("display:none"))
        assertFalse(block.contains("--md-font-family"))
    }

    @Test
    fun `无富渲染注入时不产出空标签`() {
        val html = TextRichHtmlComposer.compose(body, "", "", TextRichHtmlComposer.Theme(dark = false))
        assertFalse("空注入不得留下空 script 标签", html.contains("legado-md-rich-render"))
        assertFalse("无阅读样式时不留空 style", html.contains("legado-md-reader-css"))
    }

    @Test
    fun `富渲染注入片段原样置入且位于正文之前`() {
        val injection = "<script id=\"legado-md-rich-render\">(function(){})();</script>"
        val html = TextRichHtmlComposer.compose(body, "", injection, TextRichHtmlComposer.Theme(dark = true))
        assertTrue(html.contains(injection))
        assertTrue(
            "注入须在正文之前（正文渲染前运行时已就绪）",
            html.indexOf(injection) < html.indexOf(body)
        )
    }
}