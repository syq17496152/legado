package io.legado.app.ui.book.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真机回归（2026-11-08 00:34）：外部 `--es key` 直达搜索后结果被输入帮助层盖住。
 */
class SearchHelpPolicyTest {

    @Test
    fun `搜索中始终让位结果区`() {
        assertTrue(
            shouldHideInputHelp(
                hasFocus = true, progressLoading = true, hasResult = false, intentWindowActive = false
            )
        )
    }

    @Test
    fun `intent直达的聚焦时间窗内保持结果可见`() {
        assertTrue(
            shouldHideInputHelp(
                hasFocus = true, progressLoading = false, hasResult = true, intentWindowActive = true
            )
        )
    }

    @Test
    fun `用户主动点输入框且已过时间窗时展示帮助层`() {
        assertFalse(
            shouldHideInputHelp(
                hasFocus = true, progressLoading = false, hasResult = true, intentWindowActive = false
            )
        )
    }

    @Test
    fun `已有结果且失焦时隐藏帮助层`() {
        assertTrue(
            shouldHideInputHelp(
                hasFocus = false, progressLoading = false, hasResult = true, intentWindowActive = false
            )
        )
        assertFalse(
            shouldHideInputHelp(
                hasFocus = false, progressLoading = false, hasResult = false, intentWindowActive = false
            )
        )
    }

    /** 兜底时序：输入框已聚焦、之后才返回结果（不触发焦点回调）时也必须收起帮助层。 */
    @Test
    fun `结果到达即收起帮助层`() {
        assertTrue(shouldCollapseHelpOnResults(resultCount = 100, query = "关键词"))
        assertFalse(shouldCollapseHelpOnResults(resultCount = 0, query = "关键词"))
        assertFalse(shouldCollapseHelpOnResults(resultCount = 100, query = "   "))
    }
}
