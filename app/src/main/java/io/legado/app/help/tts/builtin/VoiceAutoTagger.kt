package io.legado.app.help.tts.builtin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 音色自动打标（AD-12 / T6.2）：F0 中位数判性别 + 名称语义补年龄，结果写回索引（可人工改）。
 * 口径：男 85~165Hz / 女 165~300Hz；无把握保持"未定"（不猜）。
 */
object VoiceAutoTagger {

    data class Result(
        val processed: Int = 0,
        val male: Int = 0,
        val female: Int = 0,
        val unknown: Int = 0
    )

    suspend fun tagAll(
        store: BuiltinVoiceStore,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        shouldAbort: () -> Boolean = { false }
    ): Result = withContext(Dispatchers.IO) {
        val voices = store.list().filter { it.gender.isBlank() }
        if (voices.isEmpty()) return@withContext Result()
        var male = 0
        var female = 0
        var unknown = 0
        var processed = 0
        voices.forEachIndexed { index, voice ->
            // T6.3：可中断——已完成的音色已落库，下次从剩余继续（天然断点续）
            if (shouldAbort()) return@withContext Result(processed, male, female, unknown)
            val file = store.audioFile(voice)
            val f0 = runCatching {
                val audio = PcmAudioUtils.readWav(file)
                val samples = PcmAudioUtils.pcm16ToFloat(audio.pcm)
                PcmAudioUtils.estimateF0(samples, audio.sampleRate)
            }.getOrDefault(0f)
            val gender = when {
                f0 <= 0f -> ""
                f0 < 165f -> "male"
                f0 <= 320f -> "female"
                else -> ""
            }
            val age = inferAge(voice.name)
            val tags = (voice.tags + listOfNotNull(
                gender.takeIf { it.isNotBlank() },
                age.takeIf { it.isNotBlank() }
            )).distinct()
            store.upsert(voice.copy(gender = gender, age = age, tags = tags))
            when (gender) {
                "male" -> male++
                "female" -> female++
                else -> unknown++
            }
            processed = index + 1
            onProgress(index + 1, voices.size)
        }
        Result(processed, male, female, unknown)
    }

    fun inferAge(name: String): String = when {
        name.contains("少年") || name.contains("少女") || name.contains("童") -> "teen"
        name.contains("老年") || name.contains("老") || name.contains("奶奶") || name.contains("爷爷") -> "aged"
        name.contains("青年") || name.contains("年轻") -> "youth"
        else -> ""
    }
}
