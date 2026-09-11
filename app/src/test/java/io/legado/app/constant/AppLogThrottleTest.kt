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
