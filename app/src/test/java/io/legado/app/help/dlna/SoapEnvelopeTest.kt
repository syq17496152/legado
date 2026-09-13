package io.legado.app.help.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * add-dlna-cast：SOAP 信封构造与解析单测（tasks 4.1 验证标准）。
 *
 * 验证点：
 * 1. 信封含 SOAP 1.1 命名空间、动作名与服务 URN（`:1` 版本）
 * 2. **`SOAPACTION` 头必须带双引号**（design 协议铁律表写死；老三星/松下收到无引号值直接忽略）
 * 3. XML 文本/属性转义：`&` 最先替换（否则实体被二次转义）、控制字符被丢弃
 * 4. 响应解析对命名空间前缀不敏感（`<u:RelTime>` / `<RelTime>` / `<m:RelTime>` 都要认）
 * 5. `UPnPError` 码提取；无 `errorCode` 判为正常响应
 * 6. `HH:MM:SS` 双向转换，且 `formatHms` **钳制到 23:59:59**（REL_TIME 上限）
 */
class SoapEnvelopeTest {

    // ============ 信封构造 ============

    @Test
    fun build_containsSoapNamespacesAndAction() {
        val xml = SoapEnvelope.build(
            DlnaConstants.SERVICE_AV_TRANSPORT,
            "Play",
            "<InstanceID>0</InstanceID><Speed>1</Speed>"
        )
        assertTrue("必须有 SOAP 信封命名空间", xml.contains(DlnaConstants.SOAP_ENVELOPE_NS))
        assertTrue("必须有 encodingStyle", xml.contains(DlnaConstants.SOAP_ENCODING_STYLE))
        assertTrue("动作元素应带服务 URN", xml.contains("xmlns:u=\"${DlnaConstants.SERVICE_AV_TRANSPORT}\""))
        assertTrue("<u:Play> 元素存在", xml.contains("<u:Play "))
        assertTrue("参数体原样嵌入", xml.contains("<InstanceID>0</InstanceID>"))
        assertTrue("闭合标签存在", xml.contains("</u:Play>"))
        assertTrue("XML 声明存在", xml.startsWith("<?xml"))
    }

    @Test
    fun soapActionHeader_mustBeQuoted() {
        val header = SoapEnvelope.soapActionHeader(DlnaConstants.SERVICE_AV_TRANSPORT, "SetAVTransportURI")
        assertEquals(
            "值必须整体带双引号，格式为 \"<service>#<action>\"",
            "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            header
        )
        assertTrue("首字符必须是双引号", header.startsWith("\""))
        assertTrue("尾字符必须是双引号", header.endsWith("\""))
        assertTrue(
            "服务 URN 必须是 :1 版本（用 :2 会得到 401/500）",
            header.contains(":service:AVTransport:1#")
        )
    }

    // ============ 转义 ============

    @Test
    fun escapeText_escapesAmpersandFirst() {
        assertEquals("a&amp;b&lt;c&gt;d", SoapEnvelope.escapeText("a&b<c>d"))
    }

    @Test
    fun escapeText_doesNotDoubleEscape() {
        // 输入里已含实体形式的 & 时，仍应被转义成 &amp; 而不是保持原样
        assertEquals("&amp;amp;", SoapEnvelope.escapeText("&amp;"))
    }

    @Test
    fun escapeText_dropsIllegalControlChars() {
        val raw = "ok\u0001\u0008\u000Bend"
        assertEquals("控制字符会导致设备判 XML 非法，必须丢弃", "okend", SoapEnvelope.escapeText(raw))
        assertEquals("换行与制表符是合法空白，应保留", "a\n\tb", SoapEnvelope.escapeText("a\n\tb"))
    }

    @Test
    fun escapeText_keepsChineseAndUrlChars() {
        val title = "第 1 集：你好"
        assertEquals(title, SoapEnvelope.escapeText(title))
    }

    @Test
    fun escapeAttribute_alsoEscapesQuotes() {
        assertEquals(
            "属性值里的引号必须转成实体，否则截断属性",
            "a&amp;b&quot;c&apos;d",
            SoapEnvelope.escapeAttribute("a&b\"c'd")
        )
    }

    @Test
    fun unescape_reversesEntities() {
        assertEquals("a&b<c>d\"e'f", SoapEnvelope.unescape("a&amp;b&lt;c&gt;d&quot;e&apos;f"))
    }

    // ============ 响应解析 ============

    @Test
    fun extractTag_isNamespacePrefixAgnostic() {
        val xml = """
            <s:Envelope xmlns:s="x"><s:Body>
              <u:GetPositionInfoResponse xmlns:u="y">
                <RelTime>00:12:34</RelTime>
              </u:GetPositionInfoResponse>
            </s:Body></s:Envelope>
        """.trimIndent()
        assertEquals("00:12:34", SoapEnvelope.extractTag(xml, "RelTime"))
        assertEquals("00:12:34", SoapEnvelope.extractTag(xml.replace("<RelTime>", "<u:RelTime>")
            .replace("</RelTime>", "</u:RelTime>"), "RelTime"))
    }

    @Test
    fun extractTag_handlesAttributesAndMissingTag() {
        val xml = "<CurrentVolume Channel=\"Master\">42</CurrentVolume>"
        assertEquals("42", SoapEnvelope.extractTag(xml, "CurrentVolume"))
        assertNull("标签不存在应返回 null", SoapEnvelope.extractTag(xml, "Nope"))
    }

    @Test
    fun extractAllTags_returnsEveryOccurrence() {
        val xml = "<a>1</a><a>2</a><a>3</a>"
        assertEquals(listOf("1", "2", "3"), SoapEnvelope.extractAllTags(xml, "a"))
        assertEquals(emptyList<String>(), SoapEnvelope.extractAllTags(xml, "b"))
    }

    @Test
    fun parseFault_extractsUpnpErrorCode() {
        val xml = """
            <s:Envelope xmlns:s="x"><s:Body><s:Fault>
              <faultcode>s:Client</faultcode>
              <faultstring>UPnPError</faultstring>
              <detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
                <errorCode>714</errorCode>
                <errorDescription>Illegal MIME-type</errorDescription>
              </UPnPError></detail>
            </s:Fault></s:Body></s:Envelope>
        """.trimIndent()
        val fault = SoapEnvelope.parseFault(xml)
        assertEquals(714, fault!!.code)
        assertEquals("Illegal MIME-type", fault.description)
        assertTrue("toString 应含错误码", fault.toString().contains("714"))
    }

    @Test
    fun parseFault_returnsNullForNormalResponse() {
        val xml = "<s:Envelope xmlns:s=\"x\"><s:Body><u:PlayResponse xmlns:u=\"y\"/></s:Body></s:Envelope>"
        assertNull("正常响应不应判为 Fault", SoapEnvelope.parseFault(xml))
    }

    @Test
    fun parseFault_fallsBackWhenCodeIsNotANumber() {
        val xml = "<errorCode>not-a-number</errorCode>"
        assertEquals(
            "错误码非数字时用兜底码，不能抛异常",
            SoapEnvelope.ERROR_CODE_UNKNOWN,
            SoapEnvelope.parseFault(xml)!!.code
        )
    }

    // ============ 时间转换 ============

    @Test
    fun parseHms_handlesStandardAndDegenerateInput() {
        assertEquals(0L, SoapEnvelope.parseHms(null))
        assertEquals("空串", 0L, SoapEnvelope.parseHms(""))
        assertEquals("设备报无时长", 0L, SoapEnvelope.parseHms("00:00:00"))
        assertEquals(3723L * 1000, SoapEnvelope.parseHms("01:02:03"))
        assertEquals("NOT_IMPLEMENTED 之类的垃圾串应得 0", 0L, SoapEnvelope.parseHms("NOT_IMPLEMENTED"))
    }

    @Test
    fun formatHms_formatsAndClampsTo23h59m59s() {
        assertEquals("00:00:00", SoapEnvelope.formatHms(0))
        assertEquals("01:02:03", SoapEnvelope.formatHms(3723L * 1000))
        assertEquals("负值钳到 0", "00:00:00", SoapEnvelope.formatHms(-5000))
        assertEquals(
            "REL_TIME 上限 24h，超长视频必须钳制而不是发出非法值",
            "23:59:59",
            SoapEnvelope.formatHms(30L * 3600 * 1000)
        )
        assertEquals("恰好 24h 也要钳制", "23:59:59", SoapEnvelope.formatHms(24L * 3600 * 1000))
    }

    @Test
    fun hmsRoundTripIsStable() {
        for (seconds in listOf(0L, 1L, 59L, 60L, 3599L, 3600L, 86399L)) {
            val text = SoapEnvelope.formatHms(seconds * 1000)
            assertEquals("往返应稳定: $text", seconds * 1000, SoapEnvelope.parseHms(text))
        }
    }
}
