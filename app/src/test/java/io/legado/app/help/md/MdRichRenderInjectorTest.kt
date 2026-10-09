package io.legado.app.help.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 富渲染注入器单测（epub-md-rich-rendering 阶段 3.8/3.9 配对）。
 *
 * 覆盖【验证标准】中**可 JVM 断言**的部分：
 * ①mermaid 安全与稳定选项齐备（`startOnLoad:false` / `securityLevel:'strict'` / `htmlLabels:false` / `suppressErrors:true`）；
 * ②KaTeX 安全与限额选项齐备（`trust:false` / `strict:'warn'` / `throwOnError:false` / `maxSize` / `maxExpand`）；
 * ③按需注入（无 mermaid/公式/代码块 ⇒ 不注入对应运行时，2.4MB 不白带）；
 * ④降级分支存在（缺失/异常 ⇒ `md-rich-fallback` 外观，不中断）；
 * ⑤防二次渲染标记；⑥主题名白名单（防注入）；⑦超时兜底信号。
 * 真正的"渲染为 SVG 而非代码块"属真机 L2。
 */
class MdRichRenderInjectorTest {

    private val assets = MdRichRenderInjector.Assets(
        mermaidJs = "/*MERMAID_JS*/",
        katexJs = "/*KATEX_JS*/",
        katexCss = "/*KATEX_CSS*/",
        highlightJs = "/*HLJS*/",
        highlightCss = "/*HLJS_CSS*/"
    )

    private fun build(
        options: MdRichRenderInjector.Options = MdRichRenderInjector.Options(),
        assets: MdRichRenderInjector.Assets = this.assets,
        needsMermaid: Boolean = true,
        needsMath: Boolean = true
    ) = MdRichRenderInjector.build(options, assets, needsMermaid, needsMath)

    @Test
    fun `mermaid 运行时安全与稳定选项齐备`() {
        val script = build().script
        assertTrue("必须离线内联运行时", script.contains("/*MERMAID_JS*/"))
        assertTrue(script.contains("startOnLoad:false"))
        assertTrue(script.contains("securityLevel:'strict'"))
        assertTrue(script.contains("htmlLabels:false"))
        assertTrue("单图失败不得中断整页", script.contains("suppressErrors:true"))
        assertTrue("渲染时机须由我们控制（字体/DOM 就绪后）", script.contains("runMermaid()"))
    }

    @Test
    fun `katex 运行时安全与限额选项齐备`() {
        val script = build().script
        assertTrue(script.contains("/*KATEX_JS*/"))
        assertTrue("须拒 href 等危险命令", script.contains("trust:false"))
        assertTrue(script.contains("strict:'warn'"))
        assertTrue("错误渲染为文本而非抛异常", script.contains("throwOnError:false"))
        assertTrue(script.contains("maxSize:200"))
        assertTrue(script.contains("maxExpand:1000"))
    }

    @Test
    fun `代码高亮在就绪后执行且可选关闭`() {
        assertTrue(build().script.contains("/*HLJS*/"))
        assertTrue(build().script.contains("highlightElement"))
        val off = build(MdRichRenderInjector.Options(highlightCode = false))
        assertFalse(off.script.contains("/*HLJS*/"))
    }

    @Test
    fun `无 mermaid 标注时不注入 mermaid 运行时`() {
        val injection = build(needsMermaid = false)
        assertFalse("不得白带 2.4MB 运行时", injection.script.contains("/*MERMAID_JS*/"))
        assertFalse(injection.script.contains("runMermaid"))
    }

    @Test
    fun `无公式标注时不注入 katex 运行时`() {
        val injection = build(needsMath = false)
        assertFalse(injection.script.contains("/*KATEX_JS*/"))
        assertFalse(injection.css.contains("/*KATEX_CSS*/"))
    }

    @Test
    fun `资产缺失时按需跳过且不抛异常`() {
        val empty = MdRichRenderInjector.Assets()
        val injection = build(assets = empty)
        assertFalse(injection.script.contains("MERMAID_JS"))
        assertFalse(injection.script.contains("KATEX_JS"))
        assertTrue("无任何可注入内容时脚本为空", injection.script.isBlank())
    }

    @Test
    fun `三项全关时不产出脚本`() {
        val injection = build(
            MdRichRenderInjector.Options(renderMermaid = false, renderMath = false, highlightCode = false)
        )
        assertTrue(injection.isEmpty)
        assertTrue(MdRichRenderInjector.wrapHtml(injection).isBlank())
    }

    @Test
    fun `降级分支存在且给出可见外观`() {
        val script = build().script
        assertTrue("缺失时降级", script.contains("'mermaid-missing'"))
        assertTrue("运行异常时降级", script.contains("'mermaid-run'"))
        assertTrue(script.contains("'katex'"))
        assertTrue("降级须落到可见类名", script.contains("md-rich-fallback"))
        assertTrue("降级标记须可诊断", script.contains("data-md-fallback"))
    }

    @Test
    fun `防二次渲染标记被选择器与写入两侧同时使用`() {
        val script = build().script
        val attribute = MdRichRenderInjector.RenderedAttribute
        assertTrue("选择器须排除已渲染节点", script.contains("!n.getAttribute('$attribute')"))
        assertTrue("渲染后须打标", script.contains("setAttribute('$attribute','mermaid')"))
        assertTrue(script.contains("setAttribute('$attribute','math')"))
        assertTrue(script.contains("setAttribute('$attribute','code')"))
    }

    @Test
    fun `公式扫描跳过代码块避免误渲染`() {
        val script = build().script
        assertTrue(script.contains("tag==='code'"))
        assertTrue(script.contains("tag==='pre'"))
    }

    @Test
    fun `mermaid 主题名走白名单防注入`() {
        val injected = build(MdRichRenderInjector.Options(mermaidTheme = "';alert(1);//"))
        assertTrue(injected.script.contains("theme:'default'"))
        assertFalse(injected.script.contains("alert(1)"))

        val dark = build(MdRichRenderInjector.Options(mermaidTheme = MdRichRenderInjector.MermaidThemeDark))
        assertTrue(dark.script.contains("theme:'dark'"))
    }

    @Test
    fun `渲染完成信号与超时兜底齐备`() {
        val script = build().script
        assertTrue(script.contains("window.${MdRichRenderInjector.StatusGlobal}=status"))
        assertTrue("须有 done 标记供宿主轮询", script.contains("status.done=true"))
        assertTrue("超时也必须置完成，避免分页永久等待", script.contains("setTimeout(finish,"))
        assertTrue(script.contains("clearTimeout(timeout);finish();"))
    }

    @Test
    fun `超时阈值可按需收紧`() {
        val script = build(MdRichRenderInjector.Options(timeoutMillis = 1_500L)).script
        assertTrue(script.contains("setTimeout(finish,1500)"))
    }

    @Test
    fun `包裹后的 HTML 带稳定 id 便于宿主定位`() {
        val html = MdRichRenderInjector.wrapHtml(build())
        assertTrue(html.startsWith("<style id=\"legado-md-vendor-css\">"))
        assertTrue(html.contains("<script id=\"legado-md-rich-render\">"))
        assertTrue(html.endsWith("</script>"))
    }

    @Test
    fun `资产路径与阶段零导入的 vendor 布局一致`() {
        assertEquals("md/md-reader.css", MdRichRenderInjector.MdReaderCssAsset)
        assertEquals("md/vendor/mermaid.min.js", MdRichRenderInjector.MermaidAsset)
        assertEquals("md/vendor/katex.min.js", MdRichRenderInjector.KatexAsset)
        assertEquals("md/vendor/katex.min.css", MdRichRenderInjector.KatexCssAsset)
        assertEquals("md/vendor/highlight.min.js", MdRichRenderInjector.HighlightAsset)
        assertEquals("md/vendor/hljs-light.min.css", MdRichRenderInjector.HighlightLightCssAsset)
        assertEquals("md/vendor/hljs-dark.min.css", MdRichRenderInjector.HighlightDarkCssAsset)
    }
}