package io.legado.app.model.localBook.epubcore.template

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 模板可用性策略 + 独立备份单测（epub-md-rich-rendering 阶段 4.9/4.10 配对）。
 *
 * 4.9 覆盖【验证标准】「关闭后等同纯文本渲染；无残留；免责提示在场」；
 * 4.10 覆盖【验证标准】「备份/恢复后类型与素材保留」+ 跨版本恢复边界。
 */
class ReaderTemplateAvailabilityAndBackupTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("template-backup-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun decide(
        enabled: Boolean = true,
        kind: ReaderTemplateAvailabilityPolicy.ContentKind = ReaderTemplateAvailabilityPolicy.ContentKind.TEXT_LIKE,
        origin: ReaderTemplateAvailabilityPolicy.Origin = ReaderTemplateAvailabilityPolicy.Origin.BUILTIN,
        confirmed: Boolean = true,
        textMode: Boolean = true
    ) = ReaderTemplateAvailabilityPolicy.decide(enabled, kind, origin, confirmed, textMode)

    // === 4.9 可用性与关闭语义 ===

    @Test
    fun `关闭模板系统时不可用且不提示不显示入口`() {
        val decision = decide(enabled = false)
        assertFalse(decision.usable)
        assertEquals(ReaderTemplateAvailabilityPolicy.Reason.DISABLED, decision.reason)
        assertEquals("关闭的语义是无残留，不得弹提示", "", decision.notice)
        assertFalse("关闭后入口不应显示", decision.showEntry)
    }

    @Test
    fun `非文本类内容不可用并提示不适用且隐藏入口`() {
        val kinds = listOf(
            ReaderTemplateAvailabilityPolicy.ContentKind.PUBLISHED_EPUB,
            ReaderTemplateAvailabilityPolicy.ContentKind.MANGA,
            ReaderTemplateAvailabilityPolicy.ContentKind.IMAGE,
            ReaderTemplateAvailabilityPolicy.ContentKind.VIDEO,
            ReaderTemplateAvailabilityPolicy.ContentKind.AUDIO,
            ReaderTemplateAvailabilityPolicy.ContentKind.PDF
        )
        kinds.forEach { kind ->
            val decision = decide(kind = kind)
            assertFalse("$kind 不应支持模板", decision.usable)
            assertEquals(ReaderTemplateAvailabilityPolicy.Reason.UNSUPPORTED_CONTENT, decision.reason)
            assertTrue("须说明作用范围", decision.notice.contains("仅作用"))
            assertFalse("不支持的内容形态应隐藏入口（避免误点）", decision.showEntry)
        }
    }

    @Test
    fun `未处于文本渲染模式时提示前置条件`() {
        val decision = decide(textMode = false)
        assertFalse(decision.usable)
        assertEquals(ReaderTemplateAvailabilityPolicy.Reason.NOT_TEXT_READING_MODE, decision.reason)
        assertTrue(decision.notice.contains("文本渲染模式"))
        assertTrue("应显示入口以引导开启", decision.showEntry)
    }

    @Test
    fun `导入模板未显式确认时不可用且给免责提示`() {
        val decision = decide(
            origin = ReaderTemplateAvailabilityPolicy.Origin.IMPORTED,
            confirmed = false
        )
        assertFalse(decision.usable)
        assertEquals(ReaderTemplateAvailabilityPolicy.Reason.NEEDS_CONFIRMATION, decision.reason)
        assertTrue("免责提示须在场", decision.notice.contains("本地使用"))
        assertTrue("导入项应显示入口以便确认", decision.showEntry)
    }

    @Test
    fun `内置与已确认的自编辑模板可用且无提示`() {
        val builtin = decide()
        assertTrue(builtin.usable)
        assertEquals("", builtin.notice)

        val edited = decide(origin = ReaderTemplateAvailabilityPolicy.Origin.USER_EDITED)
        assertTrue(edited.usable)

        val confirmedImport = decide(
            origin = ReaderTemplateAvailabilityPolicy.Origin.IMPORTED,
            confirmed = true
        )
        assertTrue(confirmedImport.usable)
    }

    @Test
    fun `导出免责仅在非内置来源出现`() {
        assertEquals(
            "",
            ReaderTemplateAvailabilityPolicy.exportNotice(ReaderTemplateAvailabilityPolicy.Origin.BUILTIN)
        )
        assertEquals(
            "导入/自编辑模板导出须给完整免责文案",
            ReaderTemplateAvailabilityPolicy.ExportDisclaimer,
            ReaderTemplateAvailabilityPolicy.exportNotice(ReaderTemplateAvailabilityPolicy.Origin.IMPORTED)
        )
        assertEquals(
            ReaderTemplateAvailabilityPolicy.ExportDisclaimer,
            ReaderTemplateAvailabilityPolicy.exportNotice(ReaderTemplateAvailabilityPolicy.Origin.USER_EDITED)
        )
    }

    @Test
    fun `判定优先级为 关闭 高于 内容形态 高于 模式 高于 确认`() {
        assertEquals(
            ReaderTemplateAvailabilityPolicy.Reason.DISABLED,
            decide(
                enabled = false,
                kind = ReaderTemplateAvailabilityPolicy.ContentKind.MANGA,
                origin = ReaderTemplateAvailabilityPolicy.Origin.IMPORTED,
                confirmed = false,
                textMode = false
            ).reason
        )
        assertEquals(
            ReaderTemplateAvailabilityPolicy.Reason.UNSUPPORTED_CONTENT,
            decide(
                kind = ReaderTemplateAvailabilityPolicy.ContentKind.PDF,
                confirmed = false,
                textMode = false
            ).reason
        )
        assertEquals(
            ReaderTemplateAvailabilityPolicy.Reason.NOT_TEXT_READING_MODE,
            decide(
                origin = ReaderTemplateAvailabilityPolicy.Origin.IMPORTED,
                confirmed = false,
                textMode = false
            ).reason
        )
    }

    // === 4.10 独立备份与跨版本恢复 ===

    private fun template(id: String, name: String, schemaVersion: Int = 2, type: String = EpubReaderTemplate.TYPE_PAGED) =
        EpubReaderTemplate(
            schemaVersion = schemaVersion,
            id = id,
            name = name,
            description = "说明",
            firstPageHtml = "<article data-reader-flow=\"body\"></article>",
            otherPageHtml = "<article data-reader-flow=\"body\"></article>",
            css = "body{color:#333}",
            javascript = "",
            type = type,
            scrollHtml = if (type == EpubReaderTemplate.TYPE_SCROLL) "<div data-reader-scroll-viewport></div>" else ""
        )

    @Test
    fun `备份恢复保留类型与翻页偏好`() {
        val source = ReaderTemplateRepository(root)
        val sourcePrefs = ReaderTemplatePreferences(root)
        source.save(template("user.a", "甲"))
        source.save(template("user.b", "乙", type = EpubReaderTemplate.TYPE_SCROLL))
        sourcePrefs.rememberPageTurn("user.a", EpubReaderTemplate.TYPE_PAGED, ReaderTemplatePageTurnPolicy.Mode.SIMULATION)

        val backup = ReaderTemplateBackup.export(source.list(), mapOf("user.a" to ReaderTemplatePageTurnPolicy.Mode.SIMULATION.value))

        val targetRoot = Files.createTempDirectory("template-restore").toFile()
        val target = ReaderTemplateRepository(targetRoot)
        val targetPrefs = ReaderTemplatePreferences(targetRoot)
        val result = ReaderTemplateBackup.restore(backup, target, targetPrefs)

        assertTrue(result.succeeded)
        assertEquals(2, result.restored)
        assertEquals(0, result.skipped)
        assertEquals("类型须保留", EpubReaderTemplate.TYPE_SCROLL, target.get("user.b")?.type)
        assertEquals("素材（css）须保留", "body{color:#333}", target.get("user.a")?.css)
        assertEquals(
            "翻页偏好须随备份恢复",
            ReaderTemplatePageTurnPolicy.Mode.SIMULATION,
            targetPrefs.pageTurnMode("user.a")
        )
        targetRoot.deleteRecursively()
    }

    @Test
    fun `高版本备份整体拒绝不做任何改动`() {
        val backup = ReaderTemplateBackup.export(listOf(template("user.a", "甲")), emptyMap())
            .replace("\"schemaVersion\":1", "\"schemaVersion\":9")
        val target = ReaderTemplateRepository(root)
        val result = ReaderTemplateBackup.restore(backup, target, ReaderTemplatePreferences(root))
        assertTrue("必须整体拒绝", result.rejected)
        assertFalse(result.succeeded)
        assertEquals("不得留半导入痕迹", 0, target.count())
        assertTrue("须提示升级应用", result.message.contains("高于当前支持版本"))
    }

    @Test
    fun `低版本备份条目被升级`() {
        val backup = ReaderTemplateBackup.export(listOf(template("user.old", "旧", schemaVersion = 1)), emptyMap())
        val target = ReaderTemplateRepository(root)
        val result = ReaderTemplateBackup.restore(backup, target, ReaderTemplatePreferences(root))
        assertTrue(result.succeeded)
        assertEquals(1, result.restored)
        assertEquals("须记录升级数", 1, result.upgraded)
        assertEquals(2, target.get("user.old")?.schemaVersion)
    }

    @Test
    fun `条目级损坏只跳过其余照常恢复`() {
        val good = template("user.good", "好")
        val backup = ReaderTemplateBackup.export(listOf(good), emptyMap())
            .replace("\"templates\":[", "\"templates\":[{\"schemaVersion\":2,\"id\":\"user.bad\",\"name\":\"坏\",\"type\":\"不存在\"},")
        val target = ReaderTemplateRepository(root)
        val result = ReaderTemplateBackup.restore(backup, target, ReaderTemplatePreferences(root))
        assertTrue(result.succeeded)
        assertEquals(1, result.restored)
        assertTrue("须记录被跳过的条目", result.skipped >= 1)
        assertEquals("user.good", target.get("user.good")?.id)
    }

    @Test
    fun `损坏备份文本优雅拒绝`() {
        val target = ReaderTemplateRepository(root)
        val result = ReaderTemplateBackup.restore("{ 坏", target, ReaderTemplatePreferences(root))
        assertTrue(result.rejected)
        assertEquals(0, target.count())
    }

    @Test
    fun `备份解析往返一致`() {
        val templates = listOf(template("user.a", "甲"), template("user.b", "乙", type = EpubReaderTemplate.TYPE_SCROLL))
        val parsed = ReaderTemplateBackup.parse(ReaderTemplateBackup.export(templates, mapOf("user.a" to 3))).getOrThrow()
        assertEquals(2, parsed.templates.size)
        assertEquals(EpubReaderTemplate.TYPE_SCROLL, parsed.templates[1].type)
        assertEquals(3, parsed.pageTurn["user.a"])
    }

    @Test
    fun `非对象备份被拒`() {
        assertTrue(ReaderTemplateBackup.parse("[1,2]").isFailure)
        assertTrue(ReaderTemplateBackup.parse("null").isFailure)
    }
}