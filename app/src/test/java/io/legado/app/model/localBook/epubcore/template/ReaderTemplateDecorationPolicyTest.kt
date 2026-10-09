package io.legado.app.model.localBook.epubcore.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 装饰策略单测（epub-md-rich-rendering 阶段 4.18 配对）。
 *
 * 覆盖【验证标准】：①默认强度"中"；②强度四档语义；③专注模式隐藏装饰且接管排版；
 * ④reduce-motion 停动效但保观感；⑤关闭模板 ⇒ 完全退出且**不接管**原生排版（等同纯正文）；
 * ⑥**正文安全区硬约束**（≥80% 页高）；⑦**装饰分层**（装饰变更不得改变正文布局）。
 */
class ReaderTemplateDecorationPolicyTest {

    private fun decide(
        enabled: Boolean = true,
        stored: ReaderTemplateDecorationPolicy.Intensity? = null,
        focus: Boolean = false,
        reduceMotion: Boolean = false
    ) = ReaderTemplateDecorationPolicy.decide(enabled, stored, focus, reduceMotion)

    @Test
    fun `默认强度为中且启用装饰与动效`() {
        val decision = decide()
        assertEquals(ReaderTemplateDecorationPolicy.Intensity.MEDIUM, decision.intensity)
        assertTrue(decision.showDecorations)
        assertTrue(decision.motionEnabled)
        assertTrue("模板接管排版", decision.nativeTypographyDisabled)
        assertEquals("默认无需提示", "", decision.notice)
    }

    @Test
    fun `强度四档语义正确`() {
        val levels = ReaderTemplateDecorationPolicy.Intensity.entries
        assertEquals(4, levels.size)
        assertEquals(0f, ReaderTemplateDecorationPolicy.Intensity.NONE.decorationScale, 0.0001f)
        assertFalse(ReaderTemplateDecorationPolicy.Intensity.NONE.motionAllowed)
        assertFalse("轻档不动效", ReaderTemplateDecorationPolicy.Intensity.LIGHT.motionAllowed)
        assertEquals(
            "非法值回落默认",
            ReaderTemplateDecorationPolicy.DefaultIntensity,
            ReaderTemplateDecorationPolicy.Intensity.fromLevel(99)
        )
        assertEquals(
            ReaderTemplateDecorationPolicy.Intensity.STRONG,
            ReaderTemplateDecorationPolicy.Intensity.fromLevel(3)
        )
    }

    @Test
    fun `无档位时不显示装饰`() {
        val decision = decide(stored = ReaderTemplateDecorationPolicy.Intensity.NONE)
        assertFalse(decision.showDecorations)
        assertFalse(decision.motionEnabled)
    }

    @Test
    fun `关闭模板系统时完全退出且不接管原生排版`() {
        val decision = decide(
            enabled = false,
            stored = ReaderTemplateDecorationPolicy.Intensity.STRONG,
            focus = true,
            reduceMotion = true
        )
        assertEquals(ReaderTemplateDecorationPolicy.Intensity.NONE, decision.intensity)
        assertFalse(decision.showDecorations)
        assertFalse(decision.motionEnabled)
        assertFalse("关闭后原生排版必须恢复可用", decision.nativeTypographyDisabled)
        assertEquals("关闭语义无残留，不提示", "", decision.notice)
    }

    @Test
    fun `专注模式隐藏装饰并给出提示`() {
        val decision = decide(stored = ReaderTemplateDecorationPolicy.Intensity.STRONG, focus = true)
        assertEquals(ReaderTemplateDecorationPolicy.Intensity.NONE, decision.intensity)
        assertFalse(decision.showDecorations)
        assertFalse(decision.motionEnabled)
        assertTrue(decision.nativeTypographyDisabled)
        assertTrue("须告知用户装饰已被隐藏", decision.notice.contains("专注模式"))
    }

    @Test
    fun `减少动效时停动效但保留装饰观感`() {
        val decision = decide(stored = ReaderTemplateDecorationPolicy.Intensity.STRONG, reduceMotion = true)
        assertEquals("强度档位保留", ReaderTemplateDecorationPolicy.Intensity.STRONG, decision.intensity)
        assertTrue("装饰仍显示", decision.showDecorations)
        assertFalse("动效必须停止", decision.motionEnabled)
        assertTrue(decision.notice.contains("动效"))
    }

    @Test
    fun `本就无动效的档位不因减少动效产生提示`() {
        val decision = decide(stored = ReaderTemplateDecorationPolicy.Intensity.LIGHT, reduceMotion = true)
        assertFalse(decision.motionEnabled)
        assertEquals("轻档本来不动效 ⇒ 无需提示打扰", "", decision.notice)
    }

    @Test
    fun `正文安全区硬约束为页高的八成`() {
        assertEquals(0.80f, ReaderTemplateDecorationPolicy.MinContentHeightRatio, 0.0001f)
        assertTrue(ReaderTemplateDecorationPolicy.contentAreaSatisfiesBlueprint(800, 1000))
        assertTrue("恰好达标", ReaderTemplateDecorationPolicy.contentAreaSatisfiesBlueprint(800, 1000))
        assertFalse("不足八成即越界", ReaderTemplateDecorationPolicy.contentAreaSatisfiesBlueprint(799, 1000))
        assertFalse("非法入参按越界处理", ReaderTemplateDecorationPolicy.contentAreaSatisfiesBlueprint(0, 1000))
        assertFalse(ReaderTemplateDecorationPolicy.contentAreaSatisfiesBlueprint(800, 0))
    }

    @Test
    fun `装饰变更不得改变正文布局`() {
        val before = ReaderTemplateDecorationPolicy.LayoutSnapshot(600, 800, 12)
        assertFalse(
            "装饰分层：同尺寸同页数 ⇒ 未影响正文测量",
            ReaderTemplateDecorationPolicy.decorationChangesLayout(
                before,
                before.copy()
            )
        )
        assertTrue(
            "正文尺寸或页数变化即视为装饰侵入正文",
            ReaderTemplateDecorationPolicy.decorationChangesLayout(before, before.copy(pageCount = 13))
        )
        assertTrue(
            ReaderTemplateDecorationPolicy.decorationChangesLayout(before, before.copy(contentHeightPx = 780))
        )
    }
}