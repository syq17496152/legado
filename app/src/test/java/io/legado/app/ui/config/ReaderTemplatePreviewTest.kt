package io.legado.app.ui.config

import io.legado.app.testkit.SourceFileProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板预览弹窗（阶段 4.8b）配对测试。
 *
 * 弹窗本身含 WebView（Fragment + ComposeView），纯 JVM 无法实例化 ⇒ 分两层锁不变量：
 * ①**示例内容**（纯值，可逐项断言）——示例必须含 mermaid/公式，否则"预览正常"什么都证明不了；
 * ②**接线结构**（源码级）——预览必须复用正式渲染链路、弹层取色单源、手势归 WebView、退场释放。
 */
class ReaderTemplatePreviewTest {

    private val dialogSource =
        SourceFileProbe.sourceText("ui/config/ReaderTemplatePreviewDialog.kt")
    private val activitySource =
        SourceFileProbe.sourceText("ui/config/ReaderTemplateManageActivity.kt")

    @Test
    fun `示例章节覆盖富渲染元素与正文元素基线`() {
        val chapter = ReaderTemplatePreviewSample.chapter()
        assertTrue("示例必须声明 mermaid（否则预览掩盖「模板 × 富渲染」这条组合路径）", chapter.hasMermaid)
        assertTrue("示例必须声明公式", chapter.hasMath)
        val html = chapter.html
        assertTrue("必须含 mermaid 节点", html.contains("class=\"mermaid\""))
        assertTrue("必须含块级公式定界符", html.contains("$$"))
        assertTrue("必须含行内公式定界符", html.contains("\$E = mc^2\$"))
        assertTrue("必须用引擎的正文元素类（否则换模板后观感与实际阅读不一致）",
            html.contains("reader-chapter-title") && html.contains("reader-paragraph"))
        assertFalse("示例正文不得自带外壳/背景（外壳由模板提供）", html.contains("<html"))
    }

    @Test
    fun `示例真值随日夜与小时变化`() {
        val night = ReaderTemplatePreviewSample.values(dark = true, hour = 23)
        assertEquals("night", night.themeId)
        assertEquals(23, night.hour)
        assertTrue("进度必须在 0~100（凭空造值会让进度类模板显示成异常态）",
            night.progressPercent in 0..100)
        val day = ReaderTemplatePreviewSample.values(dark = false, hour = 9)
        assertEquals("day", day.themeId)
        assertTrue("默认小时取本机当前小时（时间相位类模板需要）", day.hour in 0..23)
    }

    @Test
    fun `预览走正式渲染链路而非静态示意图`() {
        assertTrue(
            "必须复用 TemplateRenderBackend（与阅读页同一链路 ⇒ 所见即所得）",
            dialogSource.contains("TemplateRenderBackend(")
        )
        assertTrue("必须提供待预览模板", dialogSource.contains("templateProvider ="))
        assertTrue("必须提供示例章节", dialogSource.contains("chapterProvider ="))
        assertTrue("必须提供示例真值（槽位/进度/时间相位）", dialogSource.contains("valuesProvider ="))
        assertTrue("必须真正发起渲染", dialogSource.contains("engine.render("))
        assertTrue(
            "排版参数必须跟随阅读设置（字号/行高/字色）⇒ 预览与阅读页一致",
            dialogSource.contains("ReadBookConfig.textSize") &&
                dialogSource.contains("ReadBookConfig.lineSpacingExtra") &&
                dialogSource.contains("ReadBookConfig.textColor")
        )
        assertTrue("退场必须释放 WebView（否则泄漏渲染进程）", dialogSource.contains("dispose()"))
    }

    @Test
    fun `弹层取色单源且不新增组件族`() {
        assertTrue("取色必须走 rememberAppDialogStyle（K1：弹层取色归属）",
            dialogSource.contains("rememberAppDialogStyle()"))
        assertTrue("必须走单源色板转换", dialogSource.contains("toMiuixPalette()"))
        assertFalse("不得硬编码色值", Regex("0x[0-9A-Fa-f]{6,8}").containsMatchIn(dialogSource))
        assertFalse("不得直接构造 Color", dialogSource.contains("Color("))
        assertTrue("必须复用既有弹层框架（不新增组件族成员）", dialogSource.contains("AppDialogFrame("))
    }

    @Test
    fun `滚动模板预览的手势必须归 WebView 且尺寸就绪后才渲染`() {
        assertTrue(
            "必须关掉弹层外层滚动（否则外层抢走拖动 ⇒ 滚动模板预览滑不动）",
            dialogSource.contains("scrollContent = false")
        )
        assertTrue("必须等 WebView 量到尺寸再渲染（尺寸为 0 ⇒ 沙箱页框算成 0、页数恒 1）",
            dialogSource.contains("renderWhenSized(") && dialogSource.contains("view.width > 0"))
        assertTrue("失败必须显式告知（空白弹窗与「模板没内容」分不开）",
            dialogSource.contains("预览渲染失败"))
    }

    @Test
    fun `管理页提供预览入口`() {
        assertTrue(
            "模板行操作必须含预览项",
            activitySource.contains("AppManagementMenuAction(\"预览\")")
        )
        assertTrue(
            "预览必须把模板交给预览弹窗",
            activitySource.contains("ReaderTemplatePreviewDialog.create(entry.template)")
        )
    }
}