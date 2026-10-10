package io.legado.app.help.config

import io.legado.app.constant.PreferKey
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplate
import io.legado.app.model.localBook.epubcore.template.ReaderTemplateAvailabilityPolicy
import io.legado.app.model.localBook.epubcore.template.ReaderTemplateSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读页面模板管理器纯逻辑契约测试（epub-md-rich-rendering 阶段 4.8a 配对）。
 *
 * 为什么测这些：管理页所有动作（应用/复制/删除/恢复默认）都建立在**同一份合并目录**之上，
 * 合并规则一旦漂移会出现两类静默缺陷 —— ①同 id 内置与用户条目并存（列表出现两条、点哪条都"像"生效）；
 * ②用户条目顺序不稳（Compose `key` 抖动导致列表复用错位）。故把顺序与去重钉死。
 */
class ReaderTemplateManagerTest {

    private fun entry(
        id: String,
        name: String = id,
        type: String = EpubReaderTemplate.TYPE_PAGED,
        source: ReaderTemplateManager.Source = ReaderTemplateManager.Source.BUILTIN
    ): ReaderTemplateManager.Entry {
        val schema = if (type == EpubReaderTemplate.TYPE_SCROLL) EpubReaderTemplate.SCROLL_SCHEMA_VERSION else EpubReaderTemplate.SCHEMA_VERSION
        return ReaderTemplateManager.Entry(
            template = EpubReaderTemplate(
                schemaVersion = schema,
                id = id,
                name = name,
                type = type,
                firstPageHtml = "<div/>",
                otherPageHtml = "<div/>",
                scrollHtml = "<div/>"
            ),
            source = source
        )
    }

    private fun ids(entries: List<ReaderTemplateManager.Entry>): List<String> = entries.map { it.id }

    @Test
    fun `内置保持目录顺序用户条目按名称排序追加`() {
        val builtin = listOf(entry("builtin.b"), entry("builtin.a", name = "素笺"))
        val user = listOf(
            entry("user.z", name = "b-user", source = ReaderTemplateManager.Source.USER),
            entry("user.y", name = "a-user", source = ReaderTemplateManager.Source.USER)
        )
        assertEquals(
            listOf("builtin.b", "builtin.a", "user.y", "user.z"),
            ids(ReaderTemplateManager.assembleEntries(builtin, user))
        )
    }

    @Test
    fun `同id的用户条目就地覆盖内置且不产生重复条目`() {
        val builtin = listOf(entry("builtin.b"), entry("builtin.a", name = "素笺"))
        val override = entry("builtin.a", name = "我的素笺", source = ReaderTemplateManager.Source.USER)
        val merged = ReaderTemplateManager.assembleEntries(builtin, listOf(override))
        assertEquals(listOf("builtin.b", "builtin.a"), ids(merged))
        assertEquals("覆盖后应保留用户条目（同名两条会让用户分不清点了哪条）", ReaderTemplateManager.Source.USER, merged[1].source)
        assertEquals("我的素笺", merged[1].name)
    }

    @Test
    fun `用户条目自身重复id只保留首个`() {
        val user = listOf(
            entry("user.dup", name = "first", source = ReaderTemplateManager.Source.USER),
            entry("user.dup", name = "second", source = ReaderTemplateManager.Source.USER)
        )
        val merged = ReaderTemplateManager.assembleEntries(emptyList(), user)
        assertEquals(listOf("user.dup"), ids(merged))
        assertEquals("first", merged.first().name)
    }

    @Test
    fun `空集合不崩溃且返回空列表`() {
        assertTrue(ReaderTemplateManager.assembleEntries(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `滚动模板判定沿类型字段`() {
        assertTrue(entry("s", type = EpubReaderTemplate.TYPE_SCROLL).isScroll)
        assertFalse(entry("p").isScroll)
    }

    @Test
    fun `选定策略与模板策略单源一致`() {
        val available = listOf("builtin.minimal-ink", "builtin.neon-night")
        // 显式应用且存在 ⇒ 用应用的
        assertEquals(
            "builtin.neon-night",
            ReaderTemplateManager.resolveEffectiveId("builtin.neon-night", false, available)
        )
        // 跟随主题 ⇒ 浅色取素笺、暗色取霓虹夜行
        assertEquals(
            "builtin.minimal-ink",
            ReaderTemplateManager.resolveEffectiveId(ReaderTemplateSelection.FollowTheme, false, available)
        )
        assertEquals(
            "builtin.neon-night",
            ReaderTemplateManager.resolveEffectiveId(ReaderTemplateSelection.FollowTheme, true, available)
        )
        // 应用的模板已被删除 ⇒ 回落主题默认（而不是"白屏无模板"）
        assertEquals(
            "builtin.minimal-ink",
            ReaderTemplateManager.resolveEffectiveId("builtin.removed", false, available)
        )
        // 无任何可用模板 ⇒ null（调用方据此给提示）
        assertNull(ReaderTemplateManager.resolveEffectiveId(ReaderTemplateSelection.FollowTheme, false, emptyList()))
    }

    @Test
    fun `信任级别与免责提示按来源分流`() {
        // 内置为自研合规素材 ⇒ 导出不打扰；用户库/导入可能含第三方素材 ⇒ 必须有免责
        val builtin = entry("builtin.a")
        val user = entry("user.a", name = "我的模板", source = ReaderTemplateManager.Source.USER)
        assertEquals(ReaderTemplateAvailabilityPolicy.Origin.BUILTIN, ReaderTemplateManager.originOf(builtin))
        assertEquals(ReaderTemplateAvailabilityPolicy.Origin.USER_EDITED, ReaderTemplateManager.originOf(user))
        assertTrue("内置导出不得出现免责提示（自研素材无需自担）", ReaderTemplateManager.exportNotice(builtin).isBlank())
        assertEquals(ReaderTemplateAvailabilityPolicy.ExportDisclaimer, ReaderTemplateManager.exportNotice(user))
    }

    @Test
    fun `导入免责文案与策略单源且非空`() {
        // 4.9：导入必须显式确认 ⇒ 文案非空是与策略一致的最低要求
        assertTrue(ReaderTemplateManager.importDisclaimer.isNotBlank())
        assertEquals(ReaderTemplateAvailabilityPolicy.ImportDisclaimer, ReaderTemplateManager.importDisclaimer)
    }

    @Test
    fun `默认选定值与策略哨兵及键名三方单源`() {
        assertEquals("空串哨兵", "", ReaderTemplateSelection.FollowTheme)
        assertEquals(ReaderTemplateSelection.FollowTheme, ReaderFeatureDefaults.READER_TEMPLATE_ID)
        assertEquals("readerTemplate", PreferKey.readerTemplate)
    }
}