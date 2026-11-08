package io.legado.app.ui.book.read.config

import io.legado.app.help.tts.builtin.BuiltinVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `对比计划两段各自带不同音色名`() {
        // T8.8 真机铁证：原实现只排两段文本、从不切音色 ⇒ 两段同声（对比无意义）
        val plan = BuiltinVoiceCompare.plan(
            listOf(voice("a", "内置·男·少年"), voice("b", "内置·女·青年"))
        )
        assertEquals(2, plan?.size)
        assertEquals("这是少年的声音，听听喜欢哪一个。", plan?.get(0)?.first)
        assertEquals("a|内置·男·少年", plan?.get(0)?.second)
        assertEquals("这是青年的声音，听听喜欢哪一个。", plan?.get(1)?.first)
        assertEquals("b|内置·女·青年", plan?.get(1)?.second)
    }

    /**
     * 4.9 分组连播试听：取前 5 个可用音色（跳过空 id 脏数据），每段用短示范句；
     * 超过 5 个不再延长（扫听超过 5 个就应去管理页筛选）。
     */
    @Test
    fun `分组连播取前五个并跳过脏数据`() {
        val voices = listOf(
            voice("a", "内置·男·少年"),
            BuiltinVoice(id = "", name = "脏数据"),
            voice("b", "内置·女·青年"),
            voice("c", "女声三"),
            voice("d", "女声四"),
            voice("e", "女声五"),
            voice("f", "女声六")
        )
        val plan = BuiltinVoiceCompare.groupPlan(voices)
        assertEquals(BuiltinVoiceCompare.GROUP_PREVIEW_LIMIT, plan?.size)
        assertEquals("这是少年的声音。", plan?.get(0)?.first)
        assertEquals("a|内置·男·少年", plan?.get(0)?.second)
        // 空 id 被跳过 ⇒ 第二段是 b，而不是脏数据
        assertEquals("b|内置·女·青年", plan?.get(1)?.second)
        assertEquals(2, BuiltinVoiceCompare.groupPlan(voices, limit = 2)?.size)

        assertNull("无可用音色返回 null", BuiltinVoiceCompare.groupPlan(emptyList()))
        assertNull(BuiltinVoiceCompare.groupPlan(listOf(BuiltinVoice(id = "", name = "脏"))))
    }

    /**
     * 用户实测「内置 6 个音色试听后都是同一个音色」：根因是 setVoice 在实例初始化竞态下静默失败，
     * 现已改为 per-utterance Bundle 兜底。这里锁定：连续试听的每一段都必须携带**各自的**音色键，
     * 任何"计划里都是同一个键/空键"的回归都会让 6 个音色再次听起来一样。
     */
    @Test
    fun `分组连播每段携带各自的音色键`() {
        val voices = listOf(
            voice("b6_m_teen", "内置·男·少年"),
            voice("b6_m_youth", "内置·男·青年"),
            voice("b6_f_aged", "内置·女·老年")
        )
        val plan = BuiltinVoiceCompare.groupPlan(voices)
        assertEquals(
            listOf("b6_m_teen|内置·男·少年", "b6_m_youth|内置·男·青年", "b6_f_aged|内置·女·老年"),
            plan?.map { it.second }
        )
        assertEquals("音色键不得重复", 3, plan?.map { it.second }?.distinct()?.size)
        assertTrue("音色键不得为空", plan.orEmpty().all { it.second.isNotBlank() })
    }
}
