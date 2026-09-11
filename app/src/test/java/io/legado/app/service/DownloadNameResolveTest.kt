package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1/3.2：下载产物文件名解析纯函数单测（resolveVideoFileName）。
 * 覆盖：无标题补默认 .mp4 / 伪后缀防护 / 合法视频扩展名保留 / URL 推断 / 非法字符清洗。
 */
class DownloadNameResolveTest {

    private val exts = setOf("mp4", "mkv", "webm", "avi", "mov", "flv", "wmv", "3gp", "m4v", "m2ts", "ts", "rmvb", "rm", "f4v")

    @Test
    fun `标题无扩展名补默认mp4`() {
        assertEquals("第1集.mp4", resolveVideoFileName("第1集", "https://a/video/1", exts))
    }

    @Test
    fun `合法视频扩展名保留`() {
        assertEquals("film.mp4", resolveVideoFileName("film.mp4", "u", exts))
        assertEquals("film.MKV", resolveVideoFileName("film.MKV", "u", exts))
        assertEquals("film.webm", resolveVideoFileName("film.webm", "u", exts))
    }

    @Test
    fun `伪后缀防护_点后段非视频扩展仍补mp4`() {
        assertEquals("xx.4K.mp4", resolveVideoFileName("xx.4K", "u", exts))
        assertEquals("第1.5集.mp4", resolveVideoFileName("第1.5集", "u", exts))
        assertEquals("预告片1080P高清.mp4", resolveVideoFileName("预告片1080P高清", "u", exts))
    }

    @Test
    fun `URL推断_有扩展名保留`() {
        assertEquals("v.mp4", resolveVideoFileName(null, "https://h/path/v.mp4?token=1", exts))
    }

    @Test
    fun `URL推断_无扩展名补mp4`() {
        assertEquals("download.mp4", resolveVideoFileName(null, "https://h/path/download", exts))
        assertEquals("video.mp4", resolveVideoFileName("  ", "https://h/path/", exts))
    }

    @Test
    fun `非法字符清洗`() {
        val name = resolveVideoFileName("a:b*c?.mp4", "u", exts)
        assertEquals("a_b_c_.mp4", name)
    }

    @Test
    fun `URL带非视频扩展名时保持原语义_由MIME纠正兜底`() {
        val name = resolveVideoFileName(null, "https://h/p/v.m3u8?x=1", exts)
        // URL 推断分支沿用原语义（含点即保留，m3u8 属 HLS 任务类型处理范围）；
        // DIRECT 误下 m3u8 由 F1 2.2 下载完成 Content-Type 纠正兜底改名
        assertEquals("v.m3u8", name)
    }
}
