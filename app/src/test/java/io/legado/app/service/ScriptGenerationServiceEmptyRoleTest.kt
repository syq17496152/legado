package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剧本生成执行器：角色为空时的跳过口径（2026-11-05 E2E 修复配套）。
 *
 * 背景：模拟器端到端验证时，章内一个角色都没识别到 ⇒ 执行器静默 return，
 * 用户只看到"剧本没生成"，日志里也查不到原因。现在必须给出可执行文案。
 */
class ScriptGenerationServiceEmptyRoleTest {

    @Test
    fun `角色为空的跳过文案带章号与下一步指引`() {
        assertEquals(
            "第 3 章未识别出角色，跳过剧本生成（可先识别角色）",
            ScriptGenerationService.emptyCharactersHint(3)
        )
    }

    /** 不同章号的文案必须可区分，否则用户无法定位是哪一章卡住 */
    @Test
    fun `不同章号的跳过文案互不混淆`() {
        val first = ScriptGenerationService.emptyCharactersHint(1)
        val tenth = ScriptGenerationService.emptyCharactersHint(10)
        assertTrue(first.contains("第 1 章"))
        assertTrue(tenth.contains("第 10 章"))
        assertTrue("文案缺少可执行指引：$tenth", tenth.contains("先识别角色"))
    }
}
