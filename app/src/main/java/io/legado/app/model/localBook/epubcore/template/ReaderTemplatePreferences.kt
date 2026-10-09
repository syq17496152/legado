package io.legado.app.model.localBook.epubcore.template

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.help.config.AtomicTextFileStore
import java.io.File

/**
 * 模板用户偏好（epub-md-rich-rendering 阶段 4.6）。
 *
 * 存储：`<root>/templatePreferences.json`（**用户偏好**，与作者数据 `readerTemplates.json` 分离）。
 * 当前只有一项：每模板的**翻页方式记忆**。
 *
 * 契约：
 * - **原子写**（复用 `AtomicTextFileStore`）；
 * - **损坏不空库**：解析失败返回空偏好且**不覆盖**文件（与 4.3/4.5 同口径）；
 * - **非法值归一**：非法翻页值/滚动模板的记忆值一律归 [ReaderTemplatePageTurnPolicy.Mode.FOLLOW_READING]；
 * - 删除模板时同步清理其记忆（[forget]），避免长期残留。
 */
internal class ReaderTemplatePreferences(
    private val root: File
) {

    /** 读取某模板的记忆翻页方式；无记忆或文件损坏返回 null。 */
    fun pageTurnMode(templateId: String): ReaderTemplatePageTurnPolicy.Mode? {
        val entry = readAll()[templateId] ?: return null
        val mode = ReaderTemplatePageTurnPolicy.Mode.fromValue(entry)
        return if (mode == ReaderTemplatePageTurnPolicy.Mode.FOLLOW_READING) null else mode
    }

    /**
     * 写入记忆。
     *
     * @param templateType 模板类型（滚动模板不记忆 ⇒ 直接清除该项）。
     */
    fun rememberPageTurn(templateId: String, templateType: String, mode: ReaderTemplatePageTurnPolicy.Mode): Boolean {
        if (templateId.isBlank()) return false
        val normalized = ReaderTemplatePageTurnPolicy.normalizeForStore(templateType, mode.value)
        return if (normalized == ReaderTemplatePageTurnPolicy.Mode.FOLLOW_READING) {
            forget(templateId)
        } else {
            val all = readAll().toMutableMap()
            all[templateId] = normalized.value
            writeAll(all)
        }
    }

    fun forget(templateId: String): Boolean {
        val all = readAll()
        if (!all.containsKey(templateId)) return false
        return writeAll(all - templateId)
    }

    fun clear(): Int {
        val count = readAll().size
        writeAll(emptyMap())
        return count
    }

    fun size(): Int = readAll().size

    // === 内部 ===

    private fun file(): File = File(root, FileName)

    private fun readAll(): Map<String, Int> {
        val target = file()
        if (!target.isFile) return emptyMap()
        val text = runCatching { target.readText() }.getOrNull() ?: return emptyMap()
        if (text.isBlank()) return emptyMap()
        return runCatching {
            val rootElement = JsonParser.parseString(text)
            val objectElement = when {
                rootElement.isJsonObject -> rootElement.asJsonObject.getAsJsonObject(KeyPageTurn)
                rootElement.isJsonArray -> null
                else -> null
            } ?: return emptyMap()
            buildMap {
                objectElement.keySet().forEach { id ->
                    val value = objectElement.get(id)
                    if (value != null && value.isJsonPrimitive) {
                        runCatching { value.asInt }.getOrNull()?.let { put(id, it) }
                    }
                }
            }
        }.getOrElse { emptyMap() }
    }

    private fun writeAll(entries: Map<String, Int>): Boolean {
        val pageTurn = JsonObject().apply {
            entries.forEach { (id, value) -> addProperty(id, value) }
        }
        val payload = JsonObject().apply {
            addProperty("schemaVersion", SchemaVersion)
            add(KeyPageTurn, pageTurn)
        }
        val text = payload.toString()
        return runCatching {
            AtomicTextFileStore(file()).writeVerified(text) { it == text }
            true
        }.getOrDefault(false)
    }

    private companion object {
        const val FileName = "templatePreferences.json"
        const val KeyPageTurn = "pageTurn"
        const val SchemaVersion = 1
    }
}