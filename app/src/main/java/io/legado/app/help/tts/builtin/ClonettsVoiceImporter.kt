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
            var skipped = 0
            var duplicates = 0
            var missingAudio = 0
            var failureMessage = ""
            // 批量入库缓冲：整批只写一次索引（顺序 upsert 会把全量索引写 500 次，导入拖到分钟级）
            val pending = ArrayList<BuiltinVoice>()
            val seenHashes = existingAudioHashes().toMutableSet()
            runCatching {
                ZipFile(zipFile).use { zip ->
                    val pack = readVoicePack(zip)
                    val packById = pack?.voices.orEmpty().associateBy { it.id }
                    val profiles = readProfiles(zip)
                    total = profiles.size
                    if (total == 0) {
                        failureMessage = "备份包内未找到音色清单（profiles_list）"
                        return@use
                    }
                    profiles.forEach { profile ->
                        val id = profile.id
                        val name = profile.name.ifBlank { "音色${pending.size + 1}" }
                        val refText = profile.promptText.trim()
                        if (id.isBlank() || refText.isBlank()) {
                            skipped++
                            return@forEach
                        }
                        // 重导入幂等：zip 内 profile 的 id（UUID）就是稳定身份，已入库直接算重复
                        if (store.findById(id) != null) {
                            duplicates++
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
                        val packEntry = packById[id]
                        // 值域归一（2026-11-10）：导入包写中文（女/青年/未定），内置写英文（female/teen）
                        // ⇒ 入库即归一，避免列表/筛选出现两套口径
                        val rawGender = packEntry?.gender.orEmpty()
                        val rawAge = packEntry?.ageByName.orEmpty()
                        val gender = VoiceTagNormalizer.gender(rawGender)
                        val age = VoiceTagNormalizer.age(rawAge).ifBlank {
                            VoiceTagNormalizer.ageFromText(name)
                        }
                        val tags = VoiceTagNormalizer.tags(
                            packEntry?.tags.orEmpty() + profile.roleTags,
                            gender,
                            age
                        )
                        // T2.2：入库前统一加工（下混/裁静音/重采样/峰值归一/时长校验）；
                        // 加工失败保留原始音频（不能因为"太短/太长"就让整包导入失败）
                        val processed = ReferenceAudioProcessor.process(wavBytes, targetSampleRate)
                        val audioBytes = (processed as? ReferenceAudioProcessor.Result.Success)
                            ?.wavBytes ?: wavBytes
                        val durationMs = (processed as? ReferenceAudioProcessor.Result.Success)
                            ?.durationMs ?: (packEntry?.dur ?: 0f).toLong() * 1000
                        // 去重必须用**入库后的音频**指纹：库内存的是加工后的 wav，拿原始字节比对永远不命中
                        val audioHash = BuiltinHash.md5Hex(audioBytes)
                        if (audioHash in seenHashes) {
                            duplicates++
                            skipped++
                            return@forEach
                        }
                        val voice = store.prepareVoice(
                            name = name,
                            pcmWavBytes = audioBytes,
                            refText = refText,
                            tags = tags,
                            origin = BuiltinVoice.ORIGIN_ZIP,
                            profileId = profileId,
                            gender = gender,
                            age = age,
                            numSteps = (packEntry?.numSteps ?: 0).takeIf { it > 0 } ?: profile.numSteps,
                            durationMs = durationMs,
                            id = id
                        )
                        if (voice == null) {
                            skipped++
                        } else {
                            pending.add(voice)
                            seenHashes.add(audioHash)
                        }
                    }
                }
            }.onFailure {
                LogUtils.e(TAG, "备份包导入失败：${it.message}")
                failureMessage = "导入失败：${it.message}"
            }
            // 音频已落盘、索引整批写一次；写失败按 0 条计（不能把已成功的条目谎报成失败）
            val saved = runCatching { store.upsertAll(pending) }
                .onFailure { LogUtils.e(TAG, "音色索引批量写入失败：${it.message}") }
                .getOrDefault(emptyList())
            val imported = saved.size
            // 池聚合放在导入内部完成（仍在 IO 线程）：调用方无需记得再同步，也不会在主线程写索引
            if (saved.isNotEmpty()) {
                runCatching { BuiltinVoicePools.sync(store) }
                    .onFailure { LogUtils.e(TAG, "声音池同步失败：${it.message}") }
            }
            Report(
                total = total,
                imported = imported,
                skipped = skipped,
                duplicates = duplicates,
                missingAudio = missingAudio,
                message = failureMessage.ifBlank {
                    "导入 $imported，跳过 $skipped（重复 $duplicates，缺音频 $missingAudio）"
                }
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
