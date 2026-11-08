package io.legado.app.help.exoplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix-live-hls-manifest-cache：清单 URL 判定（JVM 纯逻辑）。
 *
 * 判据必须覆盖：
 * - HLS/DASH 清单后缀（大小写不敏感）
 * - `format/type=m3u8|mpd` 形态（动态 URL 不以 .m3u8 结尾）
 * - 分片不得误判（尤其路径里含 `m3u8` 但实际是分片）
 */
class ManifestUrlMatcherTest {

    @Test
    fun hlsManifestWithQuery_isManifest() {
        assertTrue(isManifestUrl("https://cdn.example/hls/1/master/1_auto.m3u8?pkey=abc"))
    }

    @Test
    fun hlsManifestUpperCase_isManifest() {
        assertTrue(isManifestUrl("https://cdn.example/hls/1/master/1_AUTO.M3U8"))
    }

    @Test
    fun m3uSuffix_isManifest() {
        assertTrue(isManifestUrl("https://cdn.example/list.m3u"))
    }

    @Test
    fun dashManifest_isManifest() {
        assertTrue(isManifestUrl("https://cdn.example/live/manifest.mpd?token=1"))
    }

    @Test
    fun formatQueryM3u8_isManifest() {
        assertTrue(isManifestUrl("https://cdn.example/play?format=m3u8&id=1"))
    }

    @Test
    fun typeQueryMpd_isManifest() {
        assertTrue(isManifestUrl("https://cdn.example/play?id=1&type=mpd"))
    }

    @Test
    fun proxyRelativePathQuery_isManifest() {
        assertTrue(isManifestUrl("https://cdn.example/api/v1/m3u8/proxy?path=sub/index.m3u8"))
    }

    @Test
    fun proxyDashRelativePathQuery_isManifest() {
        assertTrue(isManifestUrl("https://cdn.example/api/proxy?path=live/manifest.mpd"))
    }

    @Test
    fun absoluteUrlQueryValue_isNotManifest() {
        // 历史铁证：绝对 URL 作为 query 值时过宽匹配会导致 3002，必须交给内容预检
        assertFalse(
            isManifestUrl(
                "https://cdn.example/Play.php?uu=https%3A%2F%2Fcdn2.example%2Findex.m3u8"
            )
        )
    }

    @Test
    fun tsSegment_isNotManifest() {
        assertFalse(isManifestUrl("https://cdn.example/b-hls/1/1_191_seg.ts"))
    }

    @Test
    fun mp4Segment_isNotManifest() {
        assertFalse(isManifestUrl("https://cdn.example/b-hls/1/1_192_seg.mp4"))
    }

    @Test
    fun pathContainsM3u8ButSegment_isNotManifest() {
        assertFalse(isManifestUrl("https://cdn.example/m3u8/segment/1.mp4"))
    }

    @Test
    fun formatMp4_isNotManifest() {
        assertFalse(isManifestUrl("https://cdn.example/play?format=mp4"))
    }

    @Test
    fun backupSuffix_isNotManifest() {
        assertFalse(isManifestUrl("https://cdn.example/list.m3u8.bak"))
    }

    @Test
    fun blank_isNotManifest() {
        assertFalse(isManifestUrl(""))
        assertFalse(isManifestUrl("   "))
    }
}
