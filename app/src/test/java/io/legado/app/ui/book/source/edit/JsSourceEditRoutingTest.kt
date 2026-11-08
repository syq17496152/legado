package io.legado.app.ui.book.source.edit

import io.legado.app.data.entities.BookSource
import io.legado.app.model.jsSource.isJsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书源编辑页路由判定（ng-js-source-port + 2026-11-08 空白页回归）。
 *
 * 规则页对 JS 源无意义（规则字段被剥离），必须改道脚本编辑器，
 * 否则用户会在空规则页误保存、且看不到真正的脚本。
 *
 * 反向约束（回归）：新建规则书源不带 `sourceUrl`，此时必须落到规则编辑页 ——
 * 曾经的实现把「无需重定向」当成「不做任何动作」，导致新建页空白。
 */
class JsSourceEditRoutingTest {

    @Test
    fun jsSource_mustNotOpenRuleEditor() {
        assertTrue(BookSource(bookSourceUrl = "u", mainJs = "var config={};").isJsSource())
    }

    @Test
    fun declarativeSource_keepsRuleEditor() {
        assertFalse(BookSource(bookSourceUrl = "u", searchUrl = "https://x/{{key}}").isJsSource())
    }

    // ---- 2026-11-08：新建规则书源空白页回归锁 ----

    @Test
    fun noSourceUrl_opensRuleEditor() {
        assertEquals(
            JsSourceEditRoute.Target.RuleEditor,
            JsSourceEditRoute.decide(null, isJsSource = false)
        )
    }

    @Test
    fun blankSourceUrl_opensRuleEditor_evenIfDbSaysJs() {
        assertEquals(
            JsSourceEditRoute.Target.RuleEditor,
            JsSourceEditRoute.decide("", isJsSource = true)
        )
        assertEquals(
            JsSourceEditRoute.Target.RuleEditor,
            JsSourceEditRoute.decide("   ", isJsSource = true)
        )
    }

    @Test
    fun jsSourceWithUrl_redirectsToJsEditor() {
        assertEquals(
            JsSourceEditRoute.Target.RedirectJs,
            JsSourceEditRoute.decide("https://x", isJsSource = true)
        )
    }

    @Test
    fun declarativeSourceWithUrl_opensRuleEditor() {
        // 声明式源与「查库异常回落 false」同一分支，均必须建规则页
        assertEquals(
            JsSourceEditRoute.Target.RuleEditor,
            JsSourceEditRoute.decide("https://x", isJsSource = false)
        )
    }
}
