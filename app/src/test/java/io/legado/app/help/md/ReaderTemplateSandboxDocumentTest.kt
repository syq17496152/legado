package io.legado.app.help.md

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
    private val vendorA = "var __v=(function(){return 1})();"
    private val injector = "(function(){window.__legadoMdRichRender={done:false};})();"

    private fun scripts(
        vendorScripts: List<String> = listOf(vendorA),
        injectorCss: String = ".katex{color:red}"
    ) = ReaderTemplateSandboxDocument.Scripts(
        flowJs = flow,
        runtimeJs = runtime,
        vendorScripts = vendorScripts,
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
        val iVendor = text.indexOf(vendorA)
        val iInjector = text.indexOf(injector)
        val iBootstrap = text.indexOf("__legadoTemplateInitForwarded")
        assertTrue("五段都必须存在", listOf(iFlow, iRuntime, iVendor, iInjector, iBootstrap).all { it >= 0 })
        // runtime 在加载期读 window.ReaderTemplateFlow ⇒ flow 必须先加载（顺序颠倒 ⇒ 页数恒 1 且不报错）
        assertTrue("flow 必须先于 runtime", iFlow < iRuntime)
        assertTrue("厂商脚本必须在注入器运行时之前（注入器可能立即驱动它们）", iVendor < iInjector)
        assertTrue("bootstrap 必须最后（其余脚本已就位）", iInjector < iBootstrap)
    }

    @Test
    fun `每个厂商脚本各自独立标签且不被包进函数`() {
        val second = "var __w=(function(){return 2})();"
        val text = doc(scripts(vendorScripts = listOf(vendorA, second)))
        // 厂商脚本本体不得被任何包装（包进 IIFE 会因顶层 var 作用域变化而崩溃）
        listOf(vendorA, second).forEach { vendor ->
            assertTrue("厂商脚本必须原样独立成段：$vendor", text.contains("<script>$vendor</script>"))
        }
        assertEquals(
            "两个厂商脚本必须各自独立标签（不得合并成一段）",
            2,
            Regex("<script>var __").findAll(text).count()
        )
    }

    @Test
    fun `脚本体转义防止提前闭合标签`() {
        val hostile = "var s='</script><img onerror=alert(1)>';"
        val text = doc(scripts(vendorScripts = emptyList()).copy(flowJs = hostile))
        assertFalse("转义后不得残留可用的 </script>", text.contains("</script><img"))
        assertTrue("转义形式为 <\\/", text.contains("<\\/script><img"))
        // 除标签自身外，文档里出现的 </script> 数量必须等于脚本段数（流+runtime+注入器+bootstrap=4）
        assertEquals(4, Regex("</script>").findAll(text).count())
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