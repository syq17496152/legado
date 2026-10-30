package io.legado.app.help.tts.builtin

import io.legado.app.utils.LogUtils
import androidx.annotation.Keep
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * CloneTTS 备份包导入（T6.1 / C-5 / T7.3）：
 * ①`shared_prefs/voice_profiles_prefs.xml` 里的 `profiles_list` JSON（名字/逐字稿/标签/步数）
 * ②`files/profiles/<UUID>/prompt.wav` 参考音频
 * ③可选 `voice-pack.json`（清洗清单：性别/年龄/F0/质量）→ 直连入库跳过打标
 * 去重按音频 md5（实测 653→506），缺失音频/坏条目单列计入 skipped。
 */
@Keep
private data class VoicePackEntry(
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
private data class VoicePackFile(
    val version: Int = 0,
    val totalIn: Int = 0,
    val totalOut: Int = 0,
    val voices: List<VoicePackEntry> = emptyList()
)

class ClonettsVoiceImporter(
    private val store: BuiltinVoiceStore
) {

    data class Report(
        val total: Int = 0,
        val imported: Int = 0,
        val skipped: Int = 0,
        val duplicates: Int = 0,
        val missingAudio: Int = 0,
        val message: String = ""
    )

    /** 纯字节去重表：store 里已有音色的音频 md5（每次导入重算，音色规模 500 级开销可接受） */
    private fun existingAudioHashes(): Set<String> =
        store.list().mapNotNull { voice ->
            val file = store.audioFile(voice)
            if (file.isFile) BuiltinHash.md5Hex(file.readBytes()) else null
        }.toSet()

    suspend fun importZip(
        zipFile: File,
        profileId: String = "clone",
        targetSampleRate: Int = 24000
    ): Report =
        withContext(Dispatchers.IO) {
            if (!zipFile.isFile) return@withContext Report(message = "文件不存在")
            var total = 0
            var imported = 0
            var skipped = 0
            var duplicates = 0
            var missingAudio = 0
            val seenHashes = existingAudioHashes().toMutableSet()
            runCatching {
                ZipFile(zipFile).use { zip ->
                    val pack = readVoicePack(zip)
                    val packById = pack?.voices.orEmpty().associateBy { it.id }
                    val profiles = readProfiles(zip)
                    total = profiles.size
                    if (total == 0) {
                        return@use Report(message = "备份包内未找到音色清单（profiles_list）")
                    }
                    profiles.forEach { profile ->
                        val id = profile.id
                        val name = profile.name.ifBlank { "音色${imported + 1}" }
                        val refText = profile.promptText.trim()
                        if (id.isBlank() || refText.isBlank()) {
                            skipped++
                            return@forEach
                        }
                        val audioEntry = findAudioEntry(zip, id)
                        if (audioEntry == null) {
                            missingAudio++
                            skipped++
                            return@forEach
                        }
                        val wavBytes = runCatching { zip.getInputStream(audioEntry).use { it.readBytes() } }
                            .getOrNull()
                        if (wavBytes == null || wavBytes.size < 44) {
                            missingAudio++
                            skipped++
                            return@forEach
                        }
                        val audioHash = BuiltinHash.md5Hex(wavBytes)
                        if (audioHash in seenHashes) {
                            duplicates++
                            skipped++
                            return@forEach
                        }
                        val packEntry = packById[id]
                        // T2.2：入库前统一加工（下混/裁静音/重采样/峰值归一/时长校验）；
                        // 加工失败保留原始音频（不能因为"太短/太长"就让整包导入失败）
                        val processed = ReferenceAudioProcessor.process(wavBytes, targetSampleRate)
                        val audioBytes = (processed as? ReferenceAudioProcessor.Result.Success)
                            ?.wavBytes ?: wavBytes
                        val durationMs = (processed as? ReferenceAudioProcessor.Result.Success)
                            ?.durationMs ?: (packEntry?.dur ?: 0f).toLong() * 1000
                        val gender = packEntry?.gender.orEmpty()
                        val age = packEntry?.ageByName.orEmpty()
                        val tags = (packEntry?.tags.orEmpty() + profile.roleTags).filter { it.isNotBlank() }
                        val voice = store.addVoice(
                            name = name,
                            pcmWavBytes = audioBytes,
                            refText = refText,
                            tags = tags,
                            origin = BuiltinVoice.ORIGIN_ZIP,
                            profileId = profileId,
                            gender = gender,
                            age = age,
                            numSteps = (packEntry?.numSteps ?: 0).takeIf { it > 0 } ?: profile.numSteps,
                            durationMs = durationMs
                        )
                        if (voice == null) {
                            skipped++
                        } else {
                            seenHashes.add(audioHash)
                            imported++
                        }
                    }
                }
            }.onFailure {
                LogUtils.e(TAG, "备份包导入失败：${it.message}")
                return@withContext Report(total = total, imported = imported, skipped = skipped, message = "导入失败：${it.message}")
            }
            Report(
                total = total,
                imported = imported,
                skipped = skipped,
                duplicates = duplicates,
                missingAudio = missingAudio,
                message = "导入 $imported，跳过 $skipped（重复 $duplicates，缺音频 $missingAudio）"
            )
        }

    private data class ProfileEntry(
        val id: String = "",
        val name: String = "",
        val promptText: String = "",
        val roleTags: List<String> = emptyList(),
        val numSteps: Int = 0
    )

    private fun readVoicePack(zip: ZipFile): VoicePackFile? {
        val entry = zip.getEntry("voice-pack.json") ?: return null
        return runCatching {
            zip.getInputStream(entry).use { stream ->
                Gson().fromJson(stream.bufferedReader().readText(), VoicePackFile::class.java)
            }
        }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun readProfiles(zip: ZipFile): List<ProfileEntry> {
        val entry = zip.getEntry("shared_prefs/voice_profiles_prefs.xml") ?: return emptyList()
        val xml = runCatching { zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) } }
            .getOrDefault("")
        if (xml.isBlank()) return emptyList()
        val match = Regex("<string name=\"profiles_list\">([\\s\\S]*?)</string>").find(xml) ?: return emptyList()
        val json = unescapeXml(match.groupValues[1])
        val parsed = runCatching { Gson().fromJson(json, List::class.java) }.getOrNull()
            ?: runCatching {
                val map = Gson().fromJson(json, Map::class.java)
                map?.get("profiles") as? List<*>
            }.getOrNull()
        val list = parsed ?: return emptyList()
        return list.mapNotNull { raw ->
            val item = raw as? Map<*, *> ?: return@mapNotNull null
            val id = item["id"]?.toString().orEmpty()
            if (id.isBlank()) return@mapNotNull null
            val tags = (item["roleTags"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
            ProfileEntry(
                id = id,
                name = item["name"]?.toString().orEmpty(),
                promptText = item["promptText"]?.toString().orEmpty(),
                roleTags = tags,
                numSteps = (item["numSteps"] as? Number)?.toInt() ?: 0
            )
        }
    }

    private fun findAudioEntry(zip: ZipFile, id: String): java.util.zip.ZipEntry? {
        val candidates = listOf(
            "files/profiles/$id/prompt.wav",
            "profiles/$id/prompt.wav",
            "files/profiles/$id/reference.wav"
        )
        candidates.forEach { path -> zip.getEntry(path)?.let { return it } }
        // 兜底：同 id 目录下任意 wav
        return zip.entries().asSequence().firstOrNull {
            it.name.contains("/$id/") && it.name.endsWith(".wav", true)
        }
    }

    private fun unescapeXml(value: String): String {
        return value
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#10;", "\n")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
    }

    private companion object {
        const val TAG = "ClonettsImporter"
    }
}
