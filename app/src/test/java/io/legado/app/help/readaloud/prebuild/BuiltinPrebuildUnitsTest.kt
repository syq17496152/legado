package io.legado.app.help.readaloud.prebuild

import io.legado.app.help.readaloud.casting.CastingSegment
import io.legado.app.help.readaloud.casting.CastingTag
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T3.2/T3.3 预合成单元构造的纯函数口径：段落切分 / 对白剥引号 / 音色回落。
 * 目的：预合成缓存键必须与播放侧一致（段级音色），否则"二刷全命中"永远不成立。
 */
class BuiltinPrebuildUnitsTest {

    @Test
    fun `段落切分去空白丢空段`() {
        assertEquals(
            listOf("第一段。", "第二段。"),
            BuiltinPrebuildUnits.paragraphsOf("第一段。\n\n  第二段。  \n \n")
        )
        assertEquals(emptyList<String>(), BuiltinPrebuildUnits.paragraphsOf("  \n\n "))
    }

    @Test
    fun `旁白段保留原文 对白段剥离引号`() {
        val paragraph = "张三说：“今天出发。”"
        val narration = CastingSegment(0, 0, paragraph.length, CastingTag.NARRATION)
        val dialogue = CastingSegment(0, 0, paragraph.length, CastingTag.DIALOGUE)
        assertEquals(paragraph, BuiltinPrebuildUnits.segmentSpeech(paragraph, narration))
        // 引号不进合成文本（与播放侧 stripQuotesForSpeech 同口径）
        assertEquals("张三说：今天出发。", BuiltinPrebuildUnits.segmentSpeech(paragraph, dialogue))
        // 越界区间夹紧，不抛异常
        val outOfRange = CastingSegment(0, paragraph.length + 10, 20, CastingTag.NARRATION)
        assertEquals("", BuiltinPrebuildUnits.segmentSpeech(paragraph, outOfRange))
    }

    @Test
    fun `段级音色优先解析值 空则回落旁白`() {
        assertEquals("b6_m_youth|内置·男·青年", BuiltinPrebuildUnits.voiceFor("b6_m_youth|内置·男·青年", "旁白"))
        assertEquals("旁白", BuiltinPrebuildUnits.voiceFor("", "旁白"))
        assertEquals("", BuiltinPrebuildUnits.voiceFor("", ""))
    }

    @Test
    fun `预合成发起门 内置引擎不要求网络与 HTTP 通道`() {
        // 内置引擎激活 + 无 http + 无网络 ⇒ 允许（离线预合成）
        assertEquals(
            TtsPrebuildManager.PrebuildGate.OK,
            TtsPrebuildManager.prebuildGate(
                builtinReady = true, hasHttpTts = false, networkAvailable = false
            )
        )
        // http 通道必须有网络
        assertEquals(
            TtsPrebuildManager.PrebuildGate.NO_NETWORK,
            TtsPrebuildManager.prebuildGate(
                builtinReady = false, hasHttpTts = true, networkAvailable = false
            )
        )
        assertEquals(
            TtsPrebuildManager.PrebuildGate.OK,
            TtsPrebuildManager.prebuildGate(
                builtinReady = false, hasHttpTts = true, networkAvailable = true
            )
        )
        // 既非内置也无 http ⇒ 需先选可用引擎
        assertEquals(
            TtsPrebuildManager.PrebuildGate.NEED_ENGINE,
            TtsPrebuildManager.prebuildGate(
                builtinReady = false, hasHttpTts = false, networkAvailable = true
            )
        )
    }
}
