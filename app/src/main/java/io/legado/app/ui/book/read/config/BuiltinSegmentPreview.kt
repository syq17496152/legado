package io.legado.app.ui.book.read.config

import android.content.Context
import android.speech.tts.TextToSpeech

/**
 * 分镜段级试听（T5.5 / T5.2 步3）：用**该段角色音色**念**该段真实文本**。
 *
 * 为什么单独一个实例：管理页的「音色试听」用的是固定示范句（"这是XX的声音"），
 * 而分镜试听要证明"这段话换个音色听起来对不对"，必须念真实片段并能被角色音色替换。
 * 独立 TextToSpeech 实例与页面里的音色试听互不打断（同一引擎可并发多实例）。
 * 文本按 [previewPhrase] 截断：一次试听只念开头一小段，避免长段等十几秒。
 */
object BuiltinSegmentPreview {

    /** 单次试听最多念多少字（超过截断，保证 2~4 秒内能听到音色差异） */
    internal const val PHRASE_LIMIT = 80

    private var tts: TextToSpeech? = null

    /**
     * 纯函数（JVM 可测）：试听文本 —— 去空白后截断；截断时补省略号，让人知道只念了开头。
     * 空文本返回空串（调用方直接不发声）。
     */
    internal fun previewPhrase(text: String, limit: Int = PHRASE_LIMIT): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.length <= limit) return trimmed
        return trimmed.take(limit) + "…"
    }

    fun speak(context: Context, text: String, voiceName: String) {
        val phrase = previewPhrase(text)
        if (phrase.isEmpty()) return
        val app = context.applicationContext
        val existing = tts
        if (existing != null) {
            speakWith(existing, phrase, voiceName)
            return
        }
        tts = TextToSpeech(app, { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { speakWith(it, phrase, voiceName) }
            }
        }, app.packageName)
    }

    private fun speakWith(engine: TextToSpeech, phrase: String, voiceName: String) {
        runCatching {
            // 音色不存在时保持引擎默认音色（宁可听到默认声音，也不要静默失败）
            engine.voices?.firstOrNull { it.name == voiceName }?.let { engine.voice = it }
            engine.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "builtin_seg_preview")
        }
    }

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
    }
}
