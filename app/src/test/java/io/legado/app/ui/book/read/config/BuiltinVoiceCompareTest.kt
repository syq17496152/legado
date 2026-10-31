package io.legado.app.ui.book.read.config

import io.legado.app.help.tts.builtin.BuiltinVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T8.8 音色对比试听（纯函数）：取前两个可用音色 + 每段示范句 ≈4 秒（名称截断）。
 */
class BuiltinVoiceCompareTest {

    private fun voice(id: String, name: String) = BuiltinVoice(
        id = id,
        name = name,
        refAudio = "$id/prompt.wav",
        origin = BuiltinVoice.ORIGIN_PRESET
    )

    @Test
    fun `不足两个音色不对比`() {
        assertNull(BuiltinVoiceCompare.pairToCompare(emptyList()))
        assertNull(BuiltinVoiceCompare.pairToCompare(listOf(voice("a", "内置·男·少年"))))
        // id 为空的脏数据不计入
        assertNull(
            BuiltinVoiceCompare.pairToCompare(
                listOf(voice("a", "内置·男·少年"), voice("", "坏数据"))
            )
        )
    }

    @Test
    fun `取前两个可用音色`() {
        val pair = BuiltinVoiceCompare.pairToCompare(
            listOf(
                voice("", "坏数据"),
                voice("a", "内置·男·少年"),
                voice("b", "内置·女·青年"),
                voice("c", "导入·旁白")
            )
        )
        assertEquals("a", pair?.first?.id)
        assertEquals("b", pair?.second?.id)
    }

    @Test
    fun `示范句按最后一段名称截断 保证单段约四秒`() {
        // 「·」后只取末段（内置·男·少年 → 少年），不再把「内置·」念进示范句
        assertEquals("这是少年的声音，听听喜欢哪一个。", BuiltinVoiceCompare.sampleTextFor("内置·男·少年"))
        // 无「·」的长名截断到 6 字（用 ASCII 名字避免中文计数歧义）
        assertEquals(
            "这是ABCDEF的声音，听听喜欢哪一个。",
            BuiltinVoiceCompare.sampleTextFor("ABCDEFGHIJ")
        )
        // 空白被剔除
        assertEquals("这是张三的声音，听听喜欢哪一个。", BuiltinVoiceCompare.sampleTextFor(" 张三 "))
    }
}
