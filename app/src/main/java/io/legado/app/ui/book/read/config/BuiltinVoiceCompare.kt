package io.legado.app.ui.book.read.config

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.help.tts.builtin.BuiltinVoice

/**
 * 音色 A/B 对比试听（T8.8）：取前两个可用音色，串行播放各一段**约 4 秒**的示范句。
 *
 * 与「连续试听」的区别：连续试听是列表式扫听（最多 5 个、串行），对比试听只放 2 个、
 * 用同一个示范句（只换音色），便于直接听差异；播完自动 `shutdown`，不在页面留下长驻实例。
 */
object BuiltinVoiceCompare {

    /** 示范句里音色名最多保留多少字（防止长名把一句话拉长，保证单段 ≈4s） */
    internal const val NAME_LIMIT = 6

    private var tts: TextToSpeech? = null

    /** 纯函数（JVM 可测）：挑选对比的两个音色（跳过 id 空的脏数据；不足 2 个返回 null） */
    internal fun pairToCompare(voices: List<BuiltinVoice>): Pair<BuiltinVoice, BuiltinVoice>? {
        val usable = voices.filter { it.id.isNotBlank() }
        if (usable.size < 2) return null
        return usable[0] to usable[1]
    }

    /**
     * 纯函数（JVM 可测）：单段示范句 —— 每段约 4 秒。
     * 音色名只取「·」之后的部分（`内置·男·少年` → `少年`），再截断到 6 字，避免长名把一句话拖长。
     */
    internal fun sampleTextFor(name: String): String {
        val tail = name.substringAfterLast('·', name).filterNot { it.isWhitespace() }.take(NAME_LIMIT)
        return "这是${tail}的声音，听听喜欢哪一个。"
    }

    /**
     * 开始对比；返回 false 表示可用音色不足 2 个（调用方应提示用户先导入更多音色）。
     */
    fun compare(context: Context, voices: List<BuiltinVoice>): Boolean {
        val pair = pairToCompare(voices) ?: return false
        val app = context.applicationContext
        val texts = listOf(sampleTextFor(pair.first.displayName), sampleTextFor(pair.second.displayName))
        val existing = tts
        if (existing != null) {
            speakBoth(existing, texts)
            return true
        }
        tts = TextToSpeech(app, { status ->
            if (status == TextToSpeech.SUCCESS) speakBoth(tts, texts)
        }, app.packageName)
        return true
    }

    private fun speakBoth(engine: TextToSpeech?, texts: List<String>) {
        val e = engine ?: return
        runCatching {
            e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onError(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    // 最后一段播完即释放（对比试听不留长驻 TTS 实例）
                    if (utteranceId == UTTERANCE_LAST) shutdown()
                }
            })
            texts.forEachIndexed { index, text ->
                val id = if (index == texts.lastIndex) UTTERANCE_LAST else "compare_$index"
                e.speak(text, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
            }
        }
    }

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
    }

    private const val UTTERANCE_LAST = "compare_last"
}
