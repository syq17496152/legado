package io.legado.app.ui.book.read

import io.legado.app.testkit.SourceFileProbe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 听书入口唯一化（offline-tts-ux-consolidation AD-01）：
 * 阅读页 ⋮ 菜单不再有「听书」，唯一入口是朗读面板底部 →「听书设置」。
 *
 * 本类只做回归锁：断言"菜单入口已移除 + 面板入口已接线"，防止后续为图方便再挂回第二个入口。
 * 2026-11-08 真机 FATAL 回归锁（compose-page-regression-sweep）同目录配对（G-01）保留在本文件。
 */
class BuiltinTtsOverflowEntriesTest {

    @Test
    fun `阅读菜单不再声明听书项`() {
        val menu = SourceFileProbe.sourceTextByPath("src/main/res/menu/book_read.xml")
        assertFalse(
            "menu_cast_scene 已移除（入口收敛到朗读面板）",
            menu.contains("menu_cast_scene")
        )
        assertFalse(
            "menu_group_tts_tools 已移除",
            menu.contains("menu_group_tts_tools")
        )
    }

    @Test
    fun `溢出菜单不再产出听书入口`() {
        val source = SourceFileProbe.sourceText("ui/book/read/ReadMenuComposeComponents.kt")
        assertFalse(
            "builtinTtsOverflowEntries 已删除，禁止回潮",
            source.contains("builtinTtsOverflowEntries")
        )
        assertFalse(
            "buildOverflowActions 不再接收 onCastSceneClick",
            source.contains("onCastSceneClick")
        )
    }

    @Test
    fun `朗读面板设置动作跳听书设置`() {
        val source = SourceFileProbe.sourceText("ui/book/read/config/ReadAloudDialog.kt")
        assertTrue(
            "面板底部动作已改为「听书设置」",
            source.contains("听书设置")
        )
        assertTrue(
            "必须跳 CastSceneActivity 并带来源",
            source.contains("CastSceneActivity") && source.contains("FROM_READ_DIALOG")
        )
        assertFalse(
            "面板内不得再叠一层配置对话框",
            source.contains("readAloudConfigDialog")
        )
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
