package io.legado.app.help.readaloud.role

import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.legado.app.help.tts.builtin.BuiltinHash
import java.io.File

/**
 * 固定绑定（AD-15⑤ / C-12 / T8.14）：与 CloneTTS **同 schema**，可与其社区文件互通。
 * 全局：`legado_rules/fixed_roles.json`；书级：`legado_rules/book/<bookKey>.json`（书级优先）。
 *
 * schema：
 * ```json
 * { "_narrator":"旁白音色", "_maleLead":"男主角色名", "_maleLeadVoice":"男声音色",
 *   "_femaleLead":"女主角色名", "_femaleLeadVoice":"女声音色", "_comment":"…",
 *   "角色名":"音色名/别名" }
 * ```
 */
@Keep
data class FixedRoles(
    val narrator: String = "",
    val maleLead: String = "",
    val maleLeadVoice: String = "",
    val femaleLead: String = "",
    val femaleLeadVoice: String = "",
    val roles: Map<String, String> = emptyMap()
) {
    val isEmpty: Boolean
        get() = narrator.isBlank() && maleLead.isBlank() && maleLeadVoice.isBlank() &&
                femaleLead.isBlank() && femaleLeadVoice.isBlank() && roles.isEmpty()

    fun mergedWith(overrides: FixedRoles): FixedRoles = FixedRoles(
        narrator = overrides.narrator.ifBlank { narrator },
        maleLead = overrides.maleLead.ifBlank { maleLead },
        maleLeadVoice = overrides.maleLeadVoice.ifBlank { maleLeadVoice },
        femaleLead = overrides.femaleLead.ifBlank { femaleLead },
        femaleLeadVoice = overrides.femaleLeadVoice.ifBlank { femaleLeadVoice },
        roles = roles + overrides.roles
    )
}

class BookAliasStore(private val rulesRoot: File) {

    fun globalFile(): File = File(rulesRoot, GLOBAL_FILE)

    fun bookDir(): File = File(rulesRoot, "book")

    fun bookFile(bookKey: String): File = File(bookDir(), "${safeKey(bookKey)}.json")

    fun cueFile(bookKey: String): File = File(bookDir(), "${safeKey(bookKey)}.cues.json")

    fun readGlobal(): FixedRoles = read(globalFile())

    fun readBook(bookKey: String): FixedRoles = read(bookFile(bookKey))

    /** 合并视图：书级 > 全局 */
    fun merged(bookKey: String): FixedRoles = readGlobal().mergedWith(readBook(bookKey))

    /** 角色 → 音色（先按角色名，再按主角特殊键） */
    fun voiceFor(bookKey: String, characterName: String): String? {
        val merged = merged(bookKey)
        merged.roles[characterName]?.takeIf { it.isNotBlank() }?.let { return it }
        val normalized = normalize(characterName)
        merged.roles.entries.firstOrNull { normalize(it.key) == normalized }
            ?.value?.takeIf { it.isNotBlank() }?.let { return it }
        return when (normalized) {
            normalize(merged.maleLead) -> merged.maleLeadVoice.ifBlank { null }
            normalize(merged.femaleLead) -> merged.femaleLeadVoice.ifBlank { null }
            else -> null
        }
    }

    fun narratorVoice(bookKey: String): String? =
        merged(bookKey).narrator.takeIf { it.isNotBlank() }

    fun writeGlobal(roles: FixedRoles): Boolean = write(globalFile(), roles)

    fun writeBook(bookKey: String, roles: FixedRoles): Boolean = write(bookFile(bookKey), roles)

    /** 改段学习：记录 cue 模式 → 角色（独立文件，避免污染 CloneTTS 兼容 schema） */
    fun learnCue(bookKey: String, cue: String, characterName: String) {
        val key = cue.trim()
        if (key.isEmpty() || characterName.isBlank()) return
        val file = cueFile(bookKey)
        val map = cuesFor(bookKey).toMutableMap()
        map[key] = characterName.trim()
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(Gson().toJson(map))
        }
    }

    fun cuesFor(bookKey: String): Map<String, String> {
        val file = cueFile(bookKey)
        if (!file.isFile) return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, String>>() {}.type
            Gson().fromJson<Map<String, String>>(file.readText(), type).orEmpty()
        }.getOrDefault(emptyMap())
    }

    fun clearBook(bookKey: String) {
        runCatching { bookFile(bookKey).delete() }
        runCatching { cueFile(bookKey).delete() }
    }

    private fun read(file: File): FixedRoles {
        if (!file.isFile) return FixedRoles()
        return runCatching {
            val root = Gson().fromJson(file.readText(), Map::class.java) as? Map<*, *>
                ?: return FixedRoles()
            val roles = LinkedHashMap<String, String>()
            root.forEach { (key, value) ->
                val name = key?.toString().orEmpty()
                val voice = value?.toString().orEmpty()
                if (name.isNotBlank() && voice.isNotBlank() && !name.startsWith("_")) {
                    roles[name] = voice
                }
            }
            FixedRoles(
                narrator = root["_narrator"]?.toString().orEmpty(),
                maleLead = root["_maleLead"]?.toString().orEmpty(),
                maleLeadVoice = root["_maleLeadVoice"]?.toString().orEmpty(),
                femaleLead = root["_femaleLead"]?.toString().orEmpty(),
                femaleLeadVoice = root["_femaleLeadVoice"]?.toString().orEmpty(),
                roles = roles
            )
        }.getOrDefault(FixedRoles())
    }

    private fun write(file: File, roles: FixedRoles): Boolean {
        return runCatching {
            file.parentFile?.mkdirs()
            val root = LinkedHashMap<String, String>()
            root["_comment"] = "与 CloneTTS fixed_roles.json 同 schema；键为角色名，值为音色名/别名"
            if (roles.narrator.isNotBlank()) root["_narrator"] = roles.narrator
            if (roles.maleLead.isNotBlank()) root["_maleLead"] = roles.maleLead
            if (roles.maleLeadVoice.isNotBlank()) root["_maleLeadVoice"] = roles.maleLeadVoice
            if (roles.femaleLead.isNotBlank()) root["_femaleLead"] = roles.femaleLead
            if (roles.femaleLeadVoice.isNotBlank()) root["_femaleLeadVoice"] = roles.femaleLeadVoice
            roles.roles.forEach { (name, voice) -> root[name] = voice }
            val part = File(file.parentFile, "${file.name}.part")
            part.writeText(Gson().toJson(root))
            if (file.exists()) file.delete()
            part.renameTo(file)
            true
        }.getOrDefault(false)
    }

    private fun safeKey(bookKey: String): String =
        if (bookKey.length <= 40 && bookKey.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            bookKey
        } else {
            BuiltinHash.md5Hex(bookKey)
        }

    private fun normalize(value: String): String =
        value.trim().replace(Regex("\\s+"), "").trim('《', '》', '“', '”', '"', '\'', '：', ':')

    private companion object {
        const val GLOBAL_FILE = "fixed_roles.json"
    }
}
