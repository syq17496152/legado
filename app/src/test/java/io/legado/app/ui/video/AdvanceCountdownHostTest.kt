package io.legado.app.ui.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 续播倒计时宿主生命周期（tasks §7.1）
 *
 * 关注真实行为：注册/注销次数必须配对，重复 onResume / onPause 不得重复调用。
 */
class AdvanceCountdownHostTest {

    private var registerCount = 0
    private var unregisterCount = 0
    private val host = AdvanceCountdownHost(
        register = { registerCount++ },
        unregister = { unregisterCount++ }
    )

    @Test
    fun `首次 onResume 注册一次`() {
        host.onResume()
        assertTrue(host.isRegistered)
        assertEquals(1, registerCount)
        assertEquals(0, unregisterCount)
    }

    @Test
    fun `重复 onResume 不重复注册`() {
        host.onResume()
        host.onResume()
        host.onResume()
        assertEquals(1, registerCount)
    }

    @Test
    fun `onPause 注销一次且可再次注册`() {
        host.onResume()
        host.onPause()
        assertFalse(host.isRegistered)
        assertEquals(1, unregisterCount)
        host.onResume()
        assertEquals(2, registerCount)
    }

    @Test
    fun `未注册时 onPause 不注销`() {
        host.onPause()
        assertEquals(0, unregisterCount)
        assertFalse(host.isRegistered)
    }

    @Test
    fun `多轮前后台切换注册与注销始终配对`() {
        repeat(3) {
            host.onResume()
            host.onPause()
        }
        assertEquals(registerCount, unregisterCount)
        assertEquals(3, registerCount)
        assertFalse(host.isRegistered)
    }
}
