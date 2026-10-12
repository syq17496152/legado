package io.legado.app.model.localBook.epubcore.template

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

/**
 * archive 模板导入兼容层（epub-md-rich-rendering 阶段 4.17；AD-32 / TPL-16）。
 *
 * 依据 `docs/specs/epub-md-rich-rendering/archive-theme-compat-test.md` 的**导入映射表**逐条实现：
 *
 * | 输入 | 处理 |
 * |------|------|
 * | `schemaVersion:1` | 升级到 2（记入报告 `schema-upgraded`） |
 * | `type` 缺省 | 默认 `paged`（记入 `type-defaulted`） |
 * | `id = builtin.*` | 改名为 `user.<uuid>`，原值在报告中作 `originId` 展示（**避免与内置 id 冲突**） |
 * | `scrollHtml=""`（paged） | **忽略不判错**（记入 INFO） |
 * | `css` 内 `data:` URI | 照收，但按 AD-23 做**尺寸/数量**校验（超限降级并记入报告） |
 * | `javascript` | 照收；检测 archive 私有桥字段并记入报告（由桥策略覆盖） |
 * | 未知字段 / 私有扩展 | **忽略并降级**（逐个记入报告） |
 * | 巨大 `css`（>320KB） | 记入性能提示（编辑器需懒加载/分块，C8） |
 *
 * **同时支持两种包形态**（C5）：①单文件 `readerTemplate.json`（素材内联）；②ZIP 包（含 `package.json`）。
 * 本类处理**文本层**（JSON 解析 + 映射 + 报告）；ZIP 解包由调用方负责。
 *
 * 纯函数 + 纯 JVM（Gson）⇒ 全部分支可单测。
 */
internal object ReaderTemplateImportMapper {

    /** 我们支持的模板 schema 版本。 */
    const val TargetSchemaVersion = 2

    /** 巨大 css 的性能提示阈值（C8：320KB 量级）。 */
    const val LargeCssWarnChars = 320 * 1024

    /** 单个内联 `data:` 素材上限（与 [ReaderAssetPathPolicy.MaxAssetBytes] 同口径）。 */
    const val MaxInlineDataBytes = ReaderAssetPathPolicy.MaxAssetBytes

    /** 内联 `data:` 素材数量上限。 */
    const val MaxInlineDataCount = 64

    enum class Level { INFO, WARNING, ERROR }

    /** 报告条目：可展示、可统计、可审计。 */
    data class ReportEntry(
        val level: Level,
        val code: String,
        val message: String
    )

    data class ImportResult(
        val template: EpubReaderTemplate?,
        val report: List<ReportEntry>
    ) {
        val succeeded: Boolean get() = template != null
        val errors: List<ReportEntry> get() = report.filter { it.level == Level.ERROR }
        val warnings: List<ReportEntry> get() = report.filter { it.level == Level.WARNING }

        /** 展示用摘要（导入页直接呈现）。 */
        fun summary(): String = report.joinToString("\n") { "[${it.level}] ${it.message}" }
    }

    /** 已知字段集合（用于"未知字段忽略"检测）。 */
    private val knownFields = setOf(
        "schemaVersion", "type", "id", "name", "description",
        "firstPageHtml", "otherPageHtml", "scrollHtml", "css", "javascript"
    )

    /** archive 私有桥字段（C9：需确认被桥策略覆盖，否则模板 JS 会静默失效）。 */
    private val legacyBridgeFields = listOf(
        "legadoBridge", "androidBridge", "readerBridge",
        "pageIndex", "pageCount", "themeId", "srcHash"
    )

    /**
     * 导入单模板 JSON（形态①）。
     *
     * @param source `readerTemplate.json` 文本。
     * @param remapBuiltinIds 是否把 `builtin.*`（或缺失）id 改名为 `user.<uuid>`。
     *   用户导入必须为 `true`（C7：避免与内置 id 冲突）；**内置目录加载必须为 `false`**
     *   —— 内置模板 id 本就必须与 `assets/reader/<id>/` 目录名一致，改名会让目录校验必然失败。
     */
    fun importTemplateJson(source: String, remapBuiltinIds: Boolean = true): ImportResult {
        val report = ArrayList<ReportEntry>()
        val root = runCatching { JsonParser.parseString(source) }
            .getOrElse { error ->
                return ImportResult(
                    template = null,
                    report = listOf(ReportEntry(Level.ERROR, "invalid-json", "模板 JSON 解析失败：${error.localizedMessage}"))
                )
            }
        if (root == null || !root.isJsonObject) {
            return ImportResult(
                template = null,
                report = listOf(ReportEntry(Level.ERROR, "not-object", "模板 JSON 必须是一个对象"))
            )
        }
        val json = root.asJsonObject

        val rawVersion = json.get("schemaVersion")?.takeIf { it.isJsonPrimitive }?.asInt
        if (rawVersion == null) {
            report += ReportEntry(Level.ERROR, "missing-schema-version", "缺少 schemaVersion")
            return ImportResult(null, report)
        }
        if (rawVersion > TargetSchemaVersion) {
            report += ReportEntry(
                Level.ERROR,
                "unsupported-schema-version",
                "模板版本 $rawVersion 高于当前支持版本 $TargetSchemaVersion，请升级应用"
            )
            return ImportResult(null, report)
        }
        if (rawVersion < TargetSchemaVersion) {
            report += ReportEntry(
                Level.INFO,
                "schema-upgraded",
                "模板版本 $rawVersion → $TargetSchemaVersion（已自动升级）"
            )
        }

        val missingRequired = requiredFieldErrors(json)
        if (missingRequired != null) {
            report += missingRequired
            return ImportResult(null, report)
        }

        val originId = json.get("id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val newId = remapId(originId, report, remapBuiltinIds)

        val rawType = json.get("type")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val type = if (rawType.isBlank()) {
            report += ReportEntry(Level.INFO, "type-defaulted", "模板未声明 type，已按 paged 处理")
            EpubReaderTemplate.TYPE_PAGED
        } else {
            rawType
        }

        val scrollHtml = stringOf(json, "scrollHtml")
        if (type == EpubReaderTemplate.TYPE_PAGED && scrollHtml.isEmpty()) {
            report += ReportEntry(Level.INFO, "scroll-html-ignored", "paged 模板的 scrollHtml 为空，已忽略（不影响使用）")
        }

        val css = stringOf(json, "css")
        auditInlineData(css, report)
        if (css.length > LargeCssWarnChars) {
            report += ReportEntry(
                Level.WARNING,
                "large-css",
                "模板 css 约 ${css.length / 1024}KB，编辑器将分块加载（首屏可能略慢）"
            )
        }

        val javascript = stringOf(json, "javascript")
        auditLegacyBridge(javascript, report)

        reportUnknownFields(json, report)

        val template = runCatching {
            EpubReaderTemplate(
                schemaVersion = TargetSchemaVersion,
                id = newId,
                name = stringOf(json, "name"),
                description = stringOf(json, "description"),
                firstPageHtml = stringOf(json, "firstPageHtml"),
                otherPageHtml = stringOf(json, "otherPageHtml"),
                css = css,
                javascript = javascript,
                type = type,
                scrollHtml = scrollHtml
            )
        }.getOrElse { error ->
            // 映射后仍不合法（如 paged 缺 firstPageHtml）⇒ 按结构校验错误返回
            report += ReportEntry(Level.ERROR, "structure-invalid", "模板结构不合法：${error.localizedMessage}")
            return ImportResult(null, report)
        }

        val structureErrors = template.validate()
        if (structureErrors.isNotEmpty()) {
            report += structureErrors.map { ReportEntry(Level.ERROR, "structure-invalid", it) }
            return ImportResult(null, report)
        }

        return ImportResult(template, report)
    }

    /** 版本 v1 的模板在库内被读到时也按同一映射升级（schemaVersion 演进，4.5）。 */
    fun upgradeIfNeeded(template: EpubReaderTemplate): EpubReaderTemplate {
        if (template.schemaVersion >= TargetSchemaVersion) return template
        return template.copy(schemaVersion = TargetSchemaVersion)
    }

    /**
     * 导入**任意来源**的模板文件（C5 的完整落地）：①单文件 JSON；②ZIP 包（archive 导出包 / 我方模板包）。
     *
     * ⚠️ 真机缺口（2026-10-12 用户实测「导入主题竟然只支持 json？？不应该是 archive 导出的 zip 么」）：
     * 导入入口此前把所选文件**当 UTF-8 文本**读，ZIP 二进制必然读成乱码 ⇒ 实际只能导 JSON。
     * archive 导出的正是 ZIP（包内单文件 `readerTemplate.json`，素材内联，无 `package.json`，
     * 见 `archive-theme-compat-test.md` §一）⇒ 必须在**内容层**判别形态，不要求用户自己解压。
     *
     * 判别口径：按 **ZIP 魔数 `PK`**（不看扩展名/MIME —— 部分文件提供方给 `application/octet-stream`）。
     */
    fun importAny(bytes: ByteArray, remapBuiltinIds: Boolean = true): ImportResult {
        if (bytes.isEmpty()) {
            return ImportResult(null, listOf(ReportEntry(Level.ERROR, "empty-file", "文件为空，无法导入")))
        }
        if (!isZip(bytes)) {
            return importTemplateJson(decodeText(bytes), remapBuiltinIds)
        }
        val entries = ReaderTemplatePackage.readEntries(bytes).getOrElse { error ->
            return ImportResult(
                null,
                listOf(ReportEntry(Level.ERROR, "bad-zip", "模板包解压失败：${error.localizedMessage}"))
            )
        }
        val templateText = pickTemplateEntryText(entries)
            ?: return ImportResult(
                null,
                listOf(
                    ReportEntry(
                        Level.ERROR,
                        "no-template-in-zip",
                        "模板包内未找到 ${ReaderTemplatePackage.TemplateEntryName}（不是模板包？）"
                    )
                )
            )
        return importTemplateJson(templateText, remapBuiltinIds).withNote(externalAssetNote(entries))
    }

    // === 内部 ===

    /** ZIP 魔数：`PK\x03\x04` / 空包 `PK\x05\x06` / 跨卷 `PK\x07\x08` 皆以 `PK` 起头。 */
    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    /** 文本解码（去 UTF-8 BOM；非法字节用替换字符而非抛错 —— 由随后的 JSON 解析给明确报错）。 */
    private fun decodeText(bytes: ByteArray): String =
        String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")

    /**
     * 从包内取模板 JSON 文本。
     *
     * 顺序：①包根 `readerTemplate.json`（archive 导出包与我方包的默认路径）；②清单 `template`
     * 声明的路径（我方包可自定义路径，清单本身不可信 ⇒ 路径仍由 [ReaderTemplatePackage] 归一化校验后查表）。
     */
    private fun pickTemplateEntryText(entries: Map<String, ByteArray>): String? {
        entries[ReaderTemplatePackage.TemplateEntryName]?.let { return it.toString(Charsets.UTF_8) }
        val manifestText = entries[ReaderTemplatePackage.ManifestName]?.toString(Charsets.UTF_8) ?: return null
        val path = ReaderTemplatePackage.parseManifest(manifestText).getOrNull()?.templatePath ?: return null
        return entries[path]?.toString(Charsets.UTF_8)
    }

    /**
     * 外部素材提示：包清单声明了素材（非内联）时**明说**"本次只导入了模板，素材未入库"。
     *
     * 为什么必须报：模板 CSS/HTML 可能引用 `asset://别名` ⇒ 静默丢素材会让用户看到"模板导入成功但样式缺失"，
     * 无法归因（本项目铁证⑦③：可感知的边界处理必须留痕）。archive 导出包素材全内联 ⇒ 一般不触发。
     */
    private fun externalAssetNote(entries: Map<String, ByteArray>): ReportEntry? {
        val manifestText = entries[ReaderTemplatePackage.ManifestName]?.toString(Charsets.UTF_8) ?: return null
        val count = ReaderTemplatePackage.parseManifest(manifestText).getOrNull()?.resources?.size ?: 0
        if (count <= 0) return null
        return ReportEntry(
            Level.WARNING,
            "external-assets-skipped",
            "模板包声明了 $count 项外部素材，本次仅导入模板本体（若正文样式缺失，请改用素材内联的模板）"
        )
    }

    private fun ImportResult.withNote(note: ReportEntry?): ImportResult =
        if (note == null) this else copy(report = report + note)

    private fun requiredFieldErrors(json: JsonObject): ReportEntry? {
        val type = json.get("type")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val isScroll = type == EpubReaderTemplate.TYPE_SCROLL
        if (isScroll) {
            if (stringOf(json, "scrollHtml").isBlank()) {
                return ReportEntry(Level.ERROR, "missing-required-html", "滚动模板缺少 scrollHtml")
            }
        } else {
            if (stringOf(json, "firstPageHtml").isBlank()) {
                return ReportEntry(Level.ERROR, "missing-required-html", "模板缺少 firstPageHtml")
            }
            if (stringOf(json, "otherPageHtml").isBlank()) {
                return ReportEntry(Level.ERROR, "missing-required-html", "模板缺少 otherPageHtml")
            }
        }
        return null
    }

    /** C7：内置 id 改名，避免与"恢复内置"冲突；内置目录加载时 `remapBuiltinIds=false` 原样保留。 */
    private fun remapId(originId: String, report: MutableList<ReportEntry>, remapBuiltinIds: Boolean): String {
        if (!remapBuiltinIds) return originId
        if (originId.isNotBlank() && !originId.startsWith("builtin.")) return originId
        val newId = "user.${UUID.randomUUID()}"
        report += ReportEntry(
            Level.INFO,
            "id-renamed",
            "模板 id 已改名以避免与内置冲突：originId=${originId.ifBlank { "(空)" }} → $newId"
        )
        return newId
    }

    /** C3：内联 `data:` 素材的数量与尺寸校验。 */
    private fun auditInlineData(css: String, report: MutableList<ReportEntry>) {
        val matches = DATA_URI.findAll(css).toList()
        if (matches.isEmpty()) return
        report += ReportEntry(Level.INFO, "inline-data-count", "css 含 ${matches.size} 个内联 data: 素材（离线可用）")
        if (matches.size > MaxInlineDataCount) {
            report += ReportEntry(
                Level.WARNING,
                "too-many-inline-data",
                "内联素材数量超限：${matches.size} > $MaxInlineDataCount，超限部分可能不显示"
            )
        }
        matches.forEachIndexed { index, match ->
            val payloadChars = match.value.length
            // base64 膨胀约 4/3，换算回字节做上限判定
            val approxBytes = payloadChars / 4 * 3
            if (approxBytes > MaxInlineDataBytes) {
                report += ReportEntry(
                    Level.WARNING,
                    "oversized-inline-data",
                    "第 ${index + 1} 个内联素材约 $approxBytes 字节，超过单条上限 $MaxInlineDataBytes"
                )
            }
        }
    }

    /** C9：archive 私有桥字段检测（提示作者其 JS 需按新桥字段改写）。 */
    private fun auditLegacyBridge(javascript: String, report: MutableList<ReportEntry>) {
        if (javascript.isBlank()) return
        val hits = legacyBridgeFields.filter { javascript.contains(it) }
        if (hits.isNotEmpty()) {
            report += ReportEntry(
                Level.INFO,
                "legacy-bridge-fields",
                "模板 JS 引用了桥字段 ${hits.joinToString("/")}；已由桥白名单覆盖"
            )
        }
    }

    private fun reportUnknownFields(json: JsonObject, report: MutableList<ReportEntry>) {
        val unknown = json.keySet().filterNot { it in knownFields }
        if (unknown.isNotEmpty()) {
            report += ReportEntry(
                Level.INFO,
                "unknown-fields-ignored",
                "已忽略未知字段：${unknown.sorted().joinToString(", ")}"
            )
        }
    }

    private fun stringOf(json: JsonObject, name: String): String {
        val element = json.get(name) ?: return ""
        if (!element.isJsonPrimitive) return ""
        return runCatching { element.asString }.getOrDefault("")
    }

    private val DATA_URI = Regex("""data:([a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=]*)""")
}