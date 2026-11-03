package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 听书入口收敛（4.3 / spec R1）：
 * 阅读页「更多选项」是 Compose 硬编码列表（不读 R.menu.book_read），听书入口必须由
 * [builtinTtsOverflowEntries] 显式产出（否则本地书整组不可达，IF-14-C）；
 * 且**只能有一项** —— 预合成/引擎管理已移入听书中心「高级」，再并列就是入口回潮。
 */
class BuiltinTtsOverflowEntriesTest {

    @Test
    fun `只产出一个听书入口`() {
        val actions = builtinTtsOverflowEntries({})
        assertEquals(1, actions.size)
        assertEquals("听书", actions[0].title)
    }

    @Test
    fun `点击听书入口触发打开听书中心`() {
        var scene = 0
        val actions = builtinTtsOverflowEntries(onOpenScene = { scene++ })
        actions[0].invoke()
        assertEquals(1, scene)
    }
}
