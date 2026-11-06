package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 续播守卫决策表（design §3.1 / §7.1）
 *
 * 覆盖：五类阻断原因 + 全部放行场景 + 优先级顺序（切换中压过其它）。
 */
class AdvanceGuardPolicyTest {

    @Test
    fun `全部条件正常时放行`() {
        assertNull(
            advanceBlockReason(
                switching = false,
                layoutSwitching = false,
                playerValid = true,
                playError = false
            )
        )
    }

    @Test
    fun `切换中优先阻断`() {
        assertEquals(
            "switching",
            advanceBlockReason(
                switching = true,
                layoutSwitching = true,
                playerValid = false,
                playError = true,
                countdownPending = true
            )
        )
    }

    @Test
    fun `布局切换阻断`() {
        assertEquals(
            "layout_switching",
            advanceBlockReason(
                switching = false,
                layoutSwitching = true,
                playerValid = false,
                playError = true
            )
        )
    }

    @Test
    fun `播放器无效阻断`() {
        assertEquals(
            "player_invalid",
            advanceBlockReason(
                switching = false,
                layoutSwitching = false,
                playerValid = false,
                playError = true
            )
        )
    }

    @Test
    fun `上次播放失败阻断`() {
        assertEquals(
            "play_error",
            advanceBlockReason(
                switching = false,
                layoutSwitching = false,
                playerValid = true,
                playError = true
            )
        )
    }

    @Test
    fun `倒计时等待中视为重复`() {
        assertEquals(
            "countdown_pending",
            advanceBlockReason(
                switching = false,
                layoutSwitching = false,
                playerValid = true,
                playError = false,
                countdownPending = true
            )
        )
    }

    @Test
    fun `倒计时默认参数为不阻断`() {
        assertNull(
            advanceBlockReason(
                switching = false,
                layoutSwitching = false,
                playerValid = true,
                playError = false
            )
        )
    }
}
