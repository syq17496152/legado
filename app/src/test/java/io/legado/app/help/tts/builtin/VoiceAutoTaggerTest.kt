package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs
import kotlin.math.sin

/**
 * 音色自动打标（T6.2）：按 F0 判性别、已打标跳过（可断点续）、名称补年龄。
 */
class VoiceAutoTaggerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun sineWav(freqHz: Float, durationMs: Int = 4000, sampleRate: Int = 16000): ByteArray {
        val count = sampleRate * durationMs / 1000
        return PcmAudioUtils.wavBytes(
            PcmAudioUtils.floatToPcm16(FloatArray(count) { i ->
                0.5f * sin(2.0 * Math.PI * freqHz * i / sampleRate).toFloat()
            }),
            sampleRate
        )
    }

    private fun f0Of(bytes: ByteArray): Float {
        val audio = PcmAudioUtils.readWavDownmix(bytes.inputStream())
        return PcmAudioUtils.estimateF0(PcmAudioUtils.pcm16ToFloat(audio.pcm), audio.sampleRate)
    }

    @Test
    fun `F0 区分男女且已有性别不重复打标`() {
        val store = BuiltinVoiceStore(temp.newFolder("tag-store"))
        store.addVoice(
            name = "低音样本",
            pcmWavBytes = sineWav(120f),
            refText = "稿",
            tags = emptyList(),
            origin = BuiltinVoice.ORIGIN_ZIP,
            profileId = "clone",
            id = "low"
        )
        store.addVoice(
            name = "高音样本",
            pcmWavBytes = sineWav(230f),
            refText = "稿",
            tags = emptyList(),
            origin = BuiltinVoice.ORIGIN_ZIP,
            profileId = "clone",
            id = "high"
        )
        val lowF0 = f0Of(sineWav(120f))
        val highF0 = f0Of(sineWav(230f))
        assertEquals(120f, lowF0, 20f)
        assertEquals(230f, highF0, 25f)

        val result = kotlinx.coroutines.runBlocking { VoiceAutoTagger.tagAll(store) }
        assertEquals(2, result.processed)
        assertEquals("male", store.findById("low")?.gender)
        assertEquals("female", store.findById("high")?.gender)

        // 断点续：已打标的不再处理
        val second = kotlinx.coroutines.runBlocking { VoiceAutoTagger.tagAll(store) }
        assertEquals(0, second.processed)
    }

    @Test
    fun `名称语义推断年龄`() {
        assertEquals("teen", VoiceAutoTagger.inferAge("内置·男·少年"))
        assertEquals("aged", VoiceAutoTagger.inferAge("爷爷音色"))
        assertEquals("youth", VoiceAutoTagger.inferAge("青年女声"))
        assertEquals("", VoiceAutoTagger.inferAge("旁白"))
    }

    @Test
    fun `取消打标保留已完成项并可续跑 T6-3`() {
        val store = BuiltinVoiceStore(temp.newFolder("abort-store"))
        store.addVoice(
            name = "第一个",
            pcmWavBytes = sineWav(120f),
            refText = "稿",
            tags = emptyList(),
            origin = BuiltinVoice.ORIGIN_ZIP,
            profileId = "clone",
            id = "v1"
        )
        store.addVoice(
            name = "第二个",
            pcmWavBytes = sineWav(230f),
            refText = "稿",
            tags = emptyList(),
            origin = BuiltinVoice.ORIGIN_ZIP,
            profileId = "clone",
            id = "v2"
        )
        // 第 1 次检查放行（处理第 1 个），第 2 次检查要求中断
        var checks = 0
        val aborted = kotlinx.coroutines.runBlocking {
            VoiceAutoTagger.tagAll(store, shouldAbort = { checks++ > 0 })
        }
        assertEquals(1, aborted.processed)
        assertEquals("male", store.findById("v1")?.gender)
        assertEquals("", store.findById("v2")?.gender.orEmpty())
        // 续跑：只处理剩余（断点续）
        val rest = kotlinx.coroutines.runBlocking { VoiceAutoTagger.tagAll(store) }
        assertEquals(1, rest.processed)
        assertEquals("female", store.findById("v2")?.gender)
    }
}
