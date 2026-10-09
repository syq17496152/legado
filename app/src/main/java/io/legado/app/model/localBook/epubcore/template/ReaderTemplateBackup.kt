package io.legado.app.model.localBook.epubcore.template

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 模板库独立备份与恢复（epub-md-rich-rendering 阶段 4.10；TPL-07）。
 *
 * 「独立」的含义：模板库**不混进书籍备份**，可单独导出/导入（用户换机只搬模板）。
 * 备份内容 = **用户模板全集** + **每模板翻页方式偏好**（含类型，故恢复后"类型与素材保留"）。
 *
 * **跨版本恢复边界**（audit R9-13 §三 明确要求）：
 * - 备份版本 **>** 当前支持 ⇒ **整体拒绝**（绝不半导入：部分导入会留下"看起来成功实则缺字段"的坏库）；
 * - 备份版本 **<** 当前 ⇒ 允许恢复，条目经 [ReaderTemplateImportMapper.upgradeIfNeeded] 升级；
 * - 条目级损坏 ⇒ **跳过该条并记入报告**，其余照常恢复。
 *
 * 纯 JVM（Gson）⇒ 全部分支可单测。
 */
internal object ReaderTemplateBackup {

    /** 备份文件 schema 版本（与库文件、单模板 schema 三者独立演进）。 */
    const val BackupSchemaVersion = 1

    data class Bundle(
        val templates: List<EpubReaderTemplate>,
        val pageTurn: Map<String, Int>,
        /** 解析期被升级的条目数（供恢复报告复用；避免"升级信息在解析阶段就丢失"）。 */
        val upgraded: Int
    )

    data class RestoreResult(
        val restored: Int,
        val skipped: Int,
        val upgraded: Int,
        val rejected: Boolean,
        val message: String
    ) {
        val succeeded: Boolean get() = !rejected
    }

    /** 生成备份文本（含类型与偏好）。 */
    fun export(templates: List<EpubReaderTemplate>, pageTurn: Map<String, Int>): String {
        val payload = JsonObject().apply {
            addProperty("schemaVersion", BackupSchemaVersion)
            addProperty("kind", "readerTemplates")
            add("templates", JsonArray().apply { templates.forEach { add(it.toJsonObject()) } })
            add("pageTurn", JsonObject().apply {
                pageTurn.forEach { (id, value) -> addProperty(id, value) }
            })
        }
        return payload.toString()
    }

    /** 解析备份（不做写入）。 */
    fun parse(source: String): Result<Bundle> {
        val rootElement = runCatching { JsonParser.parseString(source) }
            .getOrElse { error ->
                return Result.failure(IllegalArgumentException("备份解析失败：${error.localizedMessage}", error))
            }
        if (rootElement == null || !rootElement.isJsonObject) {
            return Result.failure(IllegalArgumentException("备份根节点必须是对象"))
        }
        val root = rootElement.asJsonObject
        val version = root.get("schemaVersion")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        if (version > BackupSchemaVersion) {
            // 整体拒绝：高版本备份可能含本版本无法表达的结构，半导入会造成静默损坏
            return Result.failure(IllegalArgumentException(
                "备份版本 $version 高于当前支持版本 $BackupSchemaVersion，请先升级应用再恢复（未做任何改动）"
            ))
        }
        val templates = ArrayList<EpubReaderTemplate>()
        var upgraded = 0
        root.getAsJsonArray("templates")?.forEach { element ->
            if (!element.isJsonObject) return@forEach
            // 条目级损坏只跳过（不阻断整体恢复）
            runCatching { EpubReaderTemplate.fromJsonObject(element.asJsonObject) }
                .getOrNull()
                ?.let { parsed ->
                    if (parsed.schemaVersion < ReaderTemplateImportMapper.TargetSchemaVersion) upgraded++
                    templates += ReaderTemplateImportMapper.upgradeIfNeeded(parsed)
                }
        }
        val pageTurn = HashMap<String, Int>()
        root.getAsJsonObject("pageTurn")?.keySet()?.forEach { id ->
            val value = root.getAsJsonObject("pageTurn").get(id)
            if (value != null && value.isJsonPrimitive) {
                runCatching { value.asInt }.getOrNull()?.let { pageTurn[id] = it }
            }
        }
        return Result.success(Bundle(templates, pageTurn, upgraded))
    }

    /**
     * 恢复进模板库（覆盖同 id，新增其余）。
     *
     * @param repository 目标模板库。
     * @param prefs 目标偏好存储（CC0 提示：恢复后翻页方式随模板一起回来）。
     */
    fun restore(
        source: String,
        repository: ReaderTemplateRepository,
        prefs: ReaderTemplatePreferences
    ): RestoreResult {
        val parsed = parse(source).getOrElse { error ->
            return RestoreResult(
                restored = 0,
                skipped = 0,
                upgraded = 0,
                rejected = true,
                message = error.localizedMessage.orEmpty()
            )
        }
        val bundle = parsed
        val sourceCount = countTemplates(source)
        var restored = 0
        bundle.templates.forEach { template ->
            if (repository.save(template)) restored++
        }
        bundle.pageTurn.forEach { (id, value) ->
            val type = bundle.templates.firstOrNull { it.id == id }?.type ?: EpubReaderTemplate.TYPE_PAGED
            prefs.rememberPageTurn(id, type, ReaderTemplatePageTurnPolicy.Mode.fromValue(value))
        }
        return RestoreResult(
            restored = restored,
            skipped = (sourceCount - restored).coerceAtLeast(0),
            upgraded = bundle.upgraded,
            rejected = false,
            message = "已恢复 $restored 个模板" +
                if (bundle.upgraded > 0) "（其中 ${bundle.upgraded} 个已升级到当前版本）" else ""
        )
    }

    private fun countTemplates(source: String): Int {
        return runCatching {
            JsonParser.parseString(source).asJsonObject.getAsJsonArray("templates")?.size() ?: 0
        }.getOrDefault(0)
    }
}