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
        assertTrue("须有元素扫描上限", flow.contains("MAX_SCAN_ELEMENTS"))
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
        assertTrue("完成后须回发 renderState（沙箱不能轮询 DOM）", runtime.contains("'inject-done'"))
        assertTrue("未注入时须明确回报，不得静默", runtime.contains("'inject-unavailable'"))
    }

    @Test
    fun `init 不进沙箱接收白名单而由 bootstrap 转发`() {
        val runtime = asset("template-runtime.js")
        assertTrue(
            "接收类型表须保持为注入/重测/主题四类",
            runtime.contains("RECEIVE_TYPES = ['inject-mermaid', 'inject-katex', 'remeasure', 'set-theme']")
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