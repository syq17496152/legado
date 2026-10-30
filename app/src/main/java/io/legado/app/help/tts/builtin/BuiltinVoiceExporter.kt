package io.legado.app.help.tts.builtin

import androidx.annotation.Keep
import com.google.gson.Gson
import io.legado.app.utils.LogUtils
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 音色导出（T8.10）：把**用户自有音色**（自录 + 导入）打成与 CloneTTS 备份包同构的 ZIP，
 * 便于换机/分享后一键导入（导入端就是 [ClonettsVoiceImporter]，round-trip 可单测）。
 *
 * 结构（与导入器解析路径一致）：
 * - `shared_prefs/voice_profiles_prefs.xml`：`profiles_list` JSON（id/name/promptText/roleTags/numSteps）
 * - `files/profiles/<id>/prompt.wav`：参考音频原样拷贝（已加工过，不再二次归一）
 * - `voice-pack.json`：性别/年龄/时长/采样率/标签（导入端直连入库，跳过打标）
 *
 * 边界：内置 6 音色 `readOnly=true` **不可导出**（T6.5 资产边界，避免二次分发内置音色）。
 */
class BuiltinVoiceExporter(private val store: BuiltinVoiceStore) {

    @Keep
    private data class ExportProfile(
        val id: String = "",
        val name: String = "",
        val promptText: String = "",
        val roleTags: List<String> = emptyList(),
        val numSteps: Int = 0
    )

    @Keep
    private data class ExportPackEntry(
        val id: String = "",
        val name: String = "",
        val alias: String = "",
        val f0: Float = 0f,
        val gender: String = "",
        val ageByName: String = "",
        val dur: Float = 0f,
        val sr: Int = 0,
        val numSteps: Int = 0,
        val tags: List<String> = emptyList(),
        val slot: String = "",
        val md5: String = ""
    )

    @Keep
    private data class ExportPackFile(
        val version: Int = 1,
        val totalIn: Int = 0,
        val totalOut: Int = 0,
        val voices: List<ExportPackEntry> = emptyList()
    )

    data class Report(val exported: Int, val skipped: Int, val message: String)

    /** 导出到 [target]（ZIP）；默认只导出可导出音色（内置只读被排除） */
    fun export(target: File, voices: List<BuiltinVoice> = store.exportable()): Report {
        val exportable = voices.filter { !it.readOnly }
        var exported = 0
        var skipped = 0
        runCatching {
            target.parentFile?.mkdirs()
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                val profiles = ArrayList<ExportProfile>(exportable.size)
                val packEntries = ArrayList<ExportPackEntry>(exportable.size)
                exportable.forEach { voice ->
                    val audio = store.audioFile(voice)
                    if (!audio.isFile || audio.length() <= 0) {
                        skipped++
                        return@forEach
                    }
                    val bytes = runCatching { audio.readBytes() }.getOrNull()
                    if (bytes == null) {
                        skipped++
                        return@forEach
                    }
                    zip.putNextEntry(ZipEntry("files/profiles/${voice.id}/prompt.wav"))
                    zip.write(bytes)
                    zip.closeEntry()
                    profiles.add(
                        ExportProfile(
                            id = voice.id,
                            name = voice.name,
                            promptText = voice.refText,
                            roleTags = voice.tags,
                            numSteps = voice.numSteps
                        )
                    )
                    val audioMeta = runCatching { PcmAudioUtils.readWavDownmix(bytes.inputStream()) }.getOrNull()
                    packEntries.add(
                        ExportPackEntry(
                            id = voice.id,
                            name = voice.displayName,
                            gender = voice.gender,
                            ageByName = voice.age,
                            dur = (audioMeta?.let {
                                it.pcm.size.toLong() * 1000L / (it.sampleRate.toLong() * 2L)
                            } ?: voice.durationMs).toFloat() / 1000f,
                            sr = audioMeta?.sampleRate ?: 0,
                            numSteps = voice.numSteps,
                            tags = voice.tags,
                            md5 = BuiltinHash.md5Hex(bytes)
                        )
                    )
                    exported++
                }
                zip.putNextEntry(ZipEntry("shared_prefs/voice_profiles_prefs.xml"))
                zip.write(buildPrefsXml(Gson().toJson(profiles)).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("voice-pack.json"))
                zip.write(
                    Gson().toJson(
                        ExportPackFile(
                            totalIn = exportable.size,
                            totalOut = packEntries.size,
                            voices = packEntries
                        )
                    ).toByteArray(Charsets.UTF_8)
                )
                zip.closeEntry()
            }
        }.onFailure {
            LogUtils.e(TAG, "音色导出失败：${it.stackTraceToString()}")
            return Report(exported, skipped, "导出失败：${it.message}")
        }
        return Report(exported, skipped, "已导出 $exported 个音色" + if (skipped > 0) "（跳过 $skipped）" else "")
    }

    /** 与 CloneTTS 一致的 preferences XML 片段（JSON 里的引号做 XML 转义，导入端会反转义） */
    internal fun buildPrefsXml(profilesJson: String): String {
        val escaped = profilesJson
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "&#10;")
            .replace("'", "&#39;")
        return "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
                "<map>\n" +
                "    <string name=\"profiles_list\">$escaped</string>\n" +
                "</map>\n"
    }

    private companion object {
        const val TAG = "BuiltinVoiceExport"
    }
}
