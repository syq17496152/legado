package io.legado.app.help.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * add-dlna-cast：SSDP 响应报文解析单测（tasks 2.1 验证标准）。
 *
 * 验证点：
 * 1. 头名大小写不敏感（`LOCATION` / `location` / `Location` 都要认）
 * 2. 缺 `LOCATION` → 判无效（没有它就取不到设备描述，留着只会污染列表）
 * 3. 非 200 / 畸形报文 → 判无效，不抛异常
 * 4. LF-only 行尾也要能解析（少数实现不按 CRLF）
 * 5. `USN` 缺失时用 `LOCATION` 兜底做身份，不能丢掉可用设备
 *
 * 已知上限：仅覆盖纯文本解析，不覆盖 socket 收发（那部分靠真机 L2 + 假渲染器验证）。
 */
class SsdpMessageParserTest {

    private fun response(vararg headers: String): String = buildString {
        append("HTTP/1.1 200 OK\r\n")
        headers.forEach { append(it).append("\r\n") }
        append("\r\n")
    }

    private val locationHeader = "LOCATION: http://192.168.1.100:8080/desc.xml"
    private val usnHeader =
        "USN: uuid:2fac1234-31f8-11b4-a222-08002b34c003::urn:schemas-upnp-org:device:MediaRenderer:1"

    @Test
    fun parse_readsStandardResponse() {
        val parsed = SsdpMessageParser.parse(
            response(
                "CACHE-CONTROL: max-age=1800",
                locationHeader,
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1",
                usnHeader,
                "SERVER: Linux/3.14 UPnP/1.0 TestRenderer/1.0"
            )
        )
        assertNotNull("标准响应应解析成功", parsed)
        assertEquals("http://192.168.1.100:8080/desc.xml", parsed!!.location)
        assertEquals("uuid:2fac1234-31f8-11b4-a222-08002b34c003", parsed.udn)
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", parsed.st)
    }

    @Test
    fun parse_headerNamesAreCaseInsensitive() {
        val lower = SsdpMessageParser.parse(
            response(
                "location: http://192.168.1.7:80/d.xml",
                "usn: uuid:aaa::urn:schemas-upnp-org:device:MediaRenderer:1",
                "st: urn:schemas-upnp-org:device:MediaRenderer:1"
            )
        )
        val mixed = SsdpMessageParser.parse(
            response(
                "Location: http://192.168.1.7:80/d.xml",
                "Usn: uuid:aaa::urn:schemas-upnp-org:device:MediaRenderer:1",
                "sT: urn:schemas-upnp-org:device:MediaRenderer:1"
            )
        )
        assertEquals("http://192.168.1.7:80/d.xml", lower?.location)
        assertEquals("http://192.168.1.7:80/d.xml", mixed?.location)
        assertEquals("uuid:aaa", lower?.udn)
        assertEquals("uuid:aaa", mixed?.udn)
    }

    @Test
    fun parse_missingLocationIsRejected() {
        assertNull(
            "缺 LOCATION 必须判无效",
            SsdpMessageParser.parse(response(usnHeader))
        )
    }

    @Test
    fun parse_blankLocationIsRejected() {
        assertNull(
            "LOCATION 为空串必须判无效",
            SsdpMessageParser.parse(response("LOCATION:   ", usnHeader))
        )
    }

    @Test
    fun parse_nonOkStatusIsRejected() {
        val raw = "HTTP/1.1 404 Not Found\r\n$locationHeader\r\n$usnHeader\r\n\r\n"
        assertNull("非 200 响应必须判无效", SsdpMessageParser.parse(raw))
    }

    @Test
    fun parse_malformedInputReturnsNull() {
        assertNull("空串", SsdpMessageParser.parse(""))
        assertNull("空白串", SsdpMessageParser.parse("   \n  "))
        assertNull("非 HTTP 报文", SsdpMessageParser.parse("NOTIFY * HTTP/1.1\r\n$locationHeader"))
        assertNull("只有状态行", SsdpMessageParser.parse("HTTP/1.1 200 OK\r\n\r\n"))
    }

    @Test
    fun parse_acceptsLfOnlyLineEndings() {
        val raw = "HTTP/1.1 200 OK\n" +
            "LOCATION: http://10.0.0.5/desc.xml\n" +
            "USN: uuid:lf-only::urn:schemas-upnp-org:device:MediaRenderer:1\n\n"
        val parsed = SsdpMessageParser.parse(raw)
        assertNotNull("LF-only 行尾应能解析", parsed)
        assertEquals("http://10.0.0.5/desc.xml", parsed!!.location)
    }

    @Test
    fun parse_ignoresMalformedHeaderLines() {
        val raw = "HTTP/1.1 200 OK\r\n" +
            "no-colon-line\r\n" +
            ":leading-colon\r\n" +
            "$locationHeader\r\n" +
            "$usnHeader\r\n\r\n"
        val parsed = SsdpMessageParser.parse(raw)
        assertNotNull("畸形头行应被跳过而不影响整体解析", parsed)
        assertEquals("http://192.168.1.100:8080/desc.xml", parsed!!.location)
    }

    @Test
    fun parse_usnMissingFallsBackToLocationAsIdentity() {
        val parsed = SsdpMessageParser.parse(response(locationHeader))
        assertNotNull("缺 USN 不应丢弃设备", parsed)
        assertEquals(
            "USN 缺失时身份退化为 LOCATION",
            "http://192.168.1.100:8080/desc.xml",
            parsed!!.udn
        )
        assertEquals("", parsed.st)
    }

    @Test
    fun parse_keepsFirstValueForDuplicateHeader() {
        val raw = "HTTP/1.1 200 OK\r\n" +
            "LOCATION: http://first.local/desc.xml\r\n" +
            "LOCATION: http://second.local/desc.xml\r\n" +
            "$usnHeader\r\n\r\n"
        val parsed = SsdpMessageParser.parse(raw)
        assertEquals("重复同名头保留首次出现", "http://first.local/desc.xml", parsed!!.location)
    }
}
