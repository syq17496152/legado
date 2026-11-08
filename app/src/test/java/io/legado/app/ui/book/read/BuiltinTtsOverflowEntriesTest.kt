package io.legado.app.ui.book.read

import io.legado.app.testkit.SourceFileProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /**
     * 2026-11-08 真机 FATAL 回归锁（compose-page-regression-sweep）：听书面板分段角色图标
     * 曾直接 `painterResource(R.drawable.ic_bottom_person)`，而它是 `<selector>` ⇒ 组合期崩溃。
     * 同目录配对（G-01）落在本文件。
     */
    @Test
    fun `听书面板角色图标必须引用 vector 资源`() {
        val source = SourceFileProbe.sourceText("ui/book/read/ReadAloudPlayerPanel.kt")
        assertTrue(
            "必须引用 vector 子资源 ic_bottom_person_e/_s",
            source.contains("R.drawable.ic_bottom_person_e") || source.contains("R.drawable.ic_bottom_person_s")
        )
        assertFalse(
            "禁止再对 <selector> 资源 ic_bottom_person 调用 painterResource",
            Regex("""painterResource\(R\.drawable\.ic_bottom_person\)""").containsMatchIn(source)
        )
    }
}
