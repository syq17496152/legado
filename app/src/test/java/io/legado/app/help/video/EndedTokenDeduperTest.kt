package io.legado.app.help.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 结束令牌去重单测（design §1.5 / tasks 2.3）
 */
class EndedTokenDeduperTest {

    @Before
    fun setUp() {
        EndedTokenDeduper.reset()
    }

    @Test
    fun `首次放行`() {
        assertTrue(EndedTokenDeduper.allow("s1#2", now = 1000L))
    }

    @Test
    fun `窗口内同令牌丢弃`() {
        assertTrue(EndedTokenDeduper.allow("s1#2", now = 1000L))
        assertFalse(EndedTokenDeduper.allow("s1#2", now = 1500L))
        assertFalse(EndedTokenDeduper.allow("s1#2", now = 2199L))
    }

    @Test
    fun `窗口外同令牌放行`() {
        assertTrue(EndedTokenDeduper.allow("s1#2", now = 1000L))
        assertTrue(EndedTokenDeduper.allow("s1#2", now = 2200L))
    }

    @Test
    fun `不同集令牌立即放行`() {
        assertTrue(EndedTokenDeduper.allow("s1#2", now = 1000L))
        assertTrue(EndedTokenDeduper.allow("s1#3", now = 1001L))
    }

    @Test
    fun `其他入口同令牌在窗口内被丢弃`() {
        // 模拟 player 与 activity 两次回调使用同一令牌
        assertTrue(EndedTokenDeduper.allow("s9#0", now = 5000L))
        assertFalse(EndedTokenDeduper.allow("s9#0", now = 5300L))
    }

    @Test
    fun `复位后放行`() {
        assertTrue(EndedTokenDeduper.allow("s1#2", now = 1000L))
        EndedTokenDeduper.reset()
        assertTrue(EndedTokenDeduper.allow("s1#2", now = 1001L))
    }

    // ==================== 真机回归（2026-11-07 跳集铁证）====================

    @Test
    fun `同一播放器令牌稳定 不同播放器令牌不同`() {
        assertEquals("player:42", EndedTokenDeduper.playerToken(42))
        assertEquals(EndedTokenDeduper.playerToken(42), EndedTokenDeduper.playerToken(42))
        assertNotEquals(EndedTokenDeduper.playerToken(42), EndedTokenDeduper.playerToken(43))
    }

    @Test
    fun `第一个入口推进改写索引后 第二个入口仍被去重`() {
        // 铁证场景：fragment 入口推进 3→4 后，player 入口用同一播放器再次到达；
        // 令牌只依赖播放器实例，不随索引变化 ⇒ 必须被丢弃，禁止跳成 5
        val token = EndedTokenDeduper.playerToken(7788)
        assertTrue(EndedTokenDeduper.allow(token, now = 1000L))
        // 期间索引已从 3 变成 4（令牌不受影响）
        assertFalse(EndedTokenDeduper.allow(token, now = 1005L))
    }
}
