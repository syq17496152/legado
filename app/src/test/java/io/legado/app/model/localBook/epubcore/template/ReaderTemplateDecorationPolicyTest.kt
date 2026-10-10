package io.legado.app.model.localBook.epubcore.template

import io.legado.app.testkit.SourceFileProbe
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

    // ==================== 4.8d：档位字面量与沙箱三处契约 ====================

    @Test
    fun `档位字面量与档位一一对应`() {
        assertEquals(
            listOf("none", "light", "medium", "strong"),
            ReaderTemplateDecorationPolicy.Intensity.entries.map { it.cssValue }
        )
        assertEquals("中档字面量必须与后端默认值同源", "medium", ReaderTemplateDecorationPolicy.DefaultIntensity.cssValue)
    }

    @Test
    fun `焦点模式与无档位都下发 none 且停动效`() {
        val focus = decide(stored = ReaderTemplateDecorationPolicy.Intensity.STRONG, focus = true)
        assertEquals("none", focus.intensity.cssValue)
        assertFalse(focus.motionEnabled)
        val none = decide(stored = ReaderTemplateDecorationPolicy.Intensity.NONE)
        assertEquals("none", none.intensity.cssValue)
        assertFalse(none.motionEnabled)
    }

    @Test
    fun `沙箱运行时与装饰基线 css 覆盖全部档位字面量`() {
        // 三处必须同表：Kotlin 枚举（本文件断言）/ 沙箱 runtime 的 DECOR_SCALE / 引擎装饰基线 CSS。
        // 任一处漏一个档位 ⇒ 该档位在真机上"选了没反应"（且不报错），只有源码级断言能拦住
        val runtimeJs = SourceFileProbe.assetRawText("md/template-runtime.js")
        val decorationCss = SourceFileProbe.assetRawText("md/template-decoration.css")
        assertTrue("runtime 必须下发装饰强度属性", runtimeJs.contains("data-rp-decoration"))
        assertTrue("runtime 必须下发动效闸门属性", runtimeJs.contains("data-reader-motion"))
        val scaleBlock = runtimeJs.substringAfter("var DECOR_SCALE =", "").substringBefore(";")
        assertTrue("runtime 必须定义档位表", scaleBlock.isNotBlank())
        ReaderTemplateDecorationPolicy.Intensity.entries.forEach { intensity ->
            assertTrue(
                "runtime 档位表缺 ${intensity.cssValue}",
                scaleBlock.contains("${intensity.cssValue}:")
            )
            assertTrue(
                "装饰基线 CSS 缺 html[data-rp-decoration=\"${intensity.cssValue}\"] 规则",
                decorationCss.contains("data-rp-decoration=\"${intensity.cssValue}\"")
            )
        }
        assertTrue(
            "动效闸门必须按 data-reader-motion=paused 停用",
            decorationCss.contains("data-reader-motion=\"paused\"")
        )
        assertFalse(
            "装饰基线 CSS 不得出现脚本/样式闭合与转义敏感序列（沙箱 <style> 直接内联）",
            decorationCss.contains("</") || decorationCss.contains("<!--")
        )
    }
}