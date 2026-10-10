package io.legado.app.ui.book.read.textweb

import io.legado.app.help.md.MdRichRenderInjector
import io.legado.app.model.localBook.epubcore.template.ReaderTemplateHostDocument
import io.legado.app.testkit.SourceFileProbe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板渲染后端**结构契约**（epub-md-rich-rendering 阶段 4.14 配对）。
 *
 * 为什么机检而不是只靠 L2：这个类的每一条失守都**不会让页面白屏**，只会静默功能缺失——
 * ①翻页没接 ⇒ 点下一页毫无反应（N1 类）；②富渲染没驱动 ⇒ 图表变成代码块；
 * ③用了 `addJavascriptInterface` ⇒ 沙箱隔离形同虚设（安全事故而非功能事故）。
 * 后两条里第③条在真机上"看起来一切正常"，只有源码级断言能拦住。
 */
class TemplateRenderBackendContractTest {

    private val src = "ui/book/read/textweb/TemplateRenderBackend.kt"

    private fun text(): String = SourceFileProbe.sourceText(src)

    @Test
    fun `通道禁用宿主对象注入且走消息监听`() {
        val s = text()
        assertFalse("AD-23：禁 addJavascriptInterface", s.contains("addJavascriptInterface"))
        assertTrue("必须走 WebViewCompat 消息监听", s.contains("addWebMessageListener"))
        assertTrue("必须用事件编解码做权威校验", s.contains("ReaderTemplateHostEventCodec.parse"))
        assertTrue("必须用宿主文档构造器", s.contains("ReaderTemplateHostDocument.build("))
        assertTrue("必须用沙箱文档构造器", s.contains("ReaderTemplateSandboxDocument.build("))
    }

    @Test
    fun `沙箱文档按需注入富渲染且顺序由构造器保证`() {
        val s = text()
        assertTrue("必须读流程/沙箱运行时资产", s.contains("template-browser-flow.js"))
        assertTrue(s.contains("template-runtime.js"))
        assertTrue(
            "厂商脚本必须以绝对 URL 交给沙箱（不经消息体、不内联）",
            s.contains("add(\"vendorUrls\"") && s.contains("private fun vendorUrls(")
        )
        assertTrue(
            "必须为沙箱提供 assets 白名单服务（仅 /md/ 前缀）",
            s.contains("ServedAssetPrefix") && s.contains("context.assets.open(asset)")
        )
        assertTrue(
            "无 mermaid 时不得加载 2.4MB 运行时",
            s.contains("content.hasMermaid && !assetReader.read(MdRichRenderInjector.MermaidAsset).isNullOrBlank()")
        )
    }

    @Test
    fun `翻页与重测走沙箱指令而非宿主滚动`() {
        val s = text()
        assertTrue("翻页必须下 goto-page（沙箱跨源 ⇒ 宿主改不了它的滚动）", s.contains("\"goto-page\""))
        assertTrue("边界判据来自沙箱回发页码", s.contains("fun edge()"))
        assertTrue("富渲染完成后必须重测分页", s.contains("ReaderTemplateHostDocument.postScript(\"remeasure\")"))
        assertFalse("不得用宿主 scrollBy 冒充翻页", s.contains("scrollBy("))
    }

    @Test
    fun `契约遵守与降级`() {
        val s = text()
        assertTrue("字符偏移必须返回 null（AD-14 禁假换算）", s.contains("fun textToPosition(range: TextRange): ReadingPosition? = null"))
        assertTrue(s.contains("fun positionToText(position: ReadingPosition): TextRange? = null"))
        assertTrue("模板异常必须交宿主回落", s.contains("RenderFailure.ENGINE_UNAVAILABLE"))
        assertTrue("必须处理渲染进程崩溃", s.contains("onRenderProcessGone"))
        assertTrue("旧内核无消息通道时必须降级而非兜底注入", s.contains("WEB_MESSAGE_LISTENER"))
    }

    @Test
    fun `桥对象名与 origin 与宿主文档单源`() {
        val s = text()
        assertTrue(
            "桥对象名必须取自宿主文档常量（两侧改名不同步 = 通道静默失效）",
            s.contains("ReaderTemplateHostDocument.WebMessageBridgeObjectName")
        )
        assertTrue(s.contains("originOf(baseUrl)"))
        assertTrue("基址必须复用宿主文档常量", s.contains("ReaderTemplateHostDocument.BaseUrl"))
        assertTrue("注入器全局名不得另立", text().contains("MdRichRenderInjector"))
    }

    @Test
    fun `沙箱承载面必须按视口以 px 显式定尺寸`() {
        // 真机铁证（2026-10-10，预览弹窗）：Compose `AndroidView` 承载的 WebView 里 vh/百分比高度都塌成 0px
        // （样式表已解析、position:absolute 生效，仅高度为 0）⇒ 沙箱视口 0 ⇒ 页框算成负数 ⇒ 预览空白且页数恒 1
        val s = text()
        assertTrue("必须在 init 之前给容器定尺寸", s.contains("sizeSandboxContainer(view)"))
        assertTrue("必须用宿主文档视口高以 px 定尺寸（不依赖 vh/百分比）",
            s.contains("f.style.height=document.documentElement.clientHeight+'px'"))
        assertTrue("宽度同理", s.contains("f.style.width=document.documentElement.clientWidth+'px'"))
        val doc = SourceFileProbe.rawText("model/localBook/epubcore/template/ReaderTemplateHostDocument.kt")
        assertTrue("宿主文档容器不得只靠 inset 百分比链（真机上会塌成 0）",
            doc.contains("height:100vh") || doc.contains("height:100%"))
    }

    @Test
    fun `装饰强度与动效必须随 init 下发且基线 css 随沙箱挂载`() {
        // 4.8d：装饰档位/动效是**用户级设置**，若漏下发，症状是"选了档位没反应"且**不报错**
        // （沙箱跨源，宿主读不到它的 <html> 属性）⇒ 源码层钉住"两侧都接上"
        val s = text()
        assertTrue("ReaderValues 必须带装饰档位", s.contains("val decoration: String"))
        assertTrue("ReaderValues 必须带动效开关", s.contains("val decorationMotion: Boolean"))
        assertTrue("init 载荷必须下发档位", s.contains("addProperty(\"decoration\", values.decoration)"))
        assertTrue("init 载荷必须下发动效", s.contains("addProperty(\"motion\", values.decorationMotion)"))
        assertTrue(
            "沙箱必须挂载装饰基线 CSS（否则档位下发到 <html> 也没人执行）",
            s.contains("MdRichRenderInjector.TemplateDecorationCssAsset")
        )
        // 4.15 C9/C10：布局基线若不挂载，运行时写到 <html> 的 data-rp-narrow / data-rp-writing
        // 就是死开关 —— 症状同样是"设置了没反应且不报错"
        assertTrue(
            "沙箱必须挂载布局基线 CSS（响应式收紧 + 竖排规格）",
            s.contains("MdRichRenderInjector.TemplateLayoutCssAsset")
        )
    }

    @Test
    fun `动效运行时闸门与稳定随机种子随链路就位`() {
        // 4.15 C6/C4：动效暂停必须由宿主下 `set-motion`（沙箱看不到手势/生命周期）；
        // 种子必须随 init 下发并在沙箱注入成属性/变量（否则作者的"看似随机"装饰每次重排都换画面）
        val s = text()
        assertTrue("必须提供运行时闸门入口", s.contains("fun setMotionPaused(paused: Boolean)"))
        assertTrue("必须下 set-motion 白名单指令", s.contains("postScript(") && s.contains("\"set-motion\""))
        assertTrue("未就绪时不得空刷消息", s.contains("if (!reportedFirstPage) return"))
        assertTrue("ReaderValues 必须带种子", s.contains("val seed: Int"))
        assertTrue("init 载荷必须下发种子", s.contains("addProperty(\"seed\", values.seed)"))
        val host = SourceFileProbe.assetRawText("md/template-host.js")
        assertTrue("宿主转发必须消费 seed（白名单漏键即静默丢字段）", host.contains("config.seed"))
    }
}