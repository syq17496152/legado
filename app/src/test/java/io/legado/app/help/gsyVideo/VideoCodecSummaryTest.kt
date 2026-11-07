package io.legado.app.help.gsyVideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tasks §13.1：编解码摘要格式（`Exo2MediaPlayer` 在 STATE_READY 输出一次）。
 */
class VideoCodecSummaryTest {

    @Test
    fun `完整字段按固定顺序输出`() {
        val text = formatCodecSummary(
            codecs = "avc1.64001f",
            sampleMimeType = "video/avc",
            bitrate = 1_200_000,
            width = 1920,
            height = 1080,
        )
        assertEquals(
            "codecs=avc1.64001f, mime=video/avc, bitrate=1200000, size=1920x1080",
            text
        )
    }

    @Test
    fun `缺失字段回落 unknown 不抛异常`() {
        val text = formatCodecSummary(null, null, 0, 0, 0)
        assertTrue(text.startsWith("codecs=unknown, mime=unknown"))
        assertTrue(text.endsWith("size=0x0"))
    }
}
