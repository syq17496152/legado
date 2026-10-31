package io.legado.app.help.readaloud.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8.6 段级换声的文件名标记（纯函数）：播放侧靠它区分"同段非末段/末段"，
 * 保证进度账仍按段落计，而不是按子段乱走。
 */
class HttpSegmentVoicePlanTest {

    @Test
    fun `段级文件名带段落与末段标记`() {
        assertEquals("sv_3_l0_abc", HttpSegmentVoicePlan.fileName(3, last = false, baseName = "abc"))
        assertEquals("sv_3_l1_abc", HttpSegmentVoicePlan.fileName(3, last = true, baseName = "abc"))
    }

    @Test
    fun `非末段判定只对 l0 成立`() {
        assertTrue(HttpSegmentVoicePlan.isNonLastSegment("sv_3_l0_abc"))
        assertFalse(HttpSegmentVoicePlan.isNonLastSegment("sv_3_l1_abc"))
        // 旧单声路径文件（md5）与停顿静音都不算段级非末段
        assertFalse(HttpSegmentVoicePlan.isNonLastSegment("aaaaaaaaaaaaaaaa"))
        assertFalse(HttpSegmentVoicePlan.isNonLastSegment("pause_420"))
        assertFalse(HttpSegmentVoicePlan.isNonLastSegment(null))
    }

    @Test
    fun `段落下标可回解 非段级文件返回空`() {
        assertEquals(3, HttpSegmentVoicePlan.paragraphIndexOf("sv_3_l1_abc"))
        assertEquals(0, HttpSegmentVoicePlan.paragraphIndexOf("sv_0_l0_abc"))
        assertNull(HttpSegmentVoicePlan.paragraphIndexOf("abc"))
        assertNull(HttpSegmentVoicePlan.paragraphIndexOf(null))
    }
}
