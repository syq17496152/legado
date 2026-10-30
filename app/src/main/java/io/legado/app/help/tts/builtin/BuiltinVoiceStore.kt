package io.legado.app.help.tts.builtin

import android.content.res.AssetManager
import androidx.annotation.Keep
import com.google.gson.Gson
import io.legado.app.utils.LogUtils
import java.io.File

/**
 * 音色条目（C-3）。`id` 是稳定绑定键（不可变），`name` 是显示名（可改）。
 * 引擎侧 Voice.name = "<id>|<name>"（AD-20），绑定键必须逐字符原样（review-final P1-1）。
 */
@Keep
data class BuiltinVoice(
    val id: String = "",
    val name: String = "",
    /** 相对 voiceRoot 的路径：`<id>/prompt.wav` */
    val refAudio: String = "",
    val refText: String = "",
    val tags: List<String> = emptyList(),
    /** preset(内置) / zip(导入) / self(自录) */
    val origin: String = ORIGIN_ZIP,
    val numSteps: Int = 0,
    val gainMultiplier: Float = 1f,
    val groupId: String = "",
    val durationMs: Long = 0,
    val gender: String = "",
    val age: String = "",
    val readOnly: Boolean = false,
    val profileId: String = "",
    val createdAt: Long = 0
) {
    /** 引擎侧绑定键（Voice.name） */
    val voiceName: String get() = "$id|${displayName}"

    val displayName: String get() = name.ifBlank { id }

    fun matches(query: String): Boolean {
        if (query.isBlank()) return false
        return id == query || id.equals(query, true) ||
                name.equals(query, true) || voiceName.equals(query, true)
    }

    companion object {
        const val ORIGIN_PRESET = "preset"
        const val ORIGIN_ZIP = "zip"
        const val ORIGIN_SELF = "self"
    }
}

@Keep
private data class BuiltinVoiceIndex(
    val version: Int = 1,
    val voices: List<BuiltinVoice> = emptyList()
)

/**
 * 音色索引（AD-03）：文件 + JSON，不新增 Room 表；单写者（@Synchronized）+ 原子写。
 */
class BuiltinVoiceStore(private val root: File) {

    private val indexFile = File(root, "index.json")
    private var cached: List<BuiltinVoice>? = null
    private var cachedModified = -1L

    fun indexFile(): File = indexFile

    @Synchronized
    fun list(): List<BuiltinVoice> {
        val modified = if (indexFile.isFile) indexFile.lastModified() else 0L
        cached?.let { if (modified == cachedModified) return it }
        val loaded = runCatching {
            if (!indexFile.isFile) emptyList() else {
                Gson().fromJson(indexFile.readText(), BuiltinVoiceIndex::class.java)?.voices.orEmpty()
            }
        }.onFailure { LogUtils.e(TAG, "音色索引损坏，按空索引自愈：${it.message}") }.getOrDefault(emptyList())
        cached = loaded
        cachedModified = modified
        return loaded
    }

    fun find(query: String): BuiltinVoice? = list().firstOrNull { it.matches(query) }

    fun findById(id: String): BuiltinVoice? = list().firstOrNull { it.id == id || it.voiceName == id }

    fun audioFile(voice: BuiltinVoice): File = File(root, voice.refAudio)

    fun exists(voice: BuiltinVoice): Boolean = audioFile(voice).isFile && audioFile(voice).length() > 0

    /** 新增/更新（重名自动消歧 `_2`；id 为稳定键，重复 id 覆盖） */
    @Synchronized
    fun upsert(voice: BuiltinVoice): BuiltinVoice {
        val current = list().toMutableList()
        val normalizedName = normalizeName(voice.name, voice.id, current)
        val stored = voice.copy(name = normalizedName, createdAt = voice.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis())
        val index = current.indexOfFirst { it.id == stored.id }
        if (index >= 0) current[index] = stored else current.add(stored)
        save(current)
        return stored
    }

    /**
     * 新增音色（写入参考音频）。
     * @return 成功返回条目；音频为空/写入失败返回 null
     */
    @Synchronized
    fun addVoice(
        name: String,
        pcmWavBytes: ByteArray?,
        sourceFile: File? = null,
        refText: String,
        tags: List<String>,
        origin: String,
        profileId: String,
        gender: String = "",
        age: String = "",
        numSteps: Int = 0,
        durationMs: Long = 0,
        readOnly: Boolean = false,
        id: String = ""
    ): BuiltinVoice? {
        if (name.isBlank() || refText.isBlank()) return null
        val stableId = id.ifBlank { generateId(name, pcmWavBytes, sourceFile) }
        val dir = File(root, stableId)
        if (!dir.exists() && !dir.mkdirs()) return null
        val target = File(dir, "prompt.wav")
        val written = runCatching {
            when {
                pcmWavBytes != null -> target.writeBytes(pcmWavBytes)
                sourceFile != null && sourceFile.isFile -> sourceFile.copyTo(target, overwrite = true)
                else -> return null
            }
            true
        }.getOrDefault(false)
        if (!written || target.length() <= 0) return null
        val voice = BuiltinVoice(
            id = stableId,
            name = name.trim(),
            refAudio = "$stableId/prompt.wav",
            refText = refText.trim(),
            tags = tags,
            origin = origin,
            numSteps = numSteps,
            groupId = "",
            durationMs = durationMs,
            gender = gender,
            age = age,
            readOnly = readOnly,
            profileId = profileId
        )
        return upsert(voice)
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val voice = findById(id) ?: return false
        if (voice.readOnly) return false
        val current = list().filterNot { it.id == voice.id }
        save(current)
        runCatching { File(root, voice.id).deleteRecursively() }
        return true
    }

    /** 用户可导出的音色（内置只读不导出，T8.10） */
    fun exportable(): List<BuiltinVoice> = list().filter { !it.readOnly }

    @Synchronized
    fun clearUserVoices() {
        val builtin = list().filter { it.readOnly || it.origin == BuiltinVoice.ORIGIN_PRESET }
        save(builtin)
    }

    @Synchronized
    internal fun save(voices: List<BuiltinVoice>) {
        root.mkdirs()
        val json = Gson().toJson(BuiltinVoiceIndex(1, voices))
        val part = File(root, "index.json.part")
        runCatching {
            part.writeText(json)
            if (indexFile.exists()) indexFile.delete()
            part.renameTo(indexFile)
            cached = voices
            cachedModified = indexFile.lastModified()
        }.onFailure { LogUtils.e(TAG, "音色索引写入失败：${it.message}") }
    }

    private fun normalizeName(name: String, id: String, current: List<BuiltinVoice>): String {
        val base = name.trim().ifBlank { id }
        val names = current.filter { it.id != id }.map { it.name }.toSet()
        if (base !in names) return base
        var suffix = 2
        while ("${base}_$suffix" in names) suffix++
        return "${base}_$suffix"
    }

    private fun generateId(name: String, wav: ByteArray?, source: File?): String {
        val seed = buildString {
            append(name)
            append('|')
            append(System.currentTimeMillis())
            if (wav != null) append(BuiltinHash.md5Hex(wav))
            if (source != null) append(source.length())
        }
        return BuiltinHash.md5Hex(seed).take(10)
    }

    private companion object {
        const val TAG = "BuiltinVoiceStore"
    }
}

/** 内置音色目录项（assets/builtin_voices/catalog.json） */
@Keep
data class BuiltinVoiceCatalogEntry(
    val id: String = "",
    val displayName: String = "",
    val referenceFileName: String = "",
    val referenceText: String = "",
    val tags: List<String> = emptyList(),
    val gender: String = "",
    val age: String = "",
    val numSteps: Int = 0
)

@Keep
private data class BuiltinVoiceCatalogJson(
    val version: Int = 1,
    val voices: List<BuiltinVoiceCatalogEntry> = emptyList()
)

/**
 * 内置 6 音色（AD-14）：首次启动把 assets 里的裁剪参考音频拷到 filesDir，并登记 `origin=preset/readOnly`。
 * 幂等：已登记同 id 且音频存在即跳过。
 */
object BuiltinVoiceAssets {

    const val ASSET_DIR = "builtin_voices"
    const val CATALOG_ASSET = "$ASSET_DIR/catalog.json"
    const val PROFILE_ID = "clone"

    fun readCatalog(assets: AssetManager): List<BuiltinVoiceCatalogEntry> {
        return runCatching {
            assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()?.let { json ->
            runCatching {
                Gson().fromJson(json, BuiltinVoiceCatalogJson::class.java)?.voices.orEmpty()
            }.getOrDefault(emptyList())
        }.orEmpty()
    }

    fun seed(assets: AssetManager, store: BuiltinVoiceStore): Int {
        val entries = readCatalog(assets)
        if (entries.isEmpty()) return 0
        var seeded = 0
        entries.forEach { entry ->
            if (entry.id.isBlank() || entry.referenceFileName.isBlank()) return@forEach
            val existing = store.findById(entry.id)
            if (existing != null && store.exists(existing)) return@forEach
            val wavBytes = runCatching {
                assets.open("$ASSET_DIR/${entry.referenceFileName}").use { it.readBytes() }
            }.getOrNull() ?: return@forEach
            val voice = store.addVoice(
                name = entry.displayName,
                pcmWavBytes = wavBytes,
                refText = entry.referenceText,
                tags = entry.tags,
                origin = BuiltinVoice.ORIGIN_PRESET,
                profileId = PROFILE_ID,
                gender = entry.gender,
                age = entry.age,
                numSteps = entry.numSteps,
                readOnly = true,
                id = entry.id
            )
            if (voice != null) seeded++
        }
        return seeded
    }
}
