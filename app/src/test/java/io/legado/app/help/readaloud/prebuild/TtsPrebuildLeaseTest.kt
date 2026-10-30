package io.legado.app.help.readaloud.prebuild

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预合成租约/账目纯函数单测（§3.7.3-1 + §3.7.2，tasks 2.11）
 */
class TtsPrebuildLeaseTest {

    @Test
    fun `lease - 当前朗读章命中延后`() {
        val (defer, rounds) = TtsPrebuildManager.leaseDecision(5, null, 5, 0)
        assertTrue(defer)
        assertEquals(1, rounds)
    }

    @Test
    fun `lease - 预下载下一章命中延后`() {
        val (defer, _) = TtsPrebuildManager.leaseDecision(5, 6, 6, 0)
        assertTrue(defer)
    }

    @Test
    fun `lease - 非当前章直接执行`() {
        val (defer, rounds) = TtsPrebuildManager.leaseDecision(5, null, 10, 0)
        assertFalse(defer)
        assertEquals(0, rounds)
    }

    @Test
    fun `lease - 延后 3 轮上限后强制执行`() {
        var rounds = 0
        // 连续 3 轮延后（当前朗读章=任务章 10）
        repeat(3) {
            val (defer, newRounds) = TtsPrebuildManager.leaseDecision(10, null, 10, rounds)
            assertTrue(defer)
            rounds = newRounds
        }
        assertEquals(3, rounds)
        // 第 4 轮强制执行（防任务永不完成）
        val (defer, _) = TtsPrebuildManager.leaseDecision(10, null, 10, rounds)
        assertFalse(defer)
    }

    @Test
    fun `account - 账目等于剩余待合成除以总单元`() {
        val (remaining, total) = TtsPrebuildManager.buildAccount(100, 30, 5)
        assertEquals(65, remaining)
        assertEquals(100, total)
    }

    @Test
    fun `account - 超界不出现负数`() {
        val (remaining, _) = TtsPrebuildManager.buildAccount(10, 12, 3)
        assertEquals(0, remaining)
    }
}
