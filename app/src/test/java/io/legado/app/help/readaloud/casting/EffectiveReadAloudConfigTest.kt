package io.legado.app.help.readaloud.casting

import io.legado.app.help.readaloud.casting.EffectiveReadAloudConfig.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四层继承解析器（4.8）：段 > 书 > 全局 > 内置默认，并回传来源层。
 * 这里只测纯函数 [EffectiveReadAloudConfig.resolve]（不读 SharedPreferences ⇒ JVM 可跑）。
 */
class EffectiveReadAloudConfigTest {

    @Test
    fun `全空回落到内置默认`() {
        val resolved = EffectiveReadAloudConfig.resolve()
        assertNull(resolved.templateId.value)
        assertEquals(Layer.BUILTIN, resolved.templateId.layer)
        assertEquals(EffectiveReadAloudConfig.DEFAULT_SPEECH_RATE, resolved.speechRate.value)
        assertEquals(Layer.BUILTIN, resolved.speechRate.layer)
        assertTrue(resolved.followSystemRate.value)
        assertEquals(Layer.BUILTIN, resolved.followSystemRate.layer)
    }

    @Test
    fun `只有全局时来源层为全局`() {
        val resolved = EffectiveReadAloudConfig.resolve(
            globalTemplateId = "builtin_immersive_multi",
            globalSpeechRate = 8,
            globalFollowSystem = false
        )
        assertEquals("builtin_immersive_multi", resolved.templateId.value)
        assertEquals(Layer.GLOBAL, resolved.templateId.layer)
        assertEquals(8, resolved.speechRate.value)
        assertEquals(Layer.GLOBAL, resolved.speechRate.layer)
        assertEquals(false, resolved.followSystemRate.value)
        assertEquals(Layer.GLOBAL, resolved.followSystemRate.layer)
    }

    @Test
    fun `书级覆盖压住全局`() {
        val resolved = EffectiveReadAloudConfig.resolve(
            bookTemplateId = "user_template_a",
            globalTemplateId = "builtin_mono"
        )
        assertEquals("user_template_a", resolved.templateId.value)
        assertEquals(Layer.BOOK, resolved.templateId.layer)
    }

    @Test
    fun `章段覆盖压住书级与全局`() {
        val resolved = EffectiveReadAloudConfig.resolve(
            segmentTemplateId = "chapter_template",
            bookTemplateId = "user_template_a",
            globalTemplateId = "builtin_mono",
            segmentSpeechRate = 12,
            bookSpeechRate = 4,
            globalSpeechRate = 6
        )
        assertEquals("chapter_template", resolved.templateId.value)
        assertEquals(Layer.SEGMENT, resolved.templateId.layer)
        assertEquals(12, resolved.speechRate.value)
        assertEquals(Layer.SEGMENT, resolved.speechRate.layer)
    }

    @Test
    fun `空白字符串视为未覆盖`() {
        val resolved = EffectiveReadAloudConfig.resolve(
            segmentTemplateId = "  ",
            bookTemplateId = "",
            globalTemplateId = "builtin_dual_voice"
        )
        // 段/书的空白都不算覆盖 ⇒ 落全局
        assertEquals("builtin_dual_voice", resolved.templateId.value)
        assertEquals(Layer.GLOBAL, resolved.templateId.layer)
    }

    @Test
    fun `语速 0 是合法覆盖值而非未设置`() {
        val resolved = EffectiveReadAloudConfig.resolve(bookSpeechRate = 0, globalSpeechRate = 9)
        assertEquals(0, resolved.speechRate.value)
        assertEquals(Layer.BOOK, resolved.speechRate.layer)
    }

    @Test
    fun `模板与语速各自独立取层`() {
        val resolved = EffectiveReadAloudConfig.resolve(
            bookTemplateId = "user_template_a",
            segmentSpeechRate = 3,
            globalSpeechRate = 7
        )
        assertEquals(Layer.BOOK, resolved.templateId.layer)
        assertEquals(Layer.SEGMENT, resolved.speechRate.layer)
        assertEquals(3, resolved.speechRate.value)
    }

    @Test
    fun `书级语速压住全局语速`() {
        val resolved = EffectiveReadAloudConfig.resolve(bookSpeechRate = 11, globalSpeechRate = 2)
        assertEquals(11, resolved.speechRate.value)
        assertEquals(Layer.BOOK, resolved.speechRate.layer)
    }

    @Test
    fun `内置语速可注入`() {
        val resolved = EffectiveReadAloudConfig.resolve(builtinSpeechRate = 20)
        assertEquals(20, resolved.speechRate.value)
        assertEquals(Layer.BUILTIN, resolved.speechRate.layer)
    }

    @Test
    fun `跟随系统语速只认全局与内置两层`() {
        val fallback = EffectiveReadAloudConfig.resolve(globalFollowSystem = null)
        assertEquals(true, fallback.followSystemRate.value)
        assertEquals(Layer.BUILTIN, fallback.followSystemRate.layer)
        // 内置默认也可注入（便于其它入口按同一口径渲染）
        val injected = EffectiveReadAloudConfig.resolve(
            globalFollowSystem = null,
            builtinFollowSystem = false
        )
        assertEquals(false, injected.followSystemRate.value)
        assertEquals(Layer.BUILTIN, injected.followSystemRate.layer)
    }

    @Test
    fun `来源层文案四层可区分`() {
        val hints = Layer.values().map { EffectiveReadAloudConfig.layerHint(it) }
        assertEquals(4, hints.size)
        assertTrue(hints.all { it.isNotBlank() })
        assertEquals(4, hints.distinct().size)
        // 书级提示要告诉用户怎么恢复（"跟随全局"是可执行动作，不是术语）
        assertTrue(EffectiveReadAloudConfig.layerHint(Layer.BOOK).contains("跟随全局"))
    }

    /**
     * 1.7 徽标判据：只要任意一项来自「本书」层就要亮徽标 —— 即使它的值与全局完全相同。
     * 若按键值相等判断，用户"明明改过本书"却看不到徽标，反而找不到覆盖的来源。
     */
    @Test
    fun `书级覆盖徽标只认来源层`() {
        val bookTemplate = EffectiveReadAloudConfig.resolve(
            bookTemplateId = "tpl-book",
            globalTemplateId = "tpl-global",
            bookSpeechRate = 5,
            globalSpeechRate = 5
        )
        assertTrue(EffectiveReadAloudConfig.hasBookOverride(bookTemplate))

        val bookRateOnly = EffectiveReadAloudConfig.resolve(
            globalTemplateId = "tpl-global",
            bookSpeechRate = 7,
            globalSpeechRate = 5
        )
        assertTrue(EffectiveReadAloudConfig.hasBookOverride(bookRateOnly))

        val globalOnly = EffectiveReadAloudConfig.resolve(
            globalTemplateId = "tpl-global",
            globalSpeechRate = 5,
            bookSpeechRate = null
        )
        assertFalse(EffectiveReadAloudConfig.hasBookOverride(globalOnly))

        val builtinOnly = EffectiveReadAloudConfig.resolve()
        assertFalse(EffectiveReadAloudConfig.hasBookOverride(builtinOnly))
    }
}
