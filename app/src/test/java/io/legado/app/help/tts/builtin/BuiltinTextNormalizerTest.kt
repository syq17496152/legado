package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 简繁归一（T8.2）：简体原样返回；繁体超阈值才转换；正文文本不参与。
 */
class BuiltinTextNormalizerTest {

    @Test
    fun `简体文本比例为零且原样返回`() {
        val text = "他走进房间，看见桌上放着一本书。"
        assertEquals(0.0, BuiltinTextNormalizer.traditionalRatio(text), 0.0001)
        assertFalse(BuiltinTextNormalizer.isTraditional(text))
        assertEquals(text, BuiltinTextNormalizer.ensureSimplified(text))
    }

    @Test
    fun `繁体样本文本超阈值`() {
        val text = "他們說這本書很好看，我們一起來讀吧。"
        val ratio = BuiltinTextNormalizer.traditionalRatio(text)
        assertTrue("繁体占比应超阈值：$ratio", ratio > BuiltinTextNormalizer.TRADITIONAL_THRESHOLD)
        assertTrue(BuiltinTextNormalizer.isTraditional(text))
    }

    @Test
    fun `繁体文本被转换为简体`() {
        val text = "他們說這本書很好看，我們一起來讀吧。"
        val simplified = BuiltinTextNormalizer.ensureSimplified(text)
        assertFalse("不应再残留繁体专用字：$simplified", simplified.contains('們'))
        assertTrue("应转换为简体：$simplified", simplified.contains('们') || simplified.contains('这'))
    }

    @Test
    fun `少量繁体字不触发整段转换`() {
        // 只有 1 个繁体字 / 12 个汉字 ≈ 8%，低于 20%
        val text = "他走进房间，看见桌上放着一個书本。"
        assertEquals(text, BuiltinTextNormalizer.ensureSimplified(text))
    }

    @Test
    fun `空文本安全返回`() {
        assertEquals("", BuiltinTextNormalizer.ensureSimplified(""))
    }

    /**
     * A6：数字/年份/单位读音规则（设计验收：20 条样本正确率 ≥95%）。
     * 这里直接 20 条全量断言（100%），任何一条退化都会立刻失败。
     */
    @Test
    fun `数字年份单位读音样本二十条`() {
        val samples = listOf(
            "2026年" to "二零二六年",
            "1998年" to "一九九八年",
            "2008年代" to "二零零八年代",
            "2026" to "二千零二十六",
            "3.5" to "三点五",
            "3.5%" to "百分之三点五",
            "12%" to "百分之十二",
            "100" to "一百",
            "120" to "一百二十",
            "105" to "一百零五",
            "10000" to "一万",
            "10001" to "一万零一",
            "25℃" to "二十五摄氏度",
            "5km" to "五公里",
            "3.5kg" to "三点五千克",
            "2.5m²" to "二点五平方米",
            "第3章" to "第三章",
            "007" to "零零七",
            "13800138000" to "一三八零零一三八零零零",
            "16GB" to "十六吉字节"
        )
        samples.forEach { (input, expected) ->
            assertEquals("样本「$input」", expected, BuiltinNumberNormalizer.normalize(input))
        }
    }

    @Test
    fun `版本号与型号不被改写`() {
        // 3.26.110413 / RTX4090 / iOS16：数字属于标识，改了反而读错
        assertEquals("3.26.110413", BuiltinNumberNormalizer.normalize("3.26.110413"))
        assertEquals("RTX4090", BuiltinNumberNormalizer.normalize("RTX4090"))
        assertEquals("iOS16", BuiltinNumberNormalizer.normalize("iOS16"))
        // 负数交给引擎（避免丢失负号语义）
        assertEquals("-3", BuiltinNumberNormalizer.normalize("-3"))
    }

    @Test
    fun `数字归一幂等`() {
        val once = BuiltinNumberNormalizer.normalize("他在2026年花了3.5万元，气温25℃。")
        assertEquals(once, BuiltinNumberNormalizer.normalize(once))
        assertFalse("输出不应残留阿拉伯数字：$once", once.any { it in '0'..'9' })
    }
}
