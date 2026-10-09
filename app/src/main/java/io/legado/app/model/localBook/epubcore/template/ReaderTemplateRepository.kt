package io.legado.app.model.localBook.epubcore.template

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.help.config.AtomicTextFileStore
import java.io.File
import java.util.UUID

/**
 * 阅读页模板库（epub-md-rich-rendering 阶段 4.5 / blueprint §五）。
 *
 * 存储：`<root>/readerTemplates.json`（**仅用户模板**；内置模板从 assets 注入，不占用户库 id 空间）。
 * 键：库文件自身带 `schemaVersion`（与单模板 schema 独立演进）。
 *
 * 硬契约：
 * - **原子写**：复用 [AtomicTextFileStore]（staging → 校验 → 原子替换）；
 * - **损坏不空库**：库文件损坏时**不覆盖**，`list()` 返回空集合并通过 [lastError] 暴露原因
 *   （用户可用"恢复默认"显式重置，而不是被静默清空 —— 这是 4.3 同类教训的延伸）；
 * - **容量治理**：模板数上限 + 单模板体量上限（compat 报告 §五 明确 `css > 2MB` 应优雅报错）；
 * - **版本演进**：读入的 v1 模板经 [ReaderTemplateImportMapper.upgradeIfNeeded] 升级到当前 schema；
 * - 所有写操作**先校验结构**再落盘（半成品模板不入库）。
 *
 * 纯 JVM（`java.io` + Gson）⇒ 可在临时目录上单测。
 */
internal class ReaderTemplateRepository(
    private val root: File,
    private val maxTemplates: Int = DefaultMaxTemplates,
    private val maxTemplateChars: Int = DefaultMaxTemplateChars
) {

    /** 最近一次读取失败原因（供诊断；成功读取后清空）。 */
    var lastError: String? = null
        private set

    fun list(): List<EpubReaderTemplate> {
        val loaded = loadLibrary() ?: return emptyList()
        return loaded.map { ReaderTemplateImportMapper.upgradeIfNeeded(it) }
    }

    fun get(id: String): EpubReaderTemplate? = list().firstOrNull { it.id == id }

    /** 新增或覆盖（结构必须合法）。 */
    fun save(template: EpubReaderTemplate): Boolean {
        val errors = template.validate()
        if (errors.isNotEmpty()) {
            lastError = errors.joinToString("；")
            return false
        }
        if (template.resourceSource().length > maxTemplateChars) {
            lastError = "模板体量超限：${template.resourceSource().length} > $maxTemplateChars"
            return false
        }
        val current = list().toMutableList()
        val index = current.indexOfFirst { it.id == template.id }
        if (index >= 0) {
            current[index] = template
        } else {
            if (current.size >= maxTemplates) {
                lastError = "模板数量已达上限 $maxTemplates"
                return false
            }
            current.add(template)
        }
        return writeLibrary(current)
    }

    fun delete(id: String): Boolean {
        val current = list()
        val remaining = current.filterNot { it.id == id }
        if (remaining.size == current.size) return false
        return writeLibrary(remaining)
    }

    /** 复制（新 id + 新名称）；源不存在返回 null。 */
    fun duplicate(id: String, newName: String? = null): EpubReaderTemplate? {
        val source = get(id) ?: return null
        val copy = source.copy(
            id = "user.${UUID.randomUUID()}",
            name = newName?.takeIf { it.isNotBlank() } ?: "${source.name} 副本"
        )
        return if (save(copy)) copy else null
    }

    /**
     * 导入单模板 JSON（含 archive v1 兼容）；导入成功即入库。
     *
     * @return 导入报告；成功时 [ReaderTemplateImportMapper.ImportResult.template] 为**入库后**的模板。
     */
    fun importJson(source: String): ReaderTemplateImportMapper.ImportResult {
        val result = ReaderTemplateImportMapper.importTemplateJson(source)
        val template = result.template ?: return result
        if (!save(template)) {
            return ReaderTemplateImportMapper.ImportResult(
                template = null,
                report = result.report + ReaderTemplateImportMapper.ReportEntry(
                    ReaderTemplateImportMapper.Level.ERROR,
                    "save-failed",
                    "模板入库失败：${lastError.orEmpty()}"
                )
            )
        }
        return result
    }

    /** 导出单模板 JSON（用于分享/备份）。 */
    fun exportJson(id: String): String? = get(id)?.toJson()

    /** 清空用户模板（内置模板来自 assets，不受影响）。 */
    fun clearUserTemplates(): Int {
        val count = list().size
        writeLibrary(emptyList())
        return count
    }

    fun count(): Int = list().size

    // === 内部 ===

    private fun libraryFile(): File = File(root, LibraryFileName)

    /** 读库；文件不存在返回空集；损坏返回 null 并记录原因（**不覆盖文件**）。 */
    private fun loadLibrary(): List<EpubReaderTemplate>? {
        lastError = null
        val file = libraryFile()
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText() }.getOrElse { error ->
            lastError = "模板库读取失败：${error.localizedMessage}"
            return null
        }
        // 注意：此处**不得**清空 lastError —— parseLibrary 会为"被跳过的损坏条目"写入原因，
        // 那条诊断正是"损坏不空库"可被用户感知的凭据（读取前已重置一次）。
        return runCatching { parseLibrary(text) }.getOrElse { error ->
            lastError = "模板库解析失败（未覆盖，可恢复默认）：${error.localizedMessage}"
            null
        }
    }

    private fun parseLibrary(text: String): List<EpubReaderTemplate> {
        if (text.isBlank()) return emptyList()
        val rootElement = JsonParser.parseString(text)
        val array: JsonArray = when {
            rootElement.isJsonArray -> rootElement.asJsonArray
            rootElement.isJsonObject -> {
                val version = rootElement.asJsonObject.get("schemaVersion")
                    ?.takeIf { it.isJsonPrimitive }?.asInt
                require(version == null || version <= LibrarySchemaVersion) {
                    "模板库版本 $version 高于当前支持版本 $LibrarySchemaVersion"
                }
                rootElement.asJsonObject.getAsJsonArray("templates") ?: JsonArray()
            }
            else -> error("模板库根节点必须是对象或数组")
        }
        return array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            // 单条损坏只跳过该条（**不空库**）并记录；这是"损坏不空库"的最后一层保障
            runCatching { EpubReaderTemplate.fromJsonObject(element.asJsonObject) }
                .onFailure { lastError = "已跳过损坏模板条目：${it.localizedMessage}" }
                .getOrNull()
        }
    }

    private fun writeLibrary(templates: List<EpubReaderTemplate>): Boolean {
        val payload = JsonObject().apply {
            addProperty("schemaVersion", LibrarySchemaVersion)
            add("templates", JsonArray().apply {
                templates.forEach { add(it.toJsonObject()) }
            })
        }
        val text = payload.toString()
        return runCatching {
            AtomicTextFileStore(libraryFile()).writeVerified(text) { it == text }
            lastError = null
            true
        }.getOrElse { error ->
            lastError = "模板库写入失败：${error.localizedMessage}"
            false
        }
    }

    companion object {
        const val LibrarySchemaVersion = 2
        const val LibraryFileName = "readerTemplates.json"
        const val DefaultMaxTemplates = 64

        /** 单模板体量上限（compat 报告 §五：`css > 2MB` 应优雅报错）。 */
        const val DefaultMaxTemplateChars = 2 * 1024 * 1024
    }
}