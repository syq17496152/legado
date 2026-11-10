package io.legado.app.ui.book.read.config

import io.legado.app.testkit.SourceFileProbe
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-10 用户裁定：识别不全时必须能**手动补录/修正角色**，并能改角色-音色绑定。
 *
 * 入口链三段都必须在位（缺一段就是"页面有、用户到不了"）：
 * ① 角色配音面板两个按钮（添加角色 / 管理角色）；
 * ② 行内「编辑」走既有 EditCharacter 动作；
 * ③ Activity 分发到既有角色编辑页/管理页（复用，不另造页面）。
 */
class RoleManualManageEntryTest {

    private val screens by lazy { SourceFileProbe.sourceText("ui/book/read/config/BuiltinTtsScreens.kt") }
    private val activity by lazy { SourceFileProbe.sourceText("ui/book/read/config/BuiltinTtsActivity.kt") }

    @Test
    fun rolesPanelCarriesAddAndManageEntries() {
        assertTrue("缺「＋ 添加角色」按钮", screens.contains("PageAction.AddCharacter"))
        assertTrue("缺「管理角色」按钮", screens.contains("PageAction.ManageCharacters"))
        assertTrue("角色行缺「编辑」入口", screens.contains("PageAction.EditCharacter(row.characterId)"))
    }

    @Test
    fun activityRoutesToExistingCharacterPages() {
        assertTrue("AddCharacter 未接编辑页(新建)", activity.contains("PageAction.AddCharacter -> openCharacterEditor(0L)"))
        assertTrue("ManageCharacters 未接管理页", activity.contains("PageAction.ManageCharacters -> openCharacterManager()"))
        assertTrue("未复用既有角色管理页", activity.contains("BookCharacterManageActivity::class.java"))
        assertTrue("未复用既有角色编辑页", activity.contains("BookCharacterEditActivity::class.java"))
    }

    @Test
    fun pageActionsDeclared() {
        assertTrue(screens.contains("data object AddCharacter : PageAction"))
        assertTrue(screens.contains("data object ManageCharacters : PageAction"))
    }

    /** 识别偏少时必须给出可执行出路（导入 LAC 模型 / 手动补录），不能只让用户困惑 */
    @Test
    fun rolesPanelGuidesWhenRecognitionIsFew() {
        assertTrue("缺「导入 LAC 识别模型」引导", screens.contains("导入 LAC 识别模型"))
        assertTrue("缺「手动补录」引导", screens.contains("手动补录"))
    }
}
