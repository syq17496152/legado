package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * IF-14-C 回归：阅读页「更多选项」是 Compose 硬编码列表（不读 R.menu.book_read），
 * 内置离线朗读三入口必须由 [builtinTtsOverflowEntries] 显式产出，否则本地书整组不可达。
 */
class BuiltinTtsOverflowEntriesTest {

    @Test
    fun `三个入口按预合成-引擎-场景卡顺序置顶`() {
        val actions = builtinTtsOverflowEntries({}, {}, {})
        assertEquals(3, actions.size)
        assertEquals(
            listOf("批量预合成", "离线多角色引擎", "听书场景卡"),
            actions.map { it.title }
        )
    }

    @Test
    fun `点击各入口触发对应回调`() {
        var prebuild = 0
        var engine = 0
        var scene = 0
        val actions = builtinTtsOverflowEntries(
            onPrebuild = { prebuild++ },
            onOpenEngine = { engine++ },
            onOpenScene = { scene++ }
        )
        actions[0].invoke()
        actions[1].invoke()
        actions[2].invoke()
        assertEquals(1, prebuild)
        assertEquals(1, engine)
        assertEquals(1, scene)
    }
}
