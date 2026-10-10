package io.legado.app.model.localBook.epubcore.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板宿主通道契约（epub-md-rich-rendering 阶段 4.14 配对：宿主文档 + 消息编解码）。
 *
 * 为什么值得逐条断言：这条通道是**模板（第三方代码）进入宿主的唯一入口**，两类失守都极隐蔽——
 * ①解析顺序被改（先 parse 再判长度）⇒ 超长串进解析器，成了攻击面；
 * ②字段被"顺手反序列化"吸收 ⇒ 越权字段再也看不见，白名单形同虚设。
 * 二者都不会让功能"看起来坏掉"，只会让边界静默失效，故必须机检。
 */
class ReaderTemplateHostChannelTest {

    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun `宿主文档只承载容器与回调且不含危险注入`() {
        val doc = ReaderTemplateHostDocument.build("/* host */ window.ReaderTemplateHost={};")
        assertTrue("必须有沙箱 iframe 挂载容器", doc.contains(ReaderTemplateHostDocument.ContainerId))
        assertTrue("必须承载宿主侧运行时", doc.contains("window.ReaderTemplateHost={}"))
        assertTrue("必须有回调定义", doc.contains(ReaderTemplateHostDocument.BridgePostGlobal))
        assertTrue(
            "回调必须走 WebMessagePort 对象（禁 addJavascriptInterface）",
            doc.contains(ReaderTemplateHostDocument.WebMessageBridgeObjectName)
        )
        assertFalse("AD-23：禁宿主对象注入 API", doc.contains("addJavascriptInterface"))
    }

    @Test
    fun `宿主脚本中的结束标签被转义`() {
        val doc = ReaderTemplateHostDocument.build("var s=\"</script><b>\";")
        assertFalse("作者/宿主脚本不得提前闭合标签", doc.contains("</script><b>"))
        assertTrue("转义形式为 <\\/", doc.contains("<\\/script><b>"))
    }

    @Test
    fun `init 脚本把 onMessage 拼为函数引用且负载原样保留`() {
        val payload = """{"srcdoc":"<html></html>","template":{"id":"builtin.a"},"bodyHtml":"<p>x</p>"}"""
        val script = ReaderTemplateHostDocument.initScript(payload)
        assertTrue("必须调用宿主 init", script.startsWith("window.ReaderTemplateHost.init({"))
        assertTrue("负载必须原样进入对象字面量", script.contains(""""bodyHtml":"<p>x</p>""""))
        assertTrue(
            "onMessage 必须是函数引用（JSON 表达不了函数 ⇒ 由构造器拼接）",
            script.contains("onMessage:" + ReaderTemplateHostDocument.BridgePostGlobal)
        )
        assertTrue(script.endsWith("});"))
    }

    @Test
    fun `init 脚本拒绝非对象负载`() {
        val error = runCatching { ReaderTemplateHostDocument.initScript("[1,2]") }.exceptionOrNull()
        assertTrue("非对象负载必须显式失败（否则会生成非法 JS）", error is IllegalArgumentException)
    }

    @Test
    fun `post 脚本只产出放行类型调用`() {
        assertEquals(
            "window.ReaderTemplateHost.post('inject-mermaid');",
            ReaderTemplateHostDocument.postScript("inject-mermaid")
        )
        assertEquals(
            "window.ReaderTemplateHost.post('set-theme',{\"themeId\":\"night\"});",
            ReaderTemplateHostDocument.postScript("set-theme", """{"themeId":"night"}""")
        )
        // 非对象负载按"无负载"处理，不得生成非法参数
        assertEquals(
            "window.ReaderTemplateHost.post('remeasure');",
            ReaderTemplateHostDocument.postScript("remeasure", "not-json")
        )
    }

    @Test
    fun `JS 字面量转义覆盖行终止符`() {
        val escaped = ReaderTemplateHostDocument.escapeForJsLiteral("a\u2028b\u2029c")
        assertFalse("U+2028/2029 必须转义（ES2019 前是行终止符 ⇒ 内联即语法错）", escaped.contains('\u2028'))
        assertFalse(escaped.contains('\u2029'))
        assertTrue(escaped.contains("\\u2028"))
        assertTrue(escaped.contains("\\u2029"))
    }

    // === 编解码 ===

    private fun parse(raw: String) = ReaderTemplateHostEventCodec.parse(raw, token)

    @Test
    fun `合法 stable 被分类且取出页数`() {
        val result = parse("""{"type":"stable","token":"$token","pageIndex":2,"pageCount":7}""")
        assertTrue(result.accepted)
        val event = result.event
        assertTrue(event is ReaderTemplateHostEventCodec.Event.Stable)
        assertEquals(2, (event as ReaderTemplateHostEventCodec.Event.Stable).pageIndex)
        assertEquals(7, event.pageCount)
    }

    @Test
    fun `token 不匹配即拒收`() {
        val result = parse("""{"type":"stable","token":"deadbeef","pageIndex":0,"pageCount":1}""")
        assertNull(result.event)
        assertEquals("bad-token", result.rejection?.code)
    }

    @Test
    fun `越权字段点名拒收而不是被静默吸收`() {
        val result = parse("""{"type":"stable","token":"$token","pageIndex":0,"pageCount":1,"evil":"x"}""")
        assertNull("越权字段必须让整条消息被拒", result.event)
        assertEquals("unexpected-field", result.rejection?.code)
    }

    @Test
    fun `未登记类型被拒`() {
        val result = parse("""{"type":"runtime-eval","token":"$token","src":"alert(1)"}""")
        assertEquals("unknown-type", result.rejection?.code)
    }

    @Test
    fun `超长消息在解析前就被拒`() {
        // 刻意用**非 JSON** 超长串：若实现先 parse 再判长度，这里会报 invalid-json ⇒ 顺序不变量被破坏
        val raw = "x".repeat(ReaderTemplateBridgePolicy.MaxRawChars + 1)
        val result = parse(raw)
        assertEquals("too-long", result.rejection?.code)
    }

    @Test
    fun `非法 JSON 与合法深度分类`() {
        assertEquals("invalid-json", parse("{oops").rejection?.code)
        assertEquals("not-object", parse("[1,2,3]").rejection?.code)
        // 深度超限只能藏在白名单字段内部（越权字段会被更早拒收）
        val deep = """{"type":"selection","token":"$token","rects":[[[[[1]]]]]}"""
        assertEquals("too-deep", parse(deep).rejection?.code)
    }

    @Test
    fun `畸形 rects 不抛异常且被拒`() {
        val result = parse("""{"type":"selection","token":"$token","rects":5}""")
        assertEquals("too-many-rects", result.rejection?.code)
    }

    @Test
    fun `renderState 与 error 分类齐备`() {
        val state = parse("""{"type":"renderState","token":"$token","state":"inject-done"}""")
        assertEquals(
            "inject-done",
            (state.event as ReaderTemplateHostEventCodec.Event.RenderState).state
        )
        val error = parse("""{"type":"error","token":"$token","code":"template-flow-failed","message":"boom"}""")
        assertEquals(
            "template-flow-failed",
            (error.event as ReaderTemplateHostEventCodec.Event.SandboxError).code
        )
        val metrics = parse("""{"type":"metrics","token":"$token","costMs":42}""")
        assertEquals(42L, (metrics.event as ReaderTemplateHostEventCodec.Event.Metrics).costMs)
    }

    @Test
    fun `白名单内未消费类型归类为忽略而非错误`() {
        val result = parse("""{"type":"contentChanged","token":"$token","revision":3}""")
        assertTrue(result.accepted)
        assertEquals("contentChanged", (result.event as ReaderTemplateHostEventCodec.Event.Ignored).type)
        assertFalse(ReaderTemplateHostEventCodec.isHandled("contentChanged"))
        assertTrue(ReaderTemplateHostEventCodec.isHandled("stable"))
    }
}