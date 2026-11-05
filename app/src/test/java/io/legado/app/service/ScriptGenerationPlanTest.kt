package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.5 请求规划：窗口与正文切段（服务与听书中心同源）。
 *
 * 口径出错时的用户可见后果：手动「重新生成」却把下一章也重算（白烧电）、
 * 空行被当成一段让模型判角色（浪费批次）、非法章号让队列塞进脏任务。
 */
class ScriptGenerationPlanTest {

    @Test
    fun `自动生成 = 当前章 + 预取 1 章`() {
        assertEquals(listOf(3, 4), ScriptGenerationPlan.chaptersFor(3, force = false))
    }

    @Test
    fun `手动重新生成只做当前章`() {
        assertEquals(listOf(3), ScriptGenerationPlan.chaptersFor(3, force = true))
    }

    @Test
    fun `非法章号不入队`() {
        assertTrue(ScriptGenerationPlan.chaptersFor(-1, force = false).isEmpty())
        assertTrue(ScriptGenerationPlan.chaptersFor(-1, force = true).isEmpty())
    }

    @Test
    fun `正文切段丢弃空行并去首尾空白`() {
        assertEquals(
            listOf("第一段", "第二段"),
            ScriptGenerationPlan.paragraphsOf("  第一段  \n\n\t\n第二段\n")
        )
    }

    @Test
    fun `正文为空返回空表`() {
        assertTrue(ScriptGenerationPlan.paragraphsOf(null).isEmpty())
        assertTrue(ScriptGenerationPlan.paragraphsOf("   \n\n").isEmpty())
    }

    @Test
    fun `首章也按窗口取后继章且不越界为负`() {
        assertEquals(listOf(0, 1), ScriptGenerationPlan.chaptersFor(0, force = false))
    }
}
