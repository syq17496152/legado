package io.legado.app.help.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * add-dlna-cast：设备描述文档解析单测（tasks 2.2 验证标准）。
 *
 * 验证点：
 * 1. MediaRenderer 能识别；MediaServer / 网关设备被排除
 * 2. 相对 `controlURL` 按描述文档 URL 归一化为绝对地址（含 `/绝对`、`../上级`、`./同级`）
 * 3. 绝对 `controlURL` 原样保留
 * 4. 无 AVTransport 服务 → 判无效（投不了屏）
 * 5. 无 RenderingControl → `controlUrlRenderingControl == null`（UI 据此隐藏音量控件）
 * 6. 畸形 XML → 返回 null 不抛异常
 * 7. 标签名大小写不一致也能解析；描述文档缺 UDN 时用 SSDP 阶段的 UDN 兜底
 */
class DeviceDescriptionParserTest {

    private val location = "http://192.168.1.100:8080/upnp/desc.xml"

    private fun description(
        deviceType: String = "urn:schemas-upnp-org:device:MediaRenderer:1",
        friendlyName: String = "客厅电视",
        modelName: String = "MiTV-4A",
        udn: String = "uuid:device-1",
        avtControlUrl: String = "/upnp/control/AVTransport",
        rcsBlock: String = """
            <service>
              <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
              <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
              <controlURL>/upnp/control/RenderingControl</controlURL>
            </service>"""
    ): String = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>$deviceType</deviceType>
            <friendlyName>$friendlyName</friendlyName>
            <manufacturer>Test</manufacturer>
            <modelName>$modelName</modelName>
            <UDN>$udn</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <controlURL>$avtControlUrl</controlURL>
              </service>
              $rcsBlock
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun parse_recognizesMediaRenderer() {
        val device = DeviceDescriptionParser.parse(description(), location)
        assertNotNull("MediaRenderer 应解析成功", device)
        assertEquals("uuid:device-1", device!!.udn)
        assertEquals("客厅电视", device.friendlyName)
        assertEquals("MiTV-4A", device.modelName)
        assertEquals("客厅电视", device.displayName)
    }

    @Test
    fun parse_rejectsMediaServer() {
        val xml = description(deviceType = "urn:schemas-upnp-org:device:MediaServer:1")
        assertNull("MediaServer 不是渲染端，必须排除", DeviceDescriptionParser.parse(xml, location))
    }

    @Test
    fun parse_rejectsInternetGatewayDevice() {
        val xml = description(deviceType = "urn:schemas-upnp-org:device:InternetGatewayDevice:1")
        assertNull("网关设备必须排除", DeviceDescriptionParser.parse(xml, location))
    }

    @Test
    fun parse_resolvesRelativeControlUrls() {
        val device = DeviceDescriptionParser.parse(description(), location)
        assertEquals(
            "以 / 开头的路径应按 host:port 补全",
            "http://192.168.1.100:8080/upnp/control/AVTransport",
            device!!.controlUrlAvTransport
        )
        assertEquals(
            "RenderingControl 同样归一化",
            "http://192.168.1.100:8080/upnp/control/RenderingControl",
            device.controlUrlRenderingControl
        )
    }

    @Test
    fun parse_resolvesDotDotAndDotRelativePaths() {
        // ../ 应回退一级目录；./ 应停留在同级
        val upOne = DeviceDescriptionParser.parse(
            description(avtControlUrl = "../control/AVTransport"),
            location
        )
        assertEquals(
            "http://192.168.1.100:8080/control/AVTransport",
            upOne!!.controlUrlAvTransport
        )

        val sameDir = DeviceDescriptionParser.parse(
            description(avtControlUrl = "./AVTransport"),
            location
        )
        assertEquals(
            "http://192.168.1.100:8080/upnp/AVTransport",
            sameDir!!.controlUrlAvTransport
        )
    }

    @Test
    fun parse_keepsAbsoluteControlUrls() {
        val absolute = "http://192.168.1.100:49152/AVTransport/ctrl"
        val device = DeviceDescriptionParser.parse(
            description(avtControlUrl = absolute),
            location
        )
        assertEquals("绝对地址应原样保留", absolute, device!!.controlUrlAvTransport)
    }

    @Test
    fun parse_withoutAvTransportIsRejected() {
        val xml = description(avtControlUrl = "")
        assertNull("没有 AVTransport 的渲染端无法投屏，应排除", DeviceDescriptionParser.parse(xml, location))
    }

    @Test
    fun parse_withoutRenderingControlLeavesVolumeUrlNull() {
        val device = DeviceDescriptionParser.parse(description(rcsBlock = ""), location)
        assertNotNull("缺 RenderingControl 不影响投屏", device)
        assertNull(
            "RenderingControl 缺失时必须为 null，UI 据此隐藏音量控件",
            device!!.controlUrlRenderingControl
        )
    }

    @Test
    fun parse_malformedXmlReturnsNull() {
        assertNull("空串", DeviceDescriptionParser.parse("", location))
        assertNull("非 XML 文本", DeviceDescriptionParser.parse("not xml at all", location))
        assertNull(
            "缺少 device 节点",
            DeviceDescriptionParser.parse("<root><specVersion/></root>", location)
        )
    }

    @Test
    fun parse_isCaseInsensitiveOnTagNames() {
        val xml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <devicetype>urn:schemas-upnp-org:device:MediaRenderer:1</devicetype>
                <friendlyname>小写标签电视</friendlyname>
                <UDN>uuid:lower</UDN>
                <serviceList>
                  <service>
                    <servicetype>urn:schemas-upnp-org:service:AVTransport:1</servicetype>
                    <CONTROLURL>/c/avt</CONTROLURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()
        val device = DeviceDescriptionParser.parse(xml, location)
        assertNotNull("标签名大小写不一致也应解析", device)
        assertEquals("小写标签电视", device!!.friendlyName)
        assertEquals("http://192.168.1.100:8080/c/avt", device.controlUrlAvTransport)
    }

    @Test
    fun parse_udnFallsBackToSsdpStageValue() {
        val xml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>无 UDN 设备</friendlyName>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>/c/avt</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()
        val device = DeviceDescriptionParser.parse(xml, location, expectedUdn = "uuid:from-ssdp")
        assertEquals("描述文档缺 UDN 时应回退 SSDP 阶段的值", "uuid:from-ssdp", device!!.udn)
        assertNull(
            "既无描述 UDN 也无 SSDP UDN → 必须判无效（无法去重与记忆）",
            DeviceDescriptionParser.parse(xml, location, expectedUdn = null)
        )
    }

    @Test
    fun displayName_fallsBackThroughModelNameThenDefault() {
        val noFriendly = DeviceDescriptionParser.parse(
            description(friendlyName = "", modelName = "OnlyModel"),
            location
        )
        assertEquals("OnlyModel", noFriendly!!.displayName)

        val neither = DeviceDescriptionParser.parse(
            description(friendlyName = "", modelName = ""),
            location
        )
        assertEquals(DlnaConstants.DEFAULT_TITLE, neither!!.displayName)
    }

    @Test
    fun resolveUrl_handlesBlankAndNonHttpInput() {
        assertNull("空白相对路径应为 null", DeviceDescriptionParser.resolveUrl(location, "   "))
        assertTrue(
            "https 绝对地址应原样返回",
            DeviceDescriptionParser.resolveUrl(location, "https://a.b/c") == "https://a.b/c"
        )
    }
}
