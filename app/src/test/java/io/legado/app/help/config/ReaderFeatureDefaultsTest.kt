package io.legado.app.help.config

import io.legado.app.constant.PreferKey
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
}