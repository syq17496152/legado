package io.legado.app.help.config

import io.legado.app.constant.PreferKey
import io.legado.app.model.localBook.epubcore.template.ReaderTemplateSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读器功能默认值契约测试（epub-md-rich-rendering 阶段 3.2 配对）。
 *
 * 钉住"新功能默认开关"这一产品决策，并保证偏好键与 AppConfig 的单源一致。
 */
class ReaderFeatureDefaultsTest {

    @Test
    fun `文本富渲染默认开启`() {
        // 默认开 = 开箱即用看到 mermaid/公式；关掉是可用降级（回落 canvas HTML 渲染）
        assertTrue(ReaderFeatureDefaults.MD_RICH_RENDER)
    }

    @Test
    fun `偏好键与 PreferKey 单源一致且非空`() {
        assertEquals("mdRichRender", PreferKey.mdRichRender)
        assertFalse("键名不得为空，否则偏好读写会落到匿名键", PreferKey.mdRichRender.isBlank())
    }

    @Test
    fun `阅读页面模板默认值为跟随主题哨兵`() {
        // 默认跟随主题（而非固定某套）：默认模板是暗色款，浅色主题下会与主题冲撞（SP-06）
        assertEquals(ReaderTemplateSelection.FollowTheme, ReaderFeatureDefaults.READER_TEMPLATE_ID)
        assertTrue("空串即「跟随主题」，非空则等于要求用户先选一套", ReaderFeatureDefaults.READER_TEMPLATE_ID.isBlank())
        assertEquals("readerTemplate", PreferKey.readerTemplate)
    }

    @Test
    fun `页面模板整体开关默认关闭且键名单源`() {
        // 4.9：开启 = 文本类内容的默认渲染路径切换（canvas → 模板面）⇒ 属大改，
        // 必须先有 4.19 防卡顿 / XB.2 性能实测与 4.20 兼容验证，再谈默认翻转。
        assertFalse("默认关：不给用户造成「看书方式被悄悄换掉」的意外", ReaderFeatureDefaults.READER_TEMPLATE_ENABLED)
        assertEquals("readerTemplateEnabled", PreferKey.readerTemplateEnabled)
    }
}