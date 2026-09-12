package io.legado.app.help.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * video-proxy-m3u8-403 JVM 单元测试
 *
 * 覆盖 extractPlayerPageUrl 的双守卫（经公共入口 resolvePlayerPageUrl 验证）：
 * - 守卫 A（媒体端点路径末段全等 m3u8/mpd/mp4）：壳不解包；/m3u8player/ 类播放器页不误伤
 * - 守卫 B（外层 query 持有鉴权参数）：壳不解包；内层编码 URL 自带 %26exp%3D 不误判
 * - 回归：经典 /player/?url= 与 /player/?playUrl= 解包行为不变
 *
 * Object 初始化 JVM 安全（ConcurrentHashMap/Regex/CoroutineScope），AppLog 由
 * gradle returnDefaultValues 兜底（参考 EngineTest 注释）。
 */
class VideoUrlExtractorTest {

    // ==================== 守卫 A：媒体端点路径 ====================

    @Test
    fun guardA_mediaEndpointPath_shellNotUnwrapped() {
        // 实测 403 场景：壳即鉴权清单端点，解包会丢弃 exp/token 签名
        val shell = "https://www.example-a.com/media/m3u8?url=https%3A%2F%2Fxv.example-b.cn%2Fvideos5%2Fabc%2Fabc.m3u8%3Fv%3D3%26time%3D0&exp=1789182851&token=aca439bc"
        assertEquals(shell, VideoUrlExtractor.resolvePlayerPageUrl(shell))
    }

    @Test
    fun guardA_trailingSlashMediaEndpoint_shellNotUnwrapped() {
        // 红队 R2-1：尾斜杠形态同样命中
        val shell = "https://www.example-a.com/media/m3u8/?url=https%3A%2F%2Fxv.example-b.cn%2Fvideo.m3u8&token=t1"
        assertEquals(shell, VideoUrlExtractor.resolvePlayerPageUrl(shell))
    }

    @Test
    fun guardA_mpdEndpoint_shellNotUnwrapped() {
        val shell = "https://www.example-a.com/media/mpd?url=https%3A%2F%2Fcdn.example-b.com%2Fvideo.mpd&sign=s"
        assertEquals(shell, VideoUrlExtractor.resolvePlayerPageUrl(shell))
    }

    @Test
    fun guardA_m3u8PlayerHtmlPage_stillUnwrapped() {
        // 路径末段 "m3u8player" 非全等 → 守卫 A 不命中 → 照常解包
        val page = "https://v.example.com/m3u8player/?url=https%3A%2F%2Fsrc.example.com%2Findex.m3u8"
        assertEquals("https://src.example.com/index.m3u8", VideoUrlExtractor.resolvePlayerPageUrl(page))
    }

    // ==================== 守卫 B：外层鉴权参数 ====================

    @Test
    fun guardB_outerAuthParam_shellNotUnwrapped() {
        // 壳路径无媒体段，但外层持有签名
        val shell = "https://www.example-a.com/api/proxy?url=https%3A%2F%2Fcdn.example-b.com%2Fa.m3u8&sign=abcd1234"
        assertEquals(shell, VideoUrlExtractor.resolvePlayerPageUrl(shell))
    }

    @Test
    fun guardB_innerEncodedAuthParam_notMisjudged() {
        // 关键反例：内层编码 URL 自带 %26exp%3D123（exp 在内层且已编码），外层无鉴权参数
        // → 守卫 B 不得命中，仍照常解包
        val page = "https://v.example.com/player/?url=https%3A%2F%2Fsrc.example.com%2Fa.m3u8%3Fexp%3D123%26via%3Ddouyin"
        assertEquals(
            "https://src.example.com/a.m3u8?exp=123&via=douyin",
            VideoUrlExtractor.resolvePlayerPageUrl(page)
        )
    }

    // ==================== 回归：解包行为不变 ====================

    @Test
    fun regression_classicPlayerPageUrl_stillUnwrapped() {
        val page = "https://v.example.com/player/?url=https%3A%2F%2Fsrc.example.com%2Findex.m3u8"
        assertEquals("https://src.example.com/index.m3u8", VideoUrlExtractor.resolvePlayerPageUrl(page))
    }

    @Test
    fun regression_playUrlPlayerPage_stillUnwrapped() {
        val page = "https://v.example.com/player/?playUrl=https%3A%2F%2Fsrc.example.com%2Fvideo.mp4"
        assertEquals("https://src.example.com/video.mp4", VideoUrlExtractor.resolvePlayerPageUrl(page))
    }

    @Test
    fun regression_nonVideoParamPage_returnsOriginal() {
        val page = "https://v.example.com/page?next=https%3A%2F%2Fv.example.com%2Flist.html"
        assertEquals(page, VideoUrlExtractor.resolvePlayerPageUrl(page))
    }

    @Test
    fun regression_plainVideoStreamUrl_returnsOriginal() {
        val url = "https://cdn.example.com/path/video.m3u8?a=1"
        assertEquals(url, VideoUrlExtractor.resolvePlayerPageUrl(url))
    }

    // ==================== 边界 ====================

    @Test
    fun edge_emptyUrl_noThrow() {
        assertEquals("", VideoUrlExtractor.resolvePlayerPageUrl(""))
    }

    @Test
    fun edge_malformedUrl_noThrow() {
        // 不抛异常，行为与守卫前一致（返回原样或 null 兜底后原样）
        val garbage = "not-a-url?url=%ZZ&exp=1"
        assertEquals(garbage, VideoUrlExtractor.resolvePlayerPageUrl(garbage))
    }

    @Test
    fun edge_upperCaseMediaEndpoint_guardHits() {
        // 大小写不敏感
        val shell = "https://www.example-a.com/MEDIA/M3U8?url=https%3A%2F%2Fcdn.example-b.com%2Fa.m3u8&token=t"
        assertEquals(shell, VideoUrlExtractor.resolvePlayerPageUrl(shell))
    }

    @Test
    fun edge_expiresParam_notShadowedByExpBranch() {
        // expires= 的 exp 后是 i，exp= 分支不得拦截expires 自身匹配（正则语义校验）
        val shell = "https://www.example-a.com/api/p?url=https%3A%2F%2Fcdn.example-b.com%2Fa.m3u8&expires=999"
        assertEquals(shell, VideoUrlExtractor.resolvePlayerPageUrl(shell))
    }

    @Test
    fun guardA_isDirectVideoStreamUrl_untouched() {
        // 顺带回归 isDirectVideoStreamUrl（路径末段判断的既有消费方语义不受影响）
        assertTrue(VideoUrlExtractor.isDirectVideoStreamUrl("https://cdn.example.com/a/index.m3u8?token=x"))
    }
}
