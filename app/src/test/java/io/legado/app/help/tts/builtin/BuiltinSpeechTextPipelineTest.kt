package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 送引擎文本链路端到端（JVM）：简繁 → 用户词典 → 数字/年份/单位 → 可朗读字符清洗。
 *
 * 2026-11-10 真机缺陷「每句结尾多读一个耶」的回归层：
 * 单测清洗器只能证明“会删符号”，这里锁死**整条链路的顺序**——数字归一必须先跑，
 * 否则 `50%` 的百分号会被清洗提前删掉（读成“五十”而非“百分之五十”）。
 */
class BuiltinSpeechTextPipelineTest {

    private fun compose(text: String, pronounce: (String) -> String = { it }): String =
        BuiltinTtsRuntime.composeSpeechText(text, pronounce)

    @Test
    fun `百分号先归一后清洗`() {
        assertEquals("价格是百分之五十", compose("价格是50%～"))
        assertEquals("完成度百分之三十", compose("完成度30%☆"))
    }

    @Test
    fun `年份与摄氏度归一不被清洗破坏`() {
        assertEquals("二零二六年", compose("2026年"))
        assertEquals("温度二十五摄氏度", compose("温度25℃～"))
    }

    @Test
    fun `装饰符号与全角括号被剔除`() {
        assertEquals("他说好", compose("他说（好）"))
        assertEquals("你来了。", compose("你来了。～"))
        assertEquals("真棒", compose("真棒☆※♪"))
    }

    @Test
    fun `正常文本与用户词典原样保留`() {
        assertEquals("“你来了？”他问。", compose("“你来了？”他问。"))
        assertEquals("Hello, world!", compose("Hello, world!"))
        assertEquals("行走走去", compose("银行走去", pronounce = { it.replace("银行", "行走") }))
    }

    @Test
    fun `空文本安全`() {
        assertEquals("", compose(""))
        assertEquals("", compose("～☆"))
    }
}
