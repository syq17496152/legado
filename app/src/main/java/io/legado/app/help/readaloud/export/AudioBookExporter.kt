package io.legado.app.help.readaloud.export

import android.content.Context
import android.net.Uri
import io.legado.app.help.book.characterBookKey
import io.legado.app.help.readaloud.casting.CharacterVoiceAssignService
import io.legado.app.help.readaloud.role.BookAliasStore
import io.legado.app.help.readaloud.role.OfflineRoleResolver
import io.legado.app.help.tts.builtin.BuiltinTtsRuntime
import io.legado.app.help.tts.builtin.PcmAudioUtils
import io.legado.app.model.ReadBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 章节音频导出（T8.5）：分镜 → 逐段合成（命中引擎缓存零耗时）→ 拼成单声道 WAV。
 *
 * 为什么是 WAV：端侧已有的是 PCM16 单调制品，WAV 只需 44 字节头（`PcmAudioUtils.wavBytes`），
 * 任何播放器都能放；转 m4a 需 MediaCodec AAC + MediaMuxer，另加一层编码状态机，
 * 本轮先交付"能听"的 WAV（偏差登记在 tasks.md）。预合成/朗读过的章节命中缓存，导出几乎是纯拷贝。
 */
object AudioBookExporter {

    data class Report(val ok: Boolean, val message: String)

    /** 上限保护：整章异常长时避免把 PCM 全堆在内存里 */
    private const val MAX_PCM_BYTES = 200L * 1024 * 1024

    suspend fun exportChapter(
        context: Context,
        uri: Uri,
        rate: Float = 1.0f,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Report = withContext(Dispatchers.IO) {
        if (!BuiltinTtsRuntime.isReady()) {
            return@withContext Report(false, "离线引擎未就绪：先下载/导入语音包")
        }
        val book = ReadBook.book ?: return@withContext Report(false, "请先打开一本书")
        val chapter = ReadBook.curTextChapter ?: return@withContext Report(false, "请先打开一章正文")
        val bookKey = book.characterBookKey()
        val paragraphs = chapter.paragraphs.map { it.text }
        if (paragraphs.isEmpty()) return@withContext Report(false, "本章没有正文")
        val rulesRoot = BuiltinTtsRuntime.paths().rulesRoot
        val aliasStore = BookAliasStore(rulesRoot)
        val characters = runCatching {
            CharacterVoiceAssignService.roleCharacters(bookKey)
        }.getOrDefault(emptyList())
        // 分镜与朗读同源：内存命中 → 落库缓存 → 现算（失败不阻断，返回空表）
        val segments = runCatching {
            OfflineRoleResolver.ensure(
                bookKey = bookKey,
                chapterIndex = chapter.chapter.index,
                chapterTitle = chapter.chapter.title,
                paragraphs = paragraphs,
                characters = characters,
                aliasStore = aliasStore
            )
        }.getOrDefault(emptyList())
        if (segments.isEmpty()) {
            return@withContext Report(false, "本章还没有分镜：先在「角色配音」识别角色")
        }
        val characterVoices = runCatching { CharacterVoiceAssignService.rows(bookKey) }
            .getOrDefault(emptyList())
            .filter { it.voiceName.isNotBlank() }
            .associate { it.characterId to it.voiceName }
        val voices = BuiltinTtsRuntime.voices()
        val narratorVoice = aliasStore.narratorVoice(bookKey)?.takeIf { it.isNotBlank() }
            ?: voices.firstOrNull { it.tags.contains("narration") }?.voiceName
            ?: voices.firstOrNull()?.voiceName.orEmpty()
        if (narratorVoice.isBlank()) return@withContext Report(false, "没有可用音色")
        val pieces = AudioExportPlan.build(
            segments = segments,
            paragraphs = paragraphs,
            characterVoices = characterVoices,
            narratorVoice = narratorVoice
        )
        if (pieces.isEmpty()) return@withContext Report(false, "本章没有可导出的文本")
        val startedAt = System.currentTimeMillis()
        val pcmOut = ByteArrayOutputStream()
        var sampleRate = 0
        var failed = 0
        pieces.forEachIndexed { index, piece ->
            val audio = runCatching {
                BuiltinTtsRuntime.synthesizeToPcm(piece.text, piece.voiceName, rate)
            }.getOrNull()
            if (audio == null || audio.bytes <= 0) {
                failed++
            } else {
                if (sampleRate <= 0) sampleRate = audio.sampleRate
                if (pcmOut.size().toLong() + audio.bytes > MAX_PCM_BYTES) {
                    return@withContext Report(false, "本章音频过大，已中止（请分段导出）")
                }
                pcmOut.write(audio.pcm)
            }
            onProgress(index + 1, pieces.size)
        }
        if (pcmOut.size() == 0) {
            return@withContext Report(false, "全部段落合成失败：先点「自检」看引擎状态")
        }
        val rateFinal = if (sampleRate > 0) sampleRate else BuiltinTtsRuntime.activeProfile()?.sampleRate ?: 24000
        val wav = PcmAudioUtils.wavBytes(pcmOut.toByteArray(), rateFinal)
        val wrote = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out -> out.write(wav) } != null
        }.getOrDefault(false)
        if (!wrote) return@withContext Report(false, "写入失败（目标文件不可写）")
        val seconds = (System.currentTimeMillis() - startedAt) / 1000
        Report(true, AudioExportPlan.summary(pieces.size, failed, seconds, wav.size.toLong()))
    }

    /** SAF 默认文件名（书名-章节名.wav） */
    fun suggestedFileName(): String {
        val bookName = ReadBook.book?.name.orEmpty()
        val chapterTitle = ReadBook.curTextChapter?.chapter?.title.orEmpty()
        return AudioExportPlan.fileName(bookName, chapterTitle)
    }
}
