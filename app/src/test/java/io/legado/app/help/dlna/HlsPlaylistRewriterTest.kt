package io.legado.app.help.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * add-dlna-cast：m3u8 清单重写单测（tasks 5.5 验证标准，AD-04）。
 *
 * 这组用例的重点是**漏改一处即整条链断**：清单里任何一条 URI 没被改写，
 * 渲染端就会绕过代理直连原站，带鉴权的源立刻 403。
 *
 * 验证点：
 * 1. 普通行（相对 / 绝对）都改写
 * 2. master 清单的变体地址：`#EXT-X-STREAM-INF` 的**下一行**
 * 3. `#EXT-X-STREAM-INF` 的**行内 `URI=`** —— 红队第 4 轮抓到的原设计漏项
 * 4. `EXT-X-KEY` / `SESSION-KEY` / `MAP` / `MEDIA` / `I-FRAME-STREAM-INF` / `PART` / `PRELOAD-HINT` / `X-ASSET-URI`
 * 5. `EXT-X-BYTERANGE` **不是** URI 承载点，必须原样保留（并纠正红队误判）
 * 6. 注释与空行原样保留；`\r\n` 行尾不被打乱
 * 7. **自检**：改写后的清单里不得再出现原始 host
 * 8. 相对路径按清单最终 URL 的目录解析（`/绝对`、`../`、`./` 三种形态）
 */
class HlsPlaylistRewriterTest {

    private val playlistUrl = "https://cdn.origin.tv/live/channel/index.m3u8"
    private val proxyPrefix = "/cast/tok123/s"

    /** 被测的映射：登记进会话并回一个短 key（与 CastProxyServer 的真实行为一致） */
    private fun mapper(counter: AtomicInteger = AtomicInteger(0)): (String) -> String = { uri ->
        // 把解析后的绝对 URI 记进一个旁路列表，供断言"解析是否正确"
        resolvedLog += uri
        "$proxyPrefix${counter.incrementAndGet()}"
    }

    private val resolvedLog = mutableListOf<String>()

    @Test
    fun rewrite_mapsPlainRelativeAndAbsoluteLines() {
        resolvedLog.clear()
        val content = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            seg001.ts
            #EXTINF:10.0,
            https://other.cdn.tv/seg002.ts
        """.trimIndent()

        val out = HlsPlaylistRewriter.rewrite(content, playlistUrl, mapper())

        assertTrue("相对分片应改写为代理路径", out.contains("${proxyPrefix}1"))
        assertTrue("绝对分片也应改写", out.contains("${proxyPrefix}2"))
        assertEquals(
            "相对路径应按清单目录解析成绝对地址",
            "https://cdn.origin.tv/live/channel/seg001.ts",
            resolvedLog[0]
        )
        assertEquals(
            "已是绝对地址的保持不变",
            "https://other.cdn.tv/seg002.ts",
            resolvedLog[1]
        )
        assertFalse("原始分片名不应残留", out.contains("seg001.ts\n"))
    }

    @Test
    fun rewrite_masterPlaylist_variantOnNextLine() {
        val content = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=720x480
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2560000,RESOLUTION=1280x720
            high/index.m3u8
        """.trimIndent()

        val out = HlsPlaylistRewriter.rewrite(content, playlistUrl, mapper())

        assertTrue(out.contains("${proxyPrefix}1"))
        assertTrue(out.contains("${proxyPrefix}2"))
        assertFalse("变体地址不应残留", out.contains("low/index.m3u8"))
    }

    @Test
    fun rewrite_masterPlaylist_inlineUriAttribute() {
        // 原设计只处理"下一行"，漏了 HLS 允许把变体地址写在同行 URI= 里的形态
        val content = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1280000,URI="low/index.m3u8"
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=86000,URI="iframe/index.m3u8"
        """.trimIndent()

        val out = HlsPlaylistRewriter.rewrite(content, playlistUrl, mapper())

        assertFalse("行内 URI= 必须被改写（漏改会让渲染端直连原站）", out.contains("low/index.m3u8"))
        assertFalse("I-FRAME-STREAM-INF 的 URI= 同样要改", out.contains("iframe/index.m3u8"))
        assertTrue("属性名保留", out.contains("URI=\""))
        assertTrue("其余属性保留", out.contains("BANDWIDTH=1280000"))
    }

    @Test
    fun rewrite_encryptionKeyAndInitSegment() {
        val content = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x1234
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:10.0,
            seg001.m4s
        """.trimIndent()

        val out = HlsPlaylistRewriter.rewrite(content, playlistUrl, mapper())

        assertFalse("AES 密钥地址必须改写（否则取密钥时 403）", out.contains("URI=\"key.bin\""))
        assertTrue("METHOD 与 IV 属性必须保留", out.contains("METHOD=AES-128"))
        assertTrue("IV 属性必须保留", out.contains("IV=0x1234"))
        assertFalse("fMP4 初始化段地址必须改写", out.contains("URI=\"init.mp4\""))
    }

    @Test
    fun rewrite_sessionKeyMediaAndDateRange() {
        val content = """
            #EXTM3U
            #EXT-X-SESSION-KEY:METHOD=AES-128,URI="https://keys.tv/sk.bin"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a1",URI="audio/track.m3u8"
            #EXT-X-DATERANGE:ID="ad1",X-ASSET-URI="ads/clip.mp4"
        """.trimIndent()

        val out = HlsPlaylistRewriter.rewrite(content, playlistUrl, mapper())

        assertFalse(out.contains("https://keys.tv/sk.bin"))
        assertFalse(out.contains("audio/track.m3u8"))
        assertFalse("X-ASSET-URI 也是 URI 属性", out.contains("ads/clip.mp4"))
        assertTrue("属性名保留（含 X-ASSET-URI）", out.contains("X-ASSET-URI=\""))
    }

    @Test
    fun rewrite_lowLatencyPartTags() {
        val content = """
            #EXTM3U
            #EXT-X-PART:DURATION=0.5,URI="part1.mp4"
            #EXT-X-PRELOAD-HINT:TYPE=PART,URI="part2.mp4"
        """.trimIndent()

        val out = HlsPlaylistRewriter.rewrite(content, playlistUrl, mapper())

        assertFalse("LL-HLS PART 分片必须改写", out.contains("part1.mp4"))
        assertFalse("PRELOAD-HINT 必须改写", out.contains("part2.mp4"))
    }

    @Test
    fun rewrite_keepsByteRangeTagsUntouched() {
        // EXT-X-BYTERANGE 描述的是"前一条 URI 的字节区间"，本身不含地址 —— 必须原样保留
        val content = """
            #EXTM3U
            #EXTINF:10.0,
            #EXT-X-BYTERANGE:75232@0
            full.ts
            #EXTINF:10.0,
            #EXT-X-BYTERANGE:82112@752321
            full.ts
        """.trimIndent()

        val out = HlsPlaylistRewriter.rewrite(content, playlistUrl, mapper())

        assertTrue("BYTERANGE 标签必须原样保留", out.contains("#EXT-X-BYTERANGE:75232@0"))
        assertTrue(out.contains("#EXT-X-BYTERANGE:82112@752321"))
        assertEquals("两条 URI 都改写（同一 URL 不同区间由代理 Range 支持覆盖）", 2, resolvedLog.size)
        assertEquals("两次解析出的上游地址相同", resolvedLog[0], resolvedLog[1])
    }

    @Test
    fun rewrite_preservesCommentsBlanksAndCrlf() {
        val content = "#EXTM3U\r\n\r\n#EXT-X-VERSION:3\r\nseg1.ts\r\n"
        val out = HlsPlaylistRewriter.rewrite(content, playlistUrl, mapper())

        assertTrue("CRLF 行尾必须保留", out.contains("\r\n"))
        assertTrue("注释行原样保留", out.contains("#EXT-X-VERSION:3"))
        assertTrue("空行保留", out.contains("\r\n\r\n"))
        assertTrue("CRLF 数量与输入一致: $content", out.count { it == '\r' } == content.count { it == '\r' })
    }

    @Test
    fun rewrite_neverLeavesOriginalHostInPlaylist() {
        val content = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXT-X-STREAM-INF:BANDWIDTH=1,URI="variant/index.m3u8"
            seg001.ts
            https://cdn.origin.tv/abs/seg002.ts
        """.trimIndent()

        val out = HlsPlaylistRewriter.rewrite(content, playlistUrl, mapper())

        assertFalse(
            "自检：改写后不得残留原始 host —— 残留即意味着分片会绕过代理直连原站",
            out.contains("cdn.origin.tv")
        )
    }

    // ============ 相对路径解析 ============

    @Test
    fun resolveAbsolute_handlesAllThreeRelativeForms() {
        assertEquals(
            "以 / 开头 → 按 host 根补全",
            "https://cdn.origin.tv/seg.ts",
            HlsPlaylistRewriter.resolveAbsolute(playlistUrl, "/seg.ts")
        )
        assertEquals(
            "../ 回退一级目录",
            "https://cdn.origin.tv/live/seg.ts",
            HlsPlaylistRewriter.resolveAbsolute(playlistUrl, "../seg.ts")
        )
        assertEquals(
            "./ 停留同级",
            "https://cdn.origin.tv/live/channel/seg.ts",
            HlsPlaylistRewriter.resolveAbsolute(playlistUrl, "./seg.ts")
        )
        assertEquals(
            "裸文件名 → 清单所在目录",
            "https://cdn.origin.tv/live/channel/seg.ts",
            HlsPlaylistRewriter.resolveAbsolute(playlistUrl, "seg.ts")
        )
    }

    @Test
    fun resolveAbsolute_keepsAbsoluteAndBlankInput() {
        assertEquals(
            "http 绝对地址不变",
            "http://a.b/c.ts",
            HlsPlaylistRewriter.resolveAbsolute(playlistUrl, "http://a.b/c.ts")
        )
        assertEquals(
            "https 绝对地址不变",
            "https://a.b/c.ts",
            HlsPlaylistRewriter.resolveAbsolute(playlistUrl, "https://a.b/c.ts")
        )
        assertEquals("空串不变", "", HlsPlaylistRewriter.resolveAbsolute(playlistUrl, ""))
    }

    @Test
    fun rewrite_handlesEmptyContent() {
        assertEquals("", HlsPlaylistRewriter.rewrite("", playlistUrl, mapper()))
    }

    @Test
    fun isPlaylist_detectsByContentTypeOrUrl() {
        assertTrue(HlsPlaylistRewriter.isPlaylist("application/vnd.apple.mpegurl", null))
        assertTrue(HlsPlaylistRewriter.isPlaylist(null, "https://a.b/x.m3u8"))
        assertFalse(HlsPlaylistRewriter.isPlaylist("video/mp4", "https://a.b/x.mp4"))
    }
}
