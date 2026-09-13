package io.legado.app.help.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * add-dlna-cast：MIME / protocolInfo 推断单测（tasks 2.4 验证标准）。
 *
 * 验证点：
 * 1. 扩展名映射表关键项齐全（mp4 / m3u8 / mkv / ts / webm）
 * 2. query 与 fragment 不干扰判定（`a.mp4?token=xxx` 必须命中）
 * 3. 扩展名大小写不敏感
 * 4. 未知扩展名/无扩展名 → 纯函数返回 null（由 [MimeSniffer.resolve] 兜底）
 * 5. `resolve` 三级顺序：扩展名 → 探测 → 兜底；**探测抛异常也必须降级而非抛出**
 * 6. HLS 判定（Content-Type 优先，其次 URL 路径）
 *
 * 已知上限：`resolve` 的第二级此处用注入的 lambda 模拟，不真正发 HEAD（网络行为靠真机 L2）。
 */
class MimeSnifferTest {

    // ============ mimeFromUrl ============

    @Test
    fun mimeFromUrl_mapsCommonVideoExtensions() {
        assertEquals("video/mp4", MimeSniffer.mimeFromUrl("https://a.b/c.mp4"))
        assertEquals("video/x-matroska", MimeSniffer.mimeFromUrl("https://a.b/c.mkv"))
        assertEquals("video/webm", MimeSniffer.mimeFromUrl("https://a.b/c.webm"))
        assertEquals("video/mp2t", MimeSniffer.mimeFromUrl("https://a.b/c.ts"))
        assertEquals("application/vnd.apple.mpegurl", MimeSniffer.mimeFromUrl("https://a.b/c.m3u8"))
        assertEquals("video/x-flv", MimeSniffer.mimeFromUrl("https://a.b/c.flv"))
        assertEquals("video/quicktime", MimeSniffer.mimeFromUrl("https://a.b/c.mov"))
    }

    @Test
    fun mimeFromUrl_ignoresQueryAndFragment() {
        assertEquals(
            "带 query 的地址必须仍能识别扩展名",
            "video/mp4",
            MimeSniffer.mimeFromUrl("https://a.b/c.mp4?token=abc&exp=123")
        )
        assertEquals(
            "带 fragment 的地址必须仍能识别扩展名",
            "video/mp4",
            MimeSniffer.mimeFromUrl("https://a.b/c.mp4#t=10")
        )
        assertEquals(
            "m3u8 带签名参数",
            "application/vnd.apple.mpegurl",
            MimeSniffer.mimeFromUrl("https://a.b/index.m3u8?auth_key=1&sign=2")
        )
    }

    @Test
    fun mimeFromUrl_isCaseInsensitive() {
        assertEquals("video/mp4", MimeSniffer.mimeFromUrl("https://a.b/C.MP4"))
        assertEquals("video/mp4", MimeSniffer.mimeFromUrl("https://A.B/C.Mp4"))
    }

    @Test
    fun mimeFromUrl_supportsLocalFileUrls() {
        assertEquals(
            "已下载视频的 file:// 地址也要能推断",
            "video/mp4",
            MimeSniffer.mimeFromUrl("file:///storage/emulated/0/Download/movie.mp4")
        )
    }

    @Test
    fun mimeFromUrl_returnsNullForUnknownOrMissingExtension() {
        assertNull("未知扩展名", MimeSniffer.mimeFromUrl("https://a.b/c.xyz"))
        assertNull("无扩展名", MimeSniffer.mimeFromUrl("https://a.b/stream"))
        assertNull("以点结尾", MimeSniffer.mimeFromUrl("https://a.b/c."))
        assertNull("隐藏文件名（点开头）", MimeSniffer.mimeFromUrl("https://a.b/.mp4"))
        assertNull("空 URL", MimeSniffer.mimeFromUrl(null))
        assertNull("空白 URL", MimeSniffer.mimeFromUrl("   "))
    }

    // ============ resolve（三级推断）============

    @Test
    fun resolve_prefersExtensionOverProbe() {
        var probed = false
        val mime = MimeSniffer.resolve("https://a.b/c.mp4") {
            probed = true
            "video/x-matroska"
        }
        assertEquals("扩展名命中时不应再发探测请求", "video/mp4", mime)
        assertFalse("扩展名命中时探测回调不应被调用", probed)
    }

    @Test
    fun resolve_usesProbeWhenExtensionUnknown() {
        val mime = MimeSniffer.resolve("https://a.b/stream?x=1") { "video/x-ms-wmv" }
        assertEquals("扩展名未知时应采用探测结果", "video/x-ms-wmv", mime)
    }

    @Test
    fun resolve_normalizesProbeResult() {
        assertEquals(
            "Content-Type 带参数与大小写应被规范化",
            "video/mp4",
            MimeSniffer.resolve("https://a.b/stream") { "  Video/MP4 ; charset=utf-8 " }
        )
        assertEquals(
            "HLS 的 Content-Type 也要能规范化出来",
            "application/vnd.apple.mpegurl",
            MimeSniffer.resolve("https://a.b/play") { "Application/Vnd.Apple.MpegURL" }
        )
    }

    @Test
    fun resolve_fallsBackWhenProbeFailsOrReturnsGarbage() {
        assertEquals(
            "探测抛异常必须静默降级，绝不向上抛",
            DlnaConstants.MIME_FALLBACK,
            MimeSniffer.resolve("https://a.b/stream") { throw IllegalStateException("boom") }
        )
        assertEquals(
            "探测返回 null 走兜底",
            DlnaConstants.MIME_FALLBACK,
            MimeSniffer.resolve("https://a.b/stream") { null }
        )
        assertEquals(
            "探测返回不含 / 的垃圾串走兜底",
            DlnaConstants.MIME_FALLBACK,
            MimeSniffer.resolve("https://a.b/stream") { "garbage" }
        )
        assertEquals(
            "无探测回调时走兜底",
            DlnaConstants.MIME_FALLBACK,
            MimeSniffer.resolve("https://a.b/stream")
        )
    }

    // ============ HLS 判定 ============

    @Test
    fun isHlsUrl_detectsM3u8Path() {
        assertTrue(MimeSniffer.isHlsUrl("https://a.b/index.m3u8"))
        assertTrue(MimeSniffer.isHlsUrl("https://a.b/path/INDEX.M3U8?sig=1"))
        assertFalse(MimeSniffer.isHlsUrl("https://a.b/video.mp4"))
        assertFalse("m3u8 出现在 query 里不算", MimeSniffer.isHlsUrl("https://a.b/v.mp4?next=index.m3u8"))
        assertFalse("null", MimeSniffer.isHlsUrl(null))
    }

    @Test
    fun isHlsContentType_coversAllStandardVariants() {
        assertTrue(MimeSniffer.isHlsContentType("application/vnd.apple.mpegurl"))
        assertTrue(MimeSniffer.isHlsContentType("Application/X-MPEGURL"))
        assertTrue(MimeSniffer.isHlsContentType("audio/mpegurl"))
        assertTrue(MimeSniffer.isHlsContentType("audio/x-mpegurl"))
        assertTrue("带参数的也要认", MimeSniffer.isHlsContentType("application/vnd.apple.mpegurl; charset=utf-8"))
        assertFalse(MimeSniffer.isHlsContentType("video/mp4"))
        assertFalse(MimeSniffer.isHlsContentType(null))
    }

    @Test
    fun isHls_urlOrContentTypeEitherHits() {
        assertTrue(MimeSniffer.isHls("https://a.b/x.m3u8", null))
        assertTrue(MimeSniffer.isHls("https://a.b/play", "application/x-mpegURL"))
        assertFalse(MimeSniffer.isHls("https://a.b/x.mp4", "video/mp4"))
    }

    // ============ protocolInfo ============

    @Test
    fun protocolInfo_declaresSeekSupportAndDlnaFlags() {
        val info = MimeSniffer.protocolInfo("video/mp4")
        assertTrue("必须声明 http-get 协议", info.startsWith("http-get:*:video/mp4:"))
        assertTrue("OP=01 声明支持字节级 seek（Range 代理正为此）", info.contains("DLNA.ORG_OP=01"))
        assertTrue("CI=0 表示不转码", info.contains("DLNA.ORG_CI=0"))
        assertTrue(
            "FLAGS 为 32 位十六进制（128 bit）",
            info.contains("DLNA.ORG_FLAGS=${DlnaConstants.DLNA_FLAGS}")
        )
        assertEquals("FLAGS 长度必须是 32 个十六进制字符", 32, DlnaConstants.DLNA_FLAGS.length)
    }
}
