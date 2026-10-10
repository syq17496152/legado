package io.legado.app.model.localBook.epubcore.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板选定策略单测（epub-md-rich-rendering 阶段 4.8a 配对 / SP-06）。
 *
 * 覆盖：显式应用优先；应用模板已删除时回落主题默认；默认款随日夜；
 * 偏好序列缺位时的两级兜底；可用集为空返回 null；「跟随主题」哨兵语义。
 */
class ReaderTemplateSelectionTest {

    private val all = listOf(
        "builtin.neon-night",
        "builtin.minimal-ink",
        "builtin.paper-scroll",
        "user.abc"
    )

    @Test
    fun `显式应用的模板优先于主题默认`() {
        val id = ReaderTemplateSelection.resolveId("builtin.paper-scroll", preferNight = true, availableIds = all)
        assertEquals("builtin.paper-scroll", id)
    }

    @Test
    fun `显式应用的是用户模板也生效`() {
        val id = ReaderTemplateSelection.resolveId("user.abc", preferNight = false, availableIds = all)
        assertEquals("user.abc", id)
    }

    @Test
    fun `应用模板已被删除时回落主题默认而不是失效`() {
        val night = ReaderTemplateSelection.resolveId("user.gone", preferNight = true, availableIds = all)
        val day = ReaderTemplateSelection.resolveId("user.gone", preferNight = false, availableIds = all)
        assertEquals("builtin.neon-night", night)
        assertEquals("builtin.minimal-ink", day)
    }

    @Test
    fun `跟随主题在暗色下取霓虹夜行`() {
        val id = ReaderTemplateSelection.resolveId(
            ReaderTemplateSelection.FollowTheme, preferNight = true, availableIds = all
        )
        assertEquals("builtin.neon-night", id)
    }

    @Test
    fun `跟随主题在浅色下取浅色款`() {
        val id = ReaderTemplateSelection.resolveId(
            ReaderTemplateSelection.FollowTheme, preferNight = false, availableIds = all
        )
        assertEquals("builtin.minimal-ink", id)
    }

    @Test
    fun `浅色缺浅色款时回落暗色默认款而非无模板`() {
        val onlyNight = listOf("builtin.neon-night", "user.abc")
        val id = ReaderTemplateSelection.resolveId(
            ReaderTemplateSelection.FollowTheme, preferNight = false, availableIds = onlyNight
        )
        assertEquals("builtin.neon-night", id)
    }

    @Test
    fun `偏好序列全落空时取可用集首个`() {
        val custom = listOf("user.abc", "user.def")
        val id = ReaderTemplateSelection.resolveId(
            ReaderTemplateSelection.FollowTheme, preferNight = true, availableIds = custom
        )
        assertEquals("user.abc", id)
    }

    @Test
    fun `无任何可用模板时返回空表示用不了`() {
        assertNull(ReaderTemplateSelection.resolveId("user.abc", preferNight = true, availableIds = emptyList()))
        assertNull(ReaderTemplateSelection.defaultIdForTheme(preferNight = false, availableIds = emptyList()))
    }

    @Test
    fun `跟随主题哨兵语义 空串与空白均视为跟随`() {
        assertTrue(ReaderTemplateSelection.isFollowTheme(""))
        assertTrue(ReaderTemplateSelection.isFollowTheme("   "))
        assertFalse(ReaderTemplateSelection.isFollowTheme("user.abc"))
    }

    @Test
    fun `id 匹配严格区分大小写不做模糊命中`() {
        val id = ReaderTemplateSelection.resolveId("Builtin.Neon-Night", preferNight = true, availableIds = all)
        assertEquals("内置 id 大小写不符时不得命中，应回落主题默认", "builtin.neon-night", id)
    }

    @Test
    fun `偏好序列常量与设计指定一致`() {
        assertTrue("暗色默认款为霓虹夜行", "builtin.neon-night" in ReaderTemplateSelection.NightPreference)
        assertTrue("浅色默认款序列不得为空", ReaderTemplateSelection.DayPreference.isNotEmpty())
    }
}
