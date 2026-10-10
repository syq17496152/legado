package io.legado.app.constant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F9/3.2：AppLog putThrottled/putSampled 频控机制单测（JVM）。
 * 走 tag+DEBUG 通道（recordLog 关闭时静默返回，不触发 android.util.Log），仅验证计数/去重状态机。
 */
class AppLogThrottleTest {

    @Test
    fun `模块 tag 不重复且新增 tag 有据可查`() {
        // 4.8c 新增 TAG_CODE_EDIT 的配对锚点：tag 是 logcat 过滤与真机取证的唯一键，
        // 两条不同链路的 tag 若同值，真机日志会把它们混在一起（症状：看似"两处都在报错"）。
        val tags = listOf(
            AppLog.TAG_CODE_EDIT,
            AppLog.TAG_READER_TEMPLATE,
            AppLog.TAG_RSS_SOURCE_EDIT,
            AppLog.TAG_HLS_REMUX,
            AppLog.TAG_IMG_DECRYPT
        )
        assertTrue("tag 不得为空", tags.all { it.isNotBlank() })
        assertEquals("模块 tag 必须互不相同", tags.size, tags.toSet().size)
        assertEquals("CodeEdit", AppLog.TAG_CODE_EDIT)
    }

    @Test
    fun `节流_窗口内同key计数累加`() {
        val key = "throttle_test_a"
        repeat(3) { AppLog.putThrottled(key, "msg", tag = "T", level = AppLog.Level.DEBUG) }
        assertEquals(3, AppLog.throttleSnapshot()[key])
    }

    @Test
    fun `节流_不同key独立计数`() {
        AppLog.putThrottled("throttle_test_b1", "m", tag = "T", level = AppLog.Level.DEBUG)
        AppLog.putThrottled("throttle_test_b2", "m", tag = "T", level = AppLog.Level.DEBUG)
        assertEquals(1, AppLog.throttleSnapshot()["throttle_test_b1"])
        assertEquals(1, AppLog.throttleSnapshot()["throttle_test_b2"])
    }

    @Test
    fun `采样_每n条计数递增`() {
        val key = "sample_test_a"
        repeat(10) { AppLog.putSampled(key, "m", n = 5, tag = "T", level = AppLog.Level.DEBUG) }
        assertEquals(10L, AppLog.sampleSnapshot()[key])
    }

    @Test
    fun `采样_n小于等于1_每条直出不计数`() {
        AppLog.putSampled("sample_test_direct", "m", n = 1, tag = "T", level = AppLog.Level.DEBUG)
        assertTrue(AppLog.sampleSnapshot()["sample_test_direct"] == null)
    }

    @Test
    fun `空消息直接忽略`() {
        AppLog.putThrottled("throttle_test_null", null, tag = "T", level = AppLog.Level.DEBUG)
        AppLog.putSampled("sample_test_null", null, n = 5, tag = "T", level = AppLog.Level.DEBUG)
        assertTrue(AppLog.throttleSnapshot()["throttle_test_null"] == null)
        assertTrue(AppLog.sampleSnapshot()["sample_test_null"] == null)
    }
}
