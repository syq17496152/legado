package io.legado.app.ui.book.read.config

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.help.tts.builtin.BuiltinVoice

/**
 * 音色 A/B 对比试听（T8.8）：取前两个可用音色，串行播放各一段**约 4 秒**的示范句。
 *
 * 与「连续试听」的区别：连续试听是列表式扫听（最多 5 个、串行），对比试听只放 2 个、
 * 用同一个示范句（只换音色），便于直接听差异；播完自动 `shutdown`，不在页面留下长驻实例。
 *
 * 2026-11-01 真机铁证：原实现一次性 `QUEUE_FLUSH + QUEUE_ADD` 两段且**从未 setVoice**，
 * 结果两段都用同一个音色（对比无意义）。改为「每段先切音色、上一段播完再播下一段」。
 */
object BuiltinVoiceCompare {

    /** 示范句里音色名最多保留多少字（防止长名把一句话拉长，保证单段 ≈4s） */
    internal const val NAME_LIMIT = 6

    private var tts: TextToSpeech? = null
    private var plan: List<Pair<String, String>> = emptyList()
    private var index = 0
    private val mainHandler = Handler(Looper.getMainLooper())

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
     * 纯函数（JVM 可测）：对比计划 = 两段「示范句 to 引擎音色名」。
     * 音色名必须随段传递，否则两段会落到同一个音色上（T8.8 真机铁证）。
     */
    internal fun plan(voices: List<BuiltinVoice>): List<Pair<String, String>>? {
        val pair = pairToCompare(voices) ?: return null
        return listOf(
            sampleTextFor(pair.first.displayName) to pair.first.voiceName,
            sampleTextFor(pair.second.displayName) to pair.second.voiceName
        )
    }

    /**
     * 纯函数（JVM 可测）：单段试听句（5.3 换音色候选用）——一句到底，不出现"选哪个"的问句。
     */
    internal fun previewTextFor(name: String): String {
        val tail = name.substringAfterLast('·', name).filterNot { it.isWhitespace() }.take(NAME_LIMIT)
        return "这是${tail}的声音。"
    }

    /**
     * 单音色试听（5.3 换音色选用）：与 [compare] 共用同一套「先切音色再播、播完自动 shutdown」，
     * 只是播放段数为 1；返回 false 表示该音色没有可用的引擎绑定键。
     */
    fun preview(context: Context, voice: BuiltinVoice): Boolean {
        if (voice.voiceName.isBlank()) return false
        start(listOf(previewTextFor(voice.displayName) to voice.voiceName), context)
        return true
    }

    /**
     * 纯函数（JVM 可测）：分组连播试听计划 —— 取前 [limit] 个可用音色，
     * 每段一句短示范（与单条试听同一文案），便于在资源页快速扫听。
     */
    internal fun groupPlan(voices: List<BuiltinVoice>, limit: Int = GROUP_PREVIEW_LIMIT): List<Pair<String, String>>? {
        val usable = voices.filter { it.id.isNotBlank() && it.voiceName.isNotBlank() }
        if (usable.isEmpty()) return null
        return usable.take(limit.coerceIn(1, GROUP_PREVIEW_LIMIT))
            .map { previewTextFor(it.displayName) to it.voiceName }
    }

    /**
     * 分组连播试听（4.9）：返回实际起播的段数；0 表示没有可用音色（调用方应提示先导入）。
     */
    fun previewGroup(context: Context, voices: List<BuiltinVoice>, limit: Int = GROUP_PREVIEW_LIMIT): Int {
        val plan = groupPlan(voices, limit) ?: return 0
        start(plan, context)
        return plan.size
    }

    /**
     * 开始对比；返回 false 表示可用音色不足 2 个（调用方应提示用户先导入更多音色）。
     */
    fun compare(context: Context, voices: List<BuiltinVoice>): Boolean {
        val plan = plan(voices) ?: return false
        start(plan, context)
        return true
    }

    /** 起播计划（段 = 示范句 to 引擎音色名）：已有引擎直接重播，首次调用时异步初始化 */
    private fun start(plan: List<Pair<String, String>>, context: Context) {
        this.plan = plan
        index = 0
        val app = context.applicationContext
        val existing = tts
        if (existing != null) {
            startAt(existing, 0)
            return
        }
        tts = TextToSpeech(app, { status ->
            if (status == TextToSpeech.SUCCESS) tts?.let { startAt(it, 0) }
        }, app.packageName)
    }

    /** 播第 [at] 段：先切音色，再 FLUSH 播报（每段独立，避免队列早合成用错音色） */
    private fun startAt(engine: TextToSpeech, at: Int) {
        val item = plan.getOrNull(at) ?: run { shutdown(); return }
        index = at
        runCatching {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onError(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    mainHandler.post { next() }
                }
            })
            applyVoice(engine, item.second)
            engine.speak(item.first, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }
    }

    private fun next() {
        val engine = tts ?: return
        val at = index + 1
        if (at >= plan.size) {
            shutdown()
            return
        }
        startAt(engine, at)
    }

    /** 引擎音色名是 `id|显示名`；按名字从已加载音色集合里取 Voice 再 setVoice */
    private fun applyVoice(engine: TextToSpeech, voiceName: String) {
        val voice = runCatching {
            engine.voices?.firstOrNull { it.name == voiceName }
        }.getOrNull() ?: return
        runCatching { engine.setVoice(voice) }
    }

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        plan = emptyList()
        index = 0
    }

    private const val UTTERANCE_ID = "compare_voice"

    /** 分组连播上限：超过 5 个扫听就变成了"听音色马拉松"，不如去管理页筛选 */
    internal const val GROUP_PREVIEW_LIMIT = 5
}
