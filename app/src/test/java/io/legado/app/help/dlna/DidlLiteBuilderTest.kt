package io.legado.app.help.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * add-dlna-cast：DIDL-Lite 构造单测（tasks 4.1 验证标准）。
 *
 * 为什么这些小断言重要：DIDL 是渲染端判定"能不能播"的唯一依据，
 *  - 缺 `<upnp:class>` → 严格设备归为未知对象直接拒播
 *  - `protocolInfo` 里出现裸引号 → 属性被截断，整个元数据变非法 XML
 *  - URL 里的 `&` 未转义 → 元数据非法，设备回 SOAP Fault
 *
 * 验证点：
 * 1. 三处命名空间声明齐全
 * 2. `<upnp:class>` 恒为 `object.item.videoItem`
 * 3. `protocolInfo` 与 URL 的转义正确（URL 含 `&` 的直投地址是高发场景）
 * 4. 标题空白时兜底；标题含中文/特殊字符时正确转义
 * 5. `EMPTY_METADATA` 为空串（AD-11 降级链的"无元数据重试"依赖此语义）
 */
class DidlLiteBuilderTest {

    private val protocolInfo = MimeSniffer.protocolInfo("video/mp4")

    @Test
    fun build_declaresAllThreeNamespaces() {
        val didl = DidlLiteBuilder.build("http://192.168.1.7:8123/cast/t/a.mp4", protocolInfo, "测试")
        assertTrue("DIDL-Lite 默认命名空间", didl.contains("xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""))
        assertTrue("dc 命名空间", didl.contains("xmlns:dc=\"http://purl.org/dc/elements/1.1/\""))
        assertTrue("upnp 命名空间", didl.contains("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\""))
        assertTrue("item 结构存在", didl.contains("<item id=\"0\" parentID=\"-1\" restricted=\"1\">"))
    }

    @Test
    fun build_alwaysIncludesUpnpClassVideoItem() {
        val didl = DidlLiteBuilder.build("http://h/a.mp4", protocolInfo, "x")
        assertTrue(
            "缺 upnp:class 会被严格设备判为未知对象而拒播，必须恒带",
            didl.contains("<upnp:class>${DlnaConstants.DIDL_CLASS_VIDEO}</upnp:class>")
        )
        assertEquals("DIDL_CLASS_VIDEO 应为 videoItem", "object.item.videoItem", DlnaConstants.DIDL_CLASS_VIDEO)
    }

    @Test
    fun build_putsProtocolInfoInResAttribute() {
        val didl = DidlLiteBuilder.build("http://h/a.mp4", protocolInfo, "x")
        assertTrue(
            "protocolInfo 必须原样进 res 属性（含无引号陷阱的 DLNA 特性串）",
            didl.contains("protocolInfo=\"$protocolInfo\"")
        )
        assertTrue("OP=01 声明支持 seek 应保留", didl.contains("DLNA.ORG_OP=01"))
    }

    @Test
    fun build_escapesAmpersandInDirectCastUrl() {
        // 直投场景：投放原始地址，query 里带 & 是常态
        val url = "https://cdn.example.com/a.mp4?token=1&exp=2"
        val didl = DidlLiteBuilder.build(url, protocolInfo, "x")
        assertTrue("URL 里的 & 必须转义为 &amp;", didl.contains("token=1&amp;exp=2"))
        assertTrue("不得残留裸 &", !didl.contains("token=1&exp=2"))
    }

    @Test
    fun build_escapesTitle() {
        val didl = DidlLiteBuilder.build("http://h/a.mp4", protocolInfo, "A & B <第1集>")
        assertTrue(didl.contains("<dc:title>A &amp; B &lt;第1集&gt;</dc:title>"))
    }

    @Test
    fun build_fallsBackForBlankTitle() {
        assertTrue(
            "空标题应兜底，不能产出空 dc:title",
            DidlLiteBuilder.build("http://h/a.mp4", protocolInfo, "")
                .contains("<dc:title>${DlnaConstants.DEFAULT_TITLE}</dc:title>")
        )
        assertTrue(
            "null 标题同样兜底",
            DidlLiteBuilder.build("http://h/a.mp4", protocolInfo, null)
                .contains("<dc:title>${DlnaConstants.DEFAULT_TITLE}</dc:title>")
        )
        assertTrue(
            "纯空白标题也兜底",
            DidlLiteBuilder.build("http://h/a.mp4", protocolInfo, "   ")
                .contains("<dc:title>${DlnaConstants.DEFAULT_TITLE}</dc:title>")
        )
    }

    @Test
    fun build_outputIsSingleLayerEscaped() {
        // 该字符串会被整体作为 SOAP 的文本节点嵌入，因此此处只需一层转义；
        // 若这里出现 &amp;lt; 说明被二次转义，设备会显示成 "<" 字面量
        val didl = DidlLiteBuilder.build("http://h/a.mp4", protocolInfo, "<x>")
        assertTrue(didl.contains("&lt;x&gt;"))
        assertTrue("不得出现双重转义", !didl.contains("&amp;lt;"))
    }

    @Test
    fun emptyMetadata_isBlankForDegradedRetry() {
        assertEquals(
            "AD-11 降级链用空串重试，语义必须为空",
            "",
            DidlLiteBuilder.EMPTY_METADATA
        )
    }
}
