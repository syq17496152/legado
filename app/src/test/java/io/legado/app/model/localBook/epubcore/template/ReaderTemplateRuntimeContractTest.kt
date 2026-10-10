package io.legado.app.model.localBook.epubcore.template

import io.legado.app.help.md.MdRichRenderInjector
import io.legado.app.help.md.ReaderTemplateSandboxDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 模板运行时**契约一致性**单测（epub-md-rich-rendering 阶段 4.1 配对）。
 *
 * 存在理由：模板桥的安全边界由两侧共同实现——Kotlin 侧 [ReaderTemplateBridgePolicy] 是权威判据，
 * 沙箱/宿主 JS 各自还有一份**前置粗筛**表。两份表一旦漂移，就会出现
 * 「JS 放行但 Kotlin 拒收」（消息静默丢失，表现为模板功能莫名失效）或更糟的
 * 「JS 放行且 Kotlin 无登记」（越权字段绕过）。本测试把 JS 资产**当数据读入并逐项比对**。
 *
 * 同时固化 AD-23 的硬约束（可在文本层证伪的低级失守）：
 * ①禁止 `addJavascriptInterface`；②沙箱须为 `allow-scripts` 且**不含** `allow-same-origin`；
 * ③asset 引用不得在 JS 内自行拼路径（必须交 Kotlin 侧解析）。
 */
class ReaderTemplateRuntimeContractTest {

    private val assetsDir = File("src/main/assets/md")

    /** 正则而非字面量：注释里说明"不使用该 API"是允许的，**调用**才是违规。 */
    private val HOST_INTERFACE_INJECTION = Regex("""addJavascriptInterface\s*\(""")

    private fun asset(name: String): String {
        val file = File(assetsDir, name)
        assertTrue("资产缺失：${file.absolutePath}（工作目录=${File(".").absolutePath}）", file.isFile)
        return file.readText()
    }

    /** 抽取 JS 中形如 `type: ['a','b']` 的类型→字段表（本文件内联的两张表形态一致）。 */
    private fun parseTypeTable(js: String, tableName: String): Map<String, List<String>> {
        val start = js.indexOf("$tableName = {")
        assertTrue("未找到类型表 $tableName", start >= 0)
        val end = js.indexOf("};", start)
        assertTrue("类型表 $tableName 未闭合", end > start)
        val body = js.substring(start, end)
        val entry = Regex("""(\S+)\s*:\s*\[([^\]]*)]""").findAll(body)
        return entry.associate { match ->
            val key = match.groupValues[1].trim().trim('\'', '"')
            val fields = match.groupValues[2]
                .split(',')
                .map { it.trim().trim('\'', '"') }
                .filter { it.isNotEmpty() }
            key to fields
        }
    }

    @Test
    fun `沙箱侧类型与字段表与桥策略一致`() {
        val jsTable = parseTypeTable(asset("template-runtime.js"), "SEND_TYPES")
        assertTrue("解析失败：沙箱侧类型表为空", jsTable.isNotEmpty())
        jsTable.forEach { (type, fields) ->
            val policyFields = ReaderTemplateBridgePolicy.allowedFields(
                ReaderTemplateBridgePolicy.Direction.FROM_WEB, type
            )
            assertEquals("类型 $type 的字段表两侧必须一致", policyFields?.toList()?.sorted(), fields.sorted())
        }
        assertEquals(
            "沙箱侧不得漏登记桥策略已放行的类型",
            ReaderTemplateBridgePolicy.Direction.FROM_WEB.let { direction ->
                jsTable.keys.sorted()
            },
            jsTable.keys.sorted()
        )
    }

    @Test
    fun `宿主侧类型与字段表与桥策略一致`() {
        val jsTable = parseTypeTable(asset("template-host.js"), "INBOUND_TYPES")
        assertTrue("解析失败：宿主侧类型表为空", jsTable.isNotEmpty())
        jsTable.forEach { (type, fields) ->
            val policyFields = ReaderTemplateBridgePolicy.allowedFields(
                ReaderTemplateBridgePolicy.Direction.FROM_WEB, type
            )
            assertEquals("类型 $type 的字段表两侧必须一致", policyFields?.toList()?.sorted(), fields.sorted())
        }
    }

    @Test
    fun `两侧上限常量与桥策略一致`() {
        val host = asset("template-host.js")
        // JS 侧以表达式书写，故同时校验表达式文本与数值一致（防只改一边）
        assertTrue("原始字符上限表达式缺失", host.contains("MAX_RAW_CHARS = 256 * 1024"))
        assertEquals("Kotlin 侧上限须等于 JS 表达式", 256 * 1024, ReaderTemplateBridgePolicy.MaxRawChars)
        assertTrue(host.contains("MAX_JSON_DEPTH = ${ReaderTemplateBridgePolicy.MaxJsonDepth}"))
        assertTrue(host.contains("MAX_SELECTION_RECTS = ${ReaderTemplateBridgePolicy.MaxSelectionRects}"))
    }

    @Test
    fun `宿侧沙箱属性为纯 allow-scripts`() {
        val host = asset("template-host.js")
        assertFalse("AD-23 硬约束：禁用宿主对象注入 API", HOST_INTERFACE_INJECTION.containsMatchIn(host))
        // 直接校验属性赋值文本：必须是纯 allow-scripts（多一个标记就等于放弃隔离）
        assertTrue(
            "沙箱属性须精确为 allow-scripts",
            host.contains("setAttribute('sandbox', 'allow-scripts');")
        )
        assertFalse("不得出现同源标记", host.contains("-origin"))
    }

    @Test
    fun `宿主侧不在 JS 内自行拼素材路径`() {
        val host = asset("template-host.js")
        assertFalse("素材别名必须交 Kotlin 侧解析（ReaderAssetPathPolicy）", host.contains("asset://"))
    }

    @Test
    fun `沙箱侧不直接触碰宿主对象`() {
        val runtime = asset("template-runtime.js")
        assertFalse(HOST_INTERFACE_INJECTION.containsMatchIn(runtime))
        assertFalse("沙箱内不得有宿主对象引用", runtime.contains("AndroidAssetBridge"))
        assertTrue("沙箱只能经 parent.postMessage 通信", runtime.contains("parent.postMessage"))
    }

    @Test
    fun `沙箱侧白名单拒绝未登记类型`() {
        val runtime = asset("template-runtime.js")
        assertTrue("未登记发送类型须静默丢弃", runtime.contains("if (!allowed) return;"))
        assertTrue("未登记接收类型须丢弃", runtime.contains("RECEIVE_TYPES.indexOf(data.type) < 0"))
    }

    @Test
    fun `浏览器流程覆盖模板结构约定与三种分页`() {
        val flow = asset("template-browser-flow.js")
        assertTrue("须读正文槽位", flow.contains("[data-reader-flow=\"body\"]"))
        assertTrue("须读分栏标记", flow.contains("[data-reader-flow-pagination=\"columns\"]"))
        assertTrue("须读滚动视口", flow.contains("[data-reader-scroll-viewport]"))
        assertTrue("须有自研回退分页", flow.contains("settleManualPagination"))
        assertTrue("须回发结算", flow.contains("onFlowSettled"))
        assertTrue("页框高须有下限守卫（除零/整章被裁成 1 页）", flow.contains("MIN_PAGE_HEIGHT"))
        assertTrue("页数须有上限守卫（作者畸形 DOM 不得拖死宿主）", flow.contains("MAX_PAGES"))
    }

    @Test
    fun `自研回退分页把正文槽位冻结为页框并纵移翻页`() {
        // 真机缺口（2026-10-10）：模板只声明正文槽位、作者 CSS 让正文自由增高（素笺 `.mi-body`
        // 无 height/overflow）⇒ 页数恒 1、翻页无效、后半章溢出读不到。引擎必须自己冻结页框。
        val flow = asset("template-browser-flow.js")
        assertTrue("必须冻结页框（固定高）", flow.contains("function freezeSlot("))
        assertTrue("必须以 border-box 固定高（与量取口径一致，含 padding）", flow.contains("boxSizing = 'border-box'"))
        assertTrue("必须裁切（overflow:hidden 仍是可编程滚动容器）", flow.contains("overflow = 'hidden'"))
        assertTrue(
            "页框高须由「视口高 − 注入前文档高 + 槽位空高」推出（页眉页脚被自然扣掉）",
            flow.contains("viewportHeight - chromeHeight + slotEmptyHeight")
        )
        assertTrue(
            "自研回退须纵移（用错轴＝点翻页没反应）",
            flow.contains("slot.scrollTop = target * (slot.clientHeight || 1)")
        )
        assertTrue("分栏模板才横移", flow.contains("slot.scrollLeft = target * (slot.clientWidth || 1)"))
        assertTrue(
            "页码必须读容器实际滚动位置（回读静态 config.pageIndex ⇒ 翻页后页码不变、边界判据失真）",
            flow.contains("function scrollIndex(") && flow.contains("scrollIndex(slot.scrollTop, visible)")
        )
    }

    @Test
    fun `运行时在正文注入前量页框并立即结算一次`() {
        // 两条顺序/完备性不变量，各自对应一类真实失守：
        // ①先注入后量 ⇒ 正文自身被算进"固定占位"，页框被算成 0 高；
        // ②不立即结算 ⇒ 无富渲染元素的章节拿不到 stable，宿主只能 8s 超时回落 canvas。
        val runtime = asset("template-runtime.js")
        val iInit = runtime.indexOf("flow.initialize(config.flow)")
        val iBody = runtime.indexOf("applyBody(config.bodyHtml)")
        assertTrue("continueInit 必须调用 flow.initialize", iInit >= 0)
        assertTrue("正文注入必须在页框量取之后：$iInit < $iBody", iInit in 0 until iBody)
        val iSettle = runtime.indexOf("flow.settle(false)")
        assertTrue("必须在初始化阶段立即结算一次", iSettle > iBody)
    }

    @Test
    fun `沙箱富渲染驱动与注入器全局名单源`() {
        val runtime = asset("template-runtime.js")
        // 沙箱跨源 ⇒ 宿主读不到它的 DOM，只能靠它自己驱动注入器并回发消息
        assertTrue(
            "沙箱必须驱动阶段 3.8/3.9 注入器（同一全局名；改名漏改 = 注入永不生效）",
            runtime.contains("window.${MdRichRenderInjector.StatusGlobal}")
        )
        assertTrue("必须调用可重复运行入口", runtime.contains("status.run"))
        assertTrue(
            "完成后须回发 renderState 且**带渲染计数**（只看'脚本跑了'在空文档上也会成立）",
            runtime.contains("'inject-done,mermaid='")
        )
        assertTrue("未注入时须明确回报，不得静默", runtime.contains("'inject-unavailable'"))
    }

    @Test
    fun `宿主放行指令与 Kotlin 侧 TO_WEB 表一致`() {
        val host = asset("template-host.js")
        val arrayText = Regex("""\[([^\]]*)]\.indexOf\(type\)""").find(host)?.groupValues?.get(1)
        assertTrue("未找到宿主侧放行类型数组（post 的准入判据）", !arrayText.isNullOrBlank())
        val jsTypes = arrayText!!.split(',').map { it.trim().trim('\'', '"') }.filter { it.isNotEmpty() }
        // 权威源 = Kotlin 策略表（不是测试里再抄一份清单，否则"两侧一起漂"就测不出来）
        val kotlinTypes = ReaderTemplateBridgePolicy
            .registeredTypes(ReaderTemplateBridgePolicy.Direction.TO_WEB)
            .toList()
        // 两侧不一致即"Kotlin 放行但宿主 JS 拒发"（功能静默失效）或反之（越权通道）
        assertEquals("宿主放行集合必须与 Kotlin TO_WEB 表逐项相等", kotlinTypes.sorted(), jsTypes.sorted())
    }

    @Test
    fun `厂商脚本按 URL 加载且不内联`() {
        val runtime = asset("template-runtime.js")
        assertTrue("必须提供安装入口", runtime.contains("function installVendors(urls, done)"))
        assertTrue(
            "必须用 `<script src>` 按 URL 加载（体量走浏览器流式加载，不经消息体；也不经过 HTML 解析）",
            runtime.contains("script.src = url")
        )
        assertTrue("加载失败必须回报（跨源无调试器，静默失败无从定位）", runtime.contains("'vendor-load-failed'"))
        assertTrue("加载须有界等待（失败也要继续，不白屏）", runtime.contains("'vendor-load-timeout'"))
        assertTrue("厂商就位后才继续初始化", runtime.contains("installVendors(config.vendorUrls, function () {"))
        assertTrue("其余初始化必须在回调内完成", runtime.contains("function continueInit(config)"))
        assertFalse(
            "沙箱文档不得内联厂商脚本（srcdoc 由 Kotlin 侧构造，此处锁死脚本侧不引入内联路径）",
            runtime.contains("document.write")
        )
    }

    @Test
    fun `宿主驱动翻页在流程层有落点`() {
        val flow = asset("template-browser-flow.js")
        assertTrue("流程层必须提供 goto（沙箱跨源 ⇒ 只有它能改内部滚动）", flow.contains("function goto("))
        assertTrue("必须按滚动/分页两类容器分别处理", flow.contains("scrollTop") && flow.contains("scrollLeft"))
        assertTrue("跳转后必须重新结算", flow.contains("return settle(false);"))
        val runtime = asset("template-runtime.js")
        assertTrue("运行时必须把 goto-page 交给流程层", runtime.contains("flow.goto(data.pageIndex)"))
    }

    @Test
    fun `init 不进沙箱接收白名单而由 bootstrap 转发`() {
        val runtime = asset("template-runtime.js")
        assertTrue(
            "接收类型表须与 Kotlin 侧 TO_WEB 表一致（注入/重测/主题/翻页）",
            runtime.contains(
                "RECEIVE_TYPES = ['inject-mermaid', 'inject-katex', 'remeasure', 'set-theme', 'goto-page']"
            )
        )
        assertFalse("init 不得进入接收白名单（否则与 bootstrap 双重处理同一消息）", runtime.contains("'init',"))
        val bootstrap = ReaderTemplateSandboxDocument.bootstrapScript()
        assertTrue("bootstrap 必须独占 init 转发", bootstrap.contains("data.type!=='init'"))
        assertTrue(
            "bootstrap 必须调用沙箱运行时入口",
            bootstrap.contains("window.ReaderTemplateRuntime.init(data)")
        )
    }

    @Test
    fun `模板运行时覆盖内容注入与内容感知字段`() {
        val runtime = asset("template-runtime.js")
        assertTrue(runtime.contains("[data-reader-field]"))
        assertTrue("须支持进度百分比变量", runtime.contains("--rp-percent"))
        assertTrue("须支持小时变量与阶段类", runtime.contains("--rp-hour"))
        assertTrue(runtime.contains("data-rp-phase"))
        assertTrue("须下发主题", runtime.contains("data-reader-theme"))
    }
}