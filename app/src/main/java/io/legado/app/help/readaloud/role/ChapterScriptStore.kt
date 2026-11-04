package io.legado.app.help.readaloud.role

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.legado.app.help.readaloud.role.OfflineRoleSegment
import io.legado.app.help.tts.builtin.BuiltinHash
import java.io.File

/**
 * 用户手改（override）存储（AD-03）：键 = `段落|起|止`，书级独立文件 `overrides.json`。
 *
 * 与 `OfflineRoleCacheStore.updateSegmentAssignment` **双写**：旧链路（分镜页）继续可用，
 * 剧本层按 override 最优先合并；正文大改后 override 仍保留（`stale` 由剧本页提示复核），
 * 因此这里只存位置与角色，不存正文。
 */
class ScriptOverrideStore(private val root: File) {

    fun bookDir(bookKey: String): File = File(root, safeKey(bookKey))

    fun overrideFile(bookKey: String): File = File(bookDir(bookKey), OVERRIDE_FILE)

    fun read(bookKey: String): Map<String, ScriptSegment> {
        val file = overrideFile(bookKey)
        if (!file.isFile) return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, ScriptSegment>>() {}.type
            Gson().fromJson<Map<String, ScriptSegment>>(file.readText(), type).orEmpty()
        }.getOrDefault(emptyMap())
    }

    fun put(bookKey: String, key: String, segment: ScriptSegment): Boolean {
        if (key.isBlank()) return false
        val merged = LinkedHashMap(read(bookKey))
        merged[key] = segment.copy(frozen = true, confidence = 1.0, source = "user-frozen")
        return write(bookKey, merged)
    }

    fun remove(bookKey: String, key: String): Boolean {
        val merged = LinkedHashMap(read(bookKey))
        if (merged.remove(key) == null) return true
        return write(bookKey, merged)
    }

    fun clear(bookKey: String): Boolean = runCatching { overrideFile(bookKey).delete() }.getOrDefault(true)

    private fun write(bookKey: String, map: Map<String, ScriptSegment>): Boolean = runCatching {
        val target = overrideFile(bookKey)
        target.parentFile?.mkdirs()
        // 原子写：先写 .part 再替换，避免进程被杀留下半写 JSON
        val part = File(target.parentFile, "${target.name}.part")
        part.writeText(Gson().toJson(map))
        if (target.exists()) target.delete()
        part.renameTo(target)
    }.getOrDefault(false)

    companion object {
        private const val OVERRIDE_FILE = "overrides.json"

        /** 段位置键：段号 + 字符区间（同一段内换绑不会误伤其它段） */
        fun keyOf(segment: OfflineRoleSegment): String =
            "${segment.paragraphIndex}|${segment.start}|${segment.end}"

        fun keyOf(segment: ScriptSegment): String =
            "${segment.paragraphIndex}|${segment.start}|${segment.end}"

        private fun safeKey(bookKey: String): String =
            if (bookKey.length <= 40 && bookKey.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
                bookKey
            } else {
                BuiltinHash.md5Hex(bookKey)
            }
    }
}

/**
 * 章节剧本书（3.1）：`scripts/<bookKey>/<chapterIndex>.json` + `index.json`。
 *
 * 关键行为（都有单测锁定）：
 * - 读取只认当前 `schemaVersion`；旧版本文件一律忽略（不半信半疑地复用）
 * - 只读 `.json`（`.tmp/.part` 半写文件永不进入读取路径）
 * - 写入先 `.tmp` 再替换（进程被杀不留半写）
 * - 30 天未读清理（仅删缓存文件，不动 override）
 */
class ChapterScriptStore(private val root: File) {

    private val overrideStore = ScriptOverrideStore(root)

    fun bookDir(bookKey: String): File = File(root, safeKey(bookKey))

    fun scriptFile(bookKey: String, chapterIndex: Int): File =
        File(bookDir(bookKey), "$chapterIndex.json")

    fun indexFile(bookKey: String): File = File(bookDir(bookKey), INDEX_FILE)

    fun overrides(bookKey: String): Map<String, ScriptSegment> = overrideStore.read(bookKey)

    fun putOverride(bookKey: String, segment: ScriptSegment): Boolean =
        overrideStore.put(bookKey, ScriptOverrideStore.keyOf(segment), segment)

    fun removeOverride(bookKey: String, segment: ScriptSegment): Boolean =
        overrideStore.remove(bookKey, ScriptOverrideStore.keyOf(segment))

    /** 读剧本：缺文件 / 坏 JSON / schema 过期 / 书键与章号不符 ⇒ null（调用方回落规则） */
    fun read(bookKey: String, chapterIndex: Int): ChapterScript? {
        val file = scriptFile(bookKey, chapterIndex)
        if (!file.isFile) return null
        val parsed = runCatching {
            Gson().fromJson(file.readText(), ChapterScript::class.java)
        }.getOrNull() ?: return null
        if (parsed.schemaVersion != ChapterScript.CURRENT_SCHEMA_VERSION) return null
        if (parsed.bookKey.isNotBlank() && parsed.bookKey != bookKey) return null
        if (parsed.chapterIndex != chapterIndex) return null
        return parsed
    }

    /** 是否存在可复用剧本（按逐段指纹变化率判断，>10% 必须重算） */
    fun reusable(bookKey: String, chapterIndex: Int, paragraphs: List<String>): ChapterScript? {
        val existing = read(bookKey, chapterIndex) ?: return null
        val hashes = paragraphs.map { ChapterScriptBuilder.hashOf(it) }
        return existing.takeIf { it.reusableFor(hashes) }
    }

    fun write(script: ChapterScript): Boolean = runCatching {
        val dir = bookDir(script.bookKey)
        dir.mkdirs()
        val target = File(dir, "${script.chapterIndex}.json")
        // 原子写：只写 .tmp，成功后再替换（半写文件不会被 read 选中）
        val part = File(dir, "${target.name}.tmp")
        part.writeText(Gson().toJson(script))
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) return@runCatching false
        updateIndex(script)
        true
    }.getOrDefault(false)

    /**
     * 30 天未读清理（默认 30 天；传入 now 便于单测注入时钟）。
     * 只删章节 JSON 与索引，**不动 overrides.json**（用户手改不是缓存）。
     */
    fun cleanup(retentionMs: Long = DEFAULT_RETENTION_MS, now: Long = System.currentTimeMillis()): Int {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return 0
        var removed = 0
        dirs.forEach { dir ->
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".json") && it.name != OVERRIDE_FILE }
                ?.filter { now - it.lastModified() > retentionMs }
                ?.forEach { file ->
                    if (runCatching { file.delete() }.getOrDefault(false)) removed++
                }
        }
        return removed
    }

    /** 索引：章级 contentHash / source / generatedAt（便于设置页展示与排查，不参与复用判定） */
    private fun updateIndex(script: ChapterScript) {
        val file = indexFile(script.bookKey)
        val map = runCatching {
            val type = object : TypeToken<Map<String, ChapterIndexEntry>>() {}.type
            Gson().fromJson<Map<String, ChapterIndexEntry>>(file.readText(), type).orEmpty()
        }.getOrDefault(emptyMap()).toMutableMap()
        map[script.chapterIndex.toString()] = ChapterIndexEntry(
            contentHash = script.contentHash,
            source = script.source,
            generatedAt = script.generatedAt,
            segments = script.segments.size
        )
        runCatching {
            file.writeText(Gson().toJson(map))
        }
    }

    private fun safeKey(bookKey: String): String =
        if (bookKey.length <= 40 && bookKey.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            bookKey
        } else {
            BuiltinHash.md5Hex(bookKey)
        }

    companion object {
        private const val INDEX_FILE = "index.json"
        private const val OVERRIDE_FILE = "overrides.json"

        /** 30 天未读 ⇒ 视为可清理缓存 */
        const val DEFAULT_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}

/** 索引条目（Gson 模型必须 @Keep，防 R8 剥泛型签名） */
@androidx.annotation.Keep
data class ChapterIndexEntry(
    val contentHash: String = "",
    val source: String = "rule",
    val generatedAt: Long = 0,
    val segments: Int = 0
)
