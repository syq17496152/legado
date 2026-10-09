package io.legado.app.model.localBook.epubcore.template

import com.google.gson.JsonParser

/**
 * 内置模板目录（epub-md-rich-rendering 阶段 4.12；AD-21 / TPL-04）。
 *
 * 内置模板随包分发在 `assets/reader/<id>/readerTemplate.json`，目录清单 `assets/reader/templates.json`
 * 显式列出 id（**不用目录扫描**：显式清单可被测试逐项校验，也避免打包工具漏带文件时静默少一套）。
 *
 * 合规红线（AD-21 / 合规盘点）：
 * - **不内置第三方 IP 素材**；本批全部为**纯 CSS / 自研 SVG**，零外部图片与外部字体 ⇒ 天然合规；
 * - 每套附 `sources.json`（作者自研声明 + 许可），由 [ReaderBuiltinTemplateCatalog.load] 一并读入供审计展示；
 * - 加载期即校验：能力合规 + 结构合法（走 [ReaderTemplateImportMapper]，与用户导入同一条路径，
 *   避免"内置走特判"造成的口径分裂）。
 *
 * 纯 JVM（读取由 [AssetReader] 注入）⇒ 可在单测中直接读 `src/main/assets` 校验真实资产。
 */
internal object ReaderBuiltinTemplateCatalog {

    const val CatalogAsset = "reader/templates.json"
    const val SourceManifestName = "sources.json"

    /** 资产读取器：路径（相对 `assets/`）→ 文本；不存在返回 null。 */
    fun interface AssetReader {
        fun read(path: String): String?
    }

    data class BuiltinTemplate(
        val id: String,
        val template: EpubReaderTemplate,
        /** 素材来源与许可声明（`sources.json` 原文；缺失则空串）。 */
        val sources: String,
        val report: List<ReaderTemplateImportMapper.ReportEntry>
    ) {
        val isScroll: Boolean get() = template.isScrolling
    }

    data class LoadResult(
        val templates: List<BuiltinTemplate>,
        val errors: List<String>
    )

    /** 模板资产相对路径。 */
    fun templatePath(id: String): String = "reader/$id/readerTemplate.json"

    fun sourcesPath(id: String): String = "reader/$id/$SourceManifestName"

    /** 读取目录清单中的 id 列表。 */
    fun catalogIds(reader: AssetReader): Result<List<String>> {
        val text = reader.read(CatalogAsset)
            ?: return Result.failure(IllegalArgumentException("缺少内置模板目录：$CatalogAsset"))
        return runCatching {
            val root = JsonParser.parseString(text)
            require(root.isJsonObject) { "内置模板目录根节点必须是对象" }
            val array = root.asJsonObject.getAsJsonArray("templates")
                ?: return@runCatching emptyList()
            array.mapNotNull { element ->
                element.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            }
        }
    }

    /**
     * 加载全部内置模板。
     *
     * 逐项失败**不中断整体**（其余模板仍可用），错误汇总在 [LoadResult.errors] 供审计。
     */
    fun load(reader: AssetReader): LoadResult {
        val ids = catalogIds(reader).getOrElse { error ->
            return LoadResult(emptyList(), listOf(error.localizedMessage.orEmpty()))
        }
        val templates = ArrayList<BuiltinTemplate>(ids.size)
        val errors = ArrayList<String>()
        val seen = HashSet<String>()
        ids.forEach { id ->
            if (!seen.add(id)) {
                errors += "内置模板 id 重复：$id"
                return@forEach
            }
            val text = reader.read(templatePath(id))
            if (text == null) {
                errors += "缺少内置模板文件：${templatePath(id)}"
                return@forEach
            }
            // 与用户导入走同一条映射路径，但**禁止内置 id 改名**（改名为 user.* 会让目录校验必然失败）
            val imported = ReaderTemplateImportMapper.importTemplateJson(text, remapBuiltinIds = false)
            val template = imported.template
            if (template == null) {
                errors += "内置模板 $id 不合法：${imported.summary()}"
                return@forEach
            }
            // 内置模板 id 必须与目录声明一致（防止打包/改名错配）
            if (template.id != id) {
                errors += "内置模板 id 与目录不一致：目录 $id / 文件 ${template.id}"
                return@forEach
            }
            templates += BuiltinTemplate(
                id = id,
                template = template,
                sources = reader.read(sourcesPath(id)).orEmpty(),
                report = imported.report
            )
        }
        return LoadResult(templates, errors)
    }
}