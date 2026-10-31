package io.legado.app.ui.book.read.config

import android.content.Context
import android.net.Uri
import io.legado.app.help.readaloud.export.AudioBookExporter
import io.legado.app.help.tts.builtin.BuiltinTtsRuntime
import io.legado.app.help.tts.builtin.BuiltinVoiceExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 管理页「导出类」操作的执行体（G-38 行数门禁：Activity 只留 launcher + 状态回写）。
 * - 音色包 ZIP 导出（T8.10）
 * - 本章音频 WAV 导出（T8.5）
 */
internal object BuiltinAudioExportOps {

    /** 音色包导出：内置音色只读不进包，返回可展示的结果文案 */
    suspend fun exportVoicePack(
        context: Context,
        uri: Uri,
        cacheDir: File
    ): String = withContext(Dispatchers.IO) {
        val target = File(cacheDir, "voice-export.zip")
        val report = BuiltinVoiceExporter(BuiltinTtsRuntime.voiceStore()).export(target)
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                target.inputStream().use { input -> input.copyTo(out) }
            }
            true
        }.getOrDefault(false)
        runCatching { target.delete() }
        if (ok && report.exported > 0) report.message else "导出失败（无音色或写入被拒绝）"
    }

    /** 本章音频导出：逐段合成（命中缓存）后拼 WAV 写 SAF，进度走 onProgress 文案 */
    suspend fun exportChapterAudio(
        context: Context,
        uri: Uri,
        onProgress: (String) -> Unit
    ): String {
        val report = AudioBookExporter.exportChapter(context, uri) { done, total ->
            onProgress("合成中 $done/$total")
        }
        return report.message
    }

    /** SAF 默认文件名（书名-章节名.wav） */
    fun chapterFileName(): String = AudioBookExporter.suggestedFileName()
}
