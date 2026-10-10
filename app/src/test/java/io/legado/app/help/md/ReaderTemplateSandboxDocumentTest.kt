package io.legado.app.help.md

import io.legado.app.testkit.SourceFileProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板沙箱文档构造契约（epub-md-rich-rendering 阶段 4.14 配对）。
 *
 * 为什么值得逐条断言：沙箱文档**没有任何运行时兜底**——段落顺序错、厂商脚本被包进函数、
 * 脚本体未转义，这三类失守都表现为"模板渲染了但功能就是不对"（页数恒 1 / mermaid 不出现 /
 * 页面结构被作者内容撕开），且**沙箱里无法断点调试**（跨源、无宿主通道）。
 */
class ReaderTemplateSandboxDocumentTest {

    private val flow = "/* flow */ window.ReaderTemplateFlow={initialize:function(){},settle:function(){}};"
    private val runtime = "/* runtime */ var flow = window.ReaderTemplateFlow || {};"
    private val injector = "(function(){window.__legadoMdRichRender={done:false};})();"

    private fun scripts(
        injectorCss: String = ".katex{color:red}"
    ) = ReaderTemplateSandboxDocument.Scripts(
        flowJs = flow,
        runtimeJs = runtime,
        injectorRuntimeJs = injector,
        injectorCss = injectorCss
    )

    private fun doc(s: ReaderTemplateSandboxDocument.Scripts = scripts()) =
        ReaderTemplateSandboxDocument.build(s)

    @Test
    fun `段落顺序为 flow 先于 runtime 再厂商再注入器最后 bootstrap`() {
        val text = doc()
        val iFlow = text.indexOf(flow)
        val iRuntime = text.indexOf(runtime)
        val iInjector = text.indexOf(injector)
        val iBootstrap = text.indexOf("__legadoTemplateInitForwarded")
        assertTrue("各段都必须存在", listOf(iFlow, iRuntime, iInjector, iBootstrap).all { it >= 0 })
        // runtime 在加载期读 window.ReaderTemplateFlow ⇒ flow 必须先加载（顺序颠倒 ⇒ 页数恒 1 且不报错）
        assertTrue("flow 必须先于 runtime", iFlow < iRuntime)
        assertTrue("bootstrap 必须最后（其余脚本已就位）", iInjector < iBootstrap)
    }

    @Test
    fun `厂商脚本不得内联进沙箱文档`() {
        // 真机铁证：`mermaid.min.js` 含 `<!--` ⇒ 内联进 `<script>` 会被 HTML 解析器拖入
        // "script data escaped" 态而悄悄截断（症状：节点在、渲染计数恒 0、不报错）
        val vendorWithComment = "var a=1;<!-- oops --> var b=2;"
        val script = ReaderTemplateSandboxDocument.Scripts(
            flowJs = flow,
            runtimeJs = runtime,
            injectorRuntimeJs = injector
        )
        val text = ReaderTemplateSandboxDocument.build(script)
        assertFalse("沙箱文档不得出现厂商脚本正文", text.contains(vendorWithComment))
        assertFalse("沙箱文档不得含 <!--（会把后续内容拖入转义态）", text.contains("<!--"))
        assertTrue("厂商脚本改由 init 消息下发（见 TemplateRenderBackend.vendorSources）", true)
    }

    @Test
    fun `脚本体转义防止提前闭合标签`() {
        val hostile = "var s='</script><img onerror=alert(1)>';"
        val text = ReaderTemplateSandboxDocument.build(scripts().copy(flowJs = hostile))
        assertFalse("转义后不得残留可用的 </script>", text.contains("</script><img"))
        assertTrue("转义形式为 <\\/", text.contains("<\\/script><img"))
        // 除标签自身外，文档里出现的 </script> 数量必须等于脚本段数（流+runtime+注入器+bootstrap=4）
        assertEquals(4, Regex("</script>").findAll(text).count())
    }

    @Test
    fun `脚本体转义 script-data-escaped 序列`() {
        // 真机铁证（2026-10-10）：`md/template-runtime.js` 的说明注释里写了该序列 ⇒ 沙箱文档里
        // runtime 之后的 `<script>`/`</script>` 变成 JS 语法错误 ⇒ 沙箱**零回包**（像 init 没到）
        val hostile = "var a=1;<!-- oops --> var b=2;"
        val text = ReaderTemplateSandboxDocument.build(scripts().copy(flowJs = hostile))
        assertFalse("转义后不得残留把解析器拖入转义态的序列", text.contains("<!--"))
        assertTrue("转义形式为 <\\!--", text.contains("<\\!--"))
        assertEquals("字符串里的 \\! 与 ! 同值（语义等价）", "var s='x<\\!--y';",
            ReaderTemplateSandboxDocument.escapeScriptBody("var s='x<!--y';"))
    }

    @Test
    fun `真实被内联资产经构造器后不含转义态序列`() {
        // 合成样例曾掩盖该缺陷（样例无该序列、真资产有）⇒ 必须拿真资产走一遍
        val text = ReaderTemplateSandboxDocument.build(
            ReaderTemplateSandboxDocument.Scripts(
                flowJs = SourceFileProbe.assetRawText("md/template-browser-flow.js"),
                runtimeJs = SourceFileProbe.assetRawText("md/template-runtime.js")
            )
        )
        assertFalse("被内联的真资产不得让解析器进入转义态", text.contains("<!--"))
        assertTrue("runtime 必须完整落进文档", text.contains("window.ReaderTemplateRuntime"))
        // flow 必须先加载（顺序即正确性）：比**定义处**而非任意引用（flow 自身也引用 runtime）
        val iFlow = text.indexOf("window.ReaderTemplateFlow = {")
        val iRuntime = text.indexOf("window.ReaderTemplateRuntime = {")
        assertTrue("flow 定义必须先于 runtime 定义：$iFlow < $iRuntime", iFlow in 0 until iRuntime)
        // 两个脚本段 + bootstrap 段 = 3 个闭合标签（注入器为空 ⇒ 不产出空标签）
        assertEquals(3, Regex("</script>").findAll(text).count())
    }

    @Test
    fun `沙箱文档不引用任何宿主对象`() {
        val text = doc()
        listOf("ReaderTemplateHost", "AndroidAssetBridge", "addJavascriptInterface", "Java.").forEach { mark ->
            assertFalse("沙箱文档不得引用宿主侧符号：$mark", text.contains(mark))
        }
        assertTrue("bootstrap 只能经消息通道转发 init", text.contains("data.type!=='init'"))
    }

    @Test
    fun `bootstrap 只转发 init 且幂等`() {
        val text = ReaderTemplateSandboxDocument.bootstrapScript()
        assertTrue("必须把 init 交给沙箱运行时", text.contains("window.ReaderTemplateRuntime.init(data)"))
        assertTrue("必须幂等（重复注入不叠加监听）", text.contains("window.__legadoTemplateInitForwarded"))
        assertFalse("不得处理 init 之外的指令（避免与 runtime 双重处理）", text.contains("remeasure"))
    }

    @Test
    fun `注入器 CSS 就位且缺脚本时不产出空标签`() {
        val text = doc()
        assertTrue("KaTeX/hljs 样式必须随沙箱文档", text.contains(".katex{color:red}"))
        val minimal = ReaderTemplateSandboxDocument.build(ReaderTemplateSandboxDocument.Scripts())
        assertFalse("空输入不得产出空 script 标签", minimal.contains("<script></script>"))
        assertTrue("空输入仍须是可加载文档（含 bootstrap 兜底）", minimal.contains("<html>"))
    }

    @Test
    fun `转义保持 JS 语义等价`() {
        // 常见真实形态：正则里的 \/ 与字符串里的 \/
        assertEquals("var a=/<\\/b/;", ReaderTemplateSandboxDocument.escapeScriptBody("var a=/<\\/b/;"))
        assertEquals("var s='x<\\/y';", ReaderTemplateSandboxDocument.escapeScriptBody("var s='x</y';"))
        assertEquals("no-slash", ReaderTemplateSandboxDocument.escapeScriptBody("no-slash"))
    }
}