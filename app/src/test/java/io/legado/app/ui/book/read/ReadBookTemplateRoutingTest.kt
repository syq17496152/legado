package io.legado.app.ui.book.read

import io.legado.app.testkit.SourceFileProbe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读页模板路由契约（epub-md-rich-rendering 阶段 4.14 配对）。
 *
 * 存在理由：路由改动落在 5000+ 行的 `ReadBookActivity` 里，而**默认关闭**（4.9）意味着
 * 回归测试跑不到这条路径 ⇒ 一旦漏接（比如忘了在 `keyPage` 里加分支、或忘了在 `onDestroy` 释放），
 * 症状只有"开了模板的用户"能遇到：翻页没反应 / WebView 泄漏 / 主题切换不生效。
 * 故把这几处接线钉在源码层。
 */
class ReadBookTemplateRoutingTest {

    private val activity = "ui/book/read/ReadBookActivity.kt"

    private fun text(): String = SourceFileProbe.sourceText(activity)

    @Test
    fun `开关关闭时不得进入模板面`() {
        val s = text()
        assertTrue("必须有整体开关判据", s.contains("ReaderTemplateManager.templatesEnabled()"))
        assertTrue("解析入口必须存在", s.contains("private suspend fun ensureTemplateAwait()"))
        assertTrue(
            "无生效模板必须回落既有面（否则开关形同虚设）",
            s.contains("loadTextRichContentAwait(relativePosition, resetPageOffset, success)")
        )
    }

    @Test
    fun `两处内容分派都接了模板面`() {
        val s = text()
        // 1 处声明 + 2 处调用点（upContent / upContentAwait）⇒ 命名出现次数至少 3
        assertTrue(
            "upContent/upContentAwait 两处分派都要接（当前命名出现 ${Regex("loadTemplateContentAwait").findAll(s).count()} 次）",
            Regex("loadTemplateContentAwait").findAll(s).count() >= 3
        )
        assertTrue("模板面必须有独立载入函数", s.contains("private suspend fun loadTemplateContentAwait("))
    }

    @Test
    fun `翻页主题切换与进度都覆盖模板面`() {
        val s = text()
        assertTrue("keyPage 必须有模板分支", s.contains("if (templateActive) {") && s.contains("backend.pageNext()"))
        assertTrue("主题/字号变更必须重载模板面", s.contains("private fun reloadTemplateCurrent()"))
        assertTrue("进度条必须覆盖模板面", s.contains("if (textRichActive || templateActive) {"))
    }

    @Test
    fun `位置保存恢复与资源释放齐备`() {
        val s = text()
        assertTrue("onPause 必须保存模板位置", s.contains("saveTemplatePosition()"))
        assertTrue("首帧后必须恢复位置", s.contains("private fun onTemplateRendered("))
        assertTrue("onDestroy 必须释放", s.contains("switchTemplate(false)"))
        assertTrue("两面共用承载位 ⇒ 必须互斥", s.contains("if (templateActive) switchTemplate(false)"))
    }

    @Test
    fun `模板面与文本富渲染面共用同一承载位`() {
        val s = text()
        assertTrue(
            "可见性判据必须同源（否则两个 WebView 同时可见）",
            s.contains("textRichReadView.isVisible = textRichActive || templateActive")
        )
        assertFalse("不得为模板新增独立承载位节点（避免布局漂移）", s.contains("templateReadView"))
    }

    @Test
    fun `装饰档位与动效随真值下发且支持系统减少动效`() {
        // 4.8d：装饰是用户级设置，必须在**每次渲染时**从偏好单源重算（否则改档位后不生效）
        val s = text()
        assertTrue("档位必须来自管理门面（单源）", s.contains("ReaderTemplateManager.decorationCssValue(reduceMotion)"))
        assertTrue("动效必须来自管理门面（单源）", s.contains("ReaderTemplateManager.decorationMotionEnabled(reduceMotion)"))
        assertTrue("必须探测系统减少动效", s.contains("private fun isSystemReduceMotion()"))
        assertTrue(
            "减少动效必须用系统动画缩放判据（与 CSS prefers-reduced-motion 同源）",
            s.contains("Settings.Global.ANIMATOR_DURATION_SCALE")
        )
        assertTrue(
            "解析日志必须带装饰档位（真机 L2 的宿主侧证据；缺了只能靠猜沙箱有没有收到）",
            s.contains("decor=\${ReaderTemplateManager.decorationCssValue(isSystemReduceMotion())}")
        )
    }

    @Test
    fun `动效生命周期与稳定随机已接线`() {
        // 4.15 C6：沙箱看不到宿主手势/生命周期 ⇒ 必须由宿主在 DOWN/UP 与 onPause/onResume 下令
        val s = text()
        assertTrue("必须有触摸期停动效入口", s.contains("override fun dispatchTouchEvent(ev: MotionEvent)"))
        assertTrue("按下即停（拖动跟手）", s.contains("MotionEvent.ACTION_DOWN -> templateBackend?.setMotionPaused(true)"))
        assertTrue(
            "抬手/取消即恢复",
            s.contains("MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->") &&
                s.contains("templateBackend?.setMotionPaused(false)")
        )
        assertTrue(
            "离页必须停（onPause 处下发）",
            Regex("setMotionPaused\\(true\\)").findAll(s).count() >= 2
        )
        assertTrue(
            "回前台必须恢复（onResume 处下发）",
            Regex("setMotionPaused\\(false\\)").findAll(s).count() >= 2
        )
        // 4.15 C4：稳定随机种子随真值下发（同章同值）
        assertTrue("种子必须由书名+章节稳定推导", s.contains("ReaderTemplateSeed.of(bookName, chapterTitle)"))
        assertTrue("种子必须进 ReaderValues", s.contains("seed = ReaderTemplateSeed.of("))
    }
}