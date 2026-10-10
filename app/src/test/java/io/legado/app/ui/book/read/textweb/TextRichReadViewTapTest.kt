package io.legado.app.ui.book.read.textweb

import io.legado.app.testkit.SourceFileProbe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 富渲染/模板承载容器的**单击识别**配对测试（epub-md-rich-rendering 阶段 4.14 真机修复配套）。
 *
 * 存在理由（真机铁证 2026-10-10）：原先用"在 `ACTION_UP` 处 `onInterceptTouchEvent` 返回 true"
 * 收点击，框架只给子 View 发 CANCEL、**不回调父容器 onTouchEvent** ⇒ 富渲染/模板模式下
 * 点屏幕完全没反应（不出菜单、不翻页，用户被困住，只能返回键退出）。
 *
 * 本测试锁两件事：①点击/拖动的判定口径（纯函数逐值断言）；②收口位置与补 CANCEL 的接线
 * （View 的触摸派发无法在纯 JVM 断言，故用源码级结构不变量兜住"又改回 intercept 写法"的回归）。
 */
class TextRichReadViewTapTest {

    @Test
    fun `阈值内位移判为点击`() {
        assertTrue("零位移", TextRichReadView.isTap(0f, 0f, 8f))
        assertTrue("恰在阈值上", TextRichReadView.isTap(8f, -8f, 8f))
        assertTrue("阈值内抖动仍算点击", TextRichReadView.isTap(3f, -5f, 8f))
    }

    @Test
    fun `越阈值位移判为拖动`() {
        assertFalse("横向越阈", TextRichReadView.isTap(9f, 0f, 8f))
        assertFalse("纵向越阈", TextRichReadView.isTap(0f, 12f, 8f))
        assertFalse("任一轴越阈即算拖动（逐轴口径，与 ScrollView/GestureDetector 一致）",
            TextRichReadView.isTap(7f, 9f, 8f))
        assertFalse("两轴都越阈", TextRichReadView.isTap(9f, 9f, 8f))
    }

    @Test
    fun `点击必须在 dispatchTouchEvent 收口并给子 View 补 CANCEL`() {
        val source = SourceFileProbe.sourceText("ui/book/read/textweb/TextRichReadView.kt")
        assertTrue("点击必须在 dispatchTouchEvent 收口（父容器 UP 处 intercept 不会回调自身 onTouchEvent）",
            source.contains("override fun dispatchTouchEvent"))
        assertTrue("必须先给子 View 补 CANCEL（它已收到 DOWN）", source.contains("ACTION_CANCEL"))
        assertTrue("必须回调 onTap", source.contains("onTap?.invoke"))
        assertFalse("不得再保留 UP 处 intercept 的旧写法（正是它导致点击失效）",
            source.contains("if (!dragging) return true"))
        assertFalse("dispatchTouchEvent 收口后不再需要 onTouchEvent 覆写", source.contains("override fun onTouchEvent"))
    }
}