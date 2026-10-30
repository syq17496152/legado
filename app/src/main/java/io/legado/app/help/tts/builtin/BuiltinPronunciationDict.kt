package io.legado.app.help.tts.builtin

import androidx.annotation.Keep
import com.google.gson.Gson
import io.legado.app.utils.LogUtils
import java.io.File

/**
 * 发音纠错词典（AD-06 / C-7）：只在引擎合成前做文本变换，**不改正文显示**。
 * - `mode=char`：替换为同音汉字（推荐，端侧 vits/克隆档均生效）
 * - `mode=pinyin`：替换为拼音串，仅在模型 lexicon 支持拼音时生效（默认不启用，计入 skipped）
 * - 最长匹配优先 + 单趟扫描（替换后不再被后续规则命中）
 */
@Keep
data class PronRule(
    val from: String = "",
    val to: String = "",
    val mode: String = "char"
)

@Keep
private data class PronDictFile(
    val version: Int = 1,
    val rules: List<PronRule> = emptyList()
)

class BuiltinPronunciationDict(
    private val file: File
) {
    data class ImportResult(val imported: Int, val skipped: Int)

    private var rules: List<PronRule> = emptyList()
    private var loadedModified: Long = -1L

    /** 加载/热更新（文件变化即重载；损坏文件不影响合成） */
    fun load(): Int {
        if (!file.isFile) {
            rules = emptyList()
            return 0
        }
        val modified = file.lastModified()
        if (modified == loadedModified) return rules.size
        rules = runCatching {
            val json = file.readText()
            Gson().fromJson(json, PronDictFile::class.java)?.rules.orEmpty()
                .filter { it.from.isNotBlank() && it.to.isNotBlank() }
                .sortedByDescending { it.from.length }
        }.onFailure {
            LogUtils.e(TAG, "发音词典解析失败：${it.message}")
        }.getOrDefault(emptyList())
        loadedModified = modified
        return rules.size
    }

    fun rules(): List<PronRule> {
        load()
        return rules
    }

    /**
     * 合成前文本变换。
     * @param allowPinyin 模型 lexicon 是否支持拼音（vits/克隆档默认 false）
     */
    fun transform(text: String, allowPinyin: Boolean = false): String {
        if (text.isEmpty()) return text
        load()
        val active = rules.filter { it.mode == "char" || (allowPinyin && it.mode == "pinyin") }
        if (active.isEmpty()) return text
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val hit = active.firstOrNull { text.startsWith(it.from, index) }
            if (hit != null) {
                out.append(hit.to)
                index += hit.from.length
            } else {
                out.append(text[index])
                index++
            }
        }
        return out.toString()
    }

    /** 保存并热更新 */
    fun save(newRules: List<PronRule>): Boolean {
        val valid = newRules.filter { it.from.isNotBlank() && it.to.isNotBlank() }
        return runCatching {
            file.parentFile?.mkdirs()
            val json = Gson().toJson(PronDictFile(1, valid))
            val part = File(file.parentFile, "${file.name}.part")
            part.writeText(json)
            if (file.exists()) file.delete()
            part.renameTo(file)
            loadedModified = -1L
            true
        }.getOrDefault(false)
    }

    /**
     * 导入社区词库：兼容 JSON（`{version,rules:[{from,to,mode}]}`）
     * 与行式（`from=to` / `from to`，忽略 # 注释）。不兼容条目计入 skipped 并明示。
     */
    fun import(content: String): ImportResult {
        val trimmed = content.trim()
        if (trimmed.startsWith("{")) {
            val parsed = runCatching { Gson().fromJson(trimmed, PronDictFile::class.java)?.rules.orEmpty() }
                .getOrDefault(emptyList())
            val valid = parsed.filter { it.from.isNotBlank() && it.to.isNotBlank() }
            val merged = mergeRules(valid)
            save(merged)
            return ImportResult(valid.size, parsed.size - valid.size)
        }
        var imported = 0
        var skipped = 0
        val lines = mutableListOf<PronRule>()
        trimmed.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val splitAt = line.indexOf('=').takeIf { it > 0 }
            val parts = if (splitAt != null) {
                listOf(line.substring(0, splitAt), line.substring(splitAt + 1))
            } else {
                line.split(Regex("\\s+"), limit = 2)
            }
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                lines.add(PronRule(parts[0].trim(), parts[1].trim(), "char"))
                imported++
            } else {
                skipped++
            }
        }
        save(mergeRules(lines))
        return ImportResult(imported, skipped)
    }

    private fun mergeRules(incoming: List<PronRule>): List<PronRule> {
        val merged = LinkedHashMap<String, PronRule>()
        rules().forEach { merged[it.from] = it }
        incoming.forEach { merged[it.from] = it }
        return merged.values.sortedByDescending { it.from.length }
    }

    private companion object {
        const val TAG = "BuiltinPronDict"
    }
}
