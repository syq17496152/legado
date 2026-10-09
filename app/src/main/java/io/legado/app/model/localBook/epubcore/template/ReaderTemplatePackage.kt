package io.legado.app.model.localBook.epubcore.template

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 模板包编解码（epub-md-rich-rendering 阶段 4.13 / 4.7；TPL-05/07/12）。
 *
 * 包结构（沿用我方 `appearance_kits` 套件打包范式）：
 * ```
 * readerTemplate.json        # 单模板（含 schemaVersion/type/...）
 * package.json               # formatVersion + template 路径 + resources[{alias,path,type}]
 * assets/<file>              # 引用素材（也可继续用 data: 内联）
 * ```
 *
 * 设计要点：
 * - **纯 JVM**（`java.util.zip` + Gson）⇒ 导出/导入闭环可在单测中完整验证（真的打包再真的解包）；
 * - **确定性输出**：ZIP 条目时间统一归零 ⇒ 同输入产同字节（便于比对与去重，也让测试可断言）；
 * - **解包即校验**：`package.json` 的每条资源声明走 [ReaderAssetPathPolicy]（含 `assets/` 根、扩展名一致），
 *   并做条目路径的**越界防御**（`../`、绝对路径、反斜杠）；超限（单文件/总量）直接拒绝；
 * - **不信任包内清单**：`template` 字段必须相对包根且归一化后仍为 `readerTemplate.json`（防清单指向包外/其他目录）。
 */
internal object ReaderTemplatePackage {

    /** 包格式版本（与单模板 schema、库 schema 三者独立）。 */
    const val FormatVersion = 2

    const val TemplateEntryName = "readerTemplate.json"
    const val ManifestName = "package.json"

    data class Resource(
        val alias: String,
        val path: String,
        val type: String
    )

    data class Manifest(
        val formatVersion: Int,
        val templatePath: String,
        val resources: List<Resource>
    )

    /** 解包结果：模板 + 素材字节（按包内路径索引）。 */
    data class Package(
        val template: EpubReaderTemplate,
        val manifest: Manifest,
        val assets: Map<String, ByteArray>,
        val report: List<ReaderTemplateImportMapper.ReportEntry>
    )

    // === 导出 ===

    /** 生成 `package.json` 文本。 */
    fun buildManifestJson(resources: List<Resource>, templatePath: String = TemplateEntryName): String {
        val payload = JsonObject().apply {
            addProperty("formatVersion", FormatVersion)
            addProperty("template", templatePath)
            add(
                "resources",
                JsonArray().apply {
                    resources.forEach { resource ->
                        add(JsonObject().apply {
                            addProperty("alias", resource.alias)
                            addProperty("path", resource.path)
                            addProperty("type", resource.type)
                        })
                    }
                }
            )
        }
        return payload.toString()
    }

    /**
     * 打包为 ZIP 字节。
     *
     * @param template 单模板（结构必须合法）。
     * @param resources 素材声明（每条必须通过 [ReaderAssetPathPolicy.validateDeclaration]）。
     * @param assets 素材字节：包内路径 → 内容（缺项视为声明了但未随包，属于**包不完整**，直接拒绝）。
     */
    fun zip(
        template: EpubReaderTemplate,
        resources: List<Resource> = emptyList(),
        assets: Map<String, ByteArray> = emptyMap()
    ): Result<ByteArray> {
        val errors = template.validate()
        if (errors.isNotEmpty()) {
            return Result.failure(IllegalArgumentException("模板结构不合法：${errors.joinToString("；")}"))
        }
        resources.forEach { resource ->
            ReaderAssetPathPolicy.validateDeclaration(
                ReaderAssetPathPolicy.ResourceDeclaration(resource.alias, resource.path, resource.type)
            )?.let { rejection ->
                return Result.failure(IllegalArgumentException("素材声明不合法（${rejection.code}）：${rejection.message}"))
            }
        }
        val missing = resources.map { it.path }.filterNot { assets.containsKey(it) }
        if (missing.isNotEmpty()) {
            return Result.failure(IllegalArgumentException("包不完整，缺少素材：${missing.joinToString(", ")}"))
        }
        val sizeRejection = ReaderAssetPathPolicy.validatePackageSize(
            resources.map { ReaderAssetPathPolicy.ResourceDeclaration(it.alias, it.path, it.type) }
        ) { declaration -> assets[declaration.path]?.size?.toLong() ?: 0L }
        if (sizeRejection != null) {
            return Result.failure(IllegalArgumentException("素材超限（${sizeRejection.code}）：${sizeRejection.message}"))
        }
        val entries = LinkedHashMap<String, ByteArray>()
        entries[TemplateEntryName] = template.toJson().toByteArray(Charsets.UTF_8)
        entries[ManifestName] = buildManifestJson(resources).toByteArray(Charsets.UTF_8)
        resources.forEach { resource -> entries[resource.path] = assets.getValue(resource.path) }
        return Result.success(writeZip(entries))
    }

    // === 导入 ===

    /** 解析 `package.json`（含路径与资源白名单校验）。**任何异常都转成 failure，绝不抛出**。 */
    fun parseManifest(source: String): Result<Manifest> {
        val rootElement = runCatching { JsonParser.parseString(source) }.getOrElse { error ->
            return Result.failure(IllegalArgumentException("package.json 解析失败：${error.localizedMessage}", error))
        }
        if (rootElement == null || !rootElement.isJsonObject) {
            return Result.failure(IllegalArgumentException("package.json 根节点必须是对象"))
        }
        val root = rootElement.asJsonObject
        val formatVersion = root.get("formatVersion")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        if (formatVersion > FormatVersion) {
            return Result.failure(IllegalArgumentException(
                "模板包格式版本 $formatVersion 高于当前支持版本 $FormatVersion，请先升级应用（未做任何改动）"
            ))
        }
        val templatePath = root.get("template")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        // 不信任包内清单：模板路径必须归一化后就是包根下的 readerTemplate.json
        val normalizedTemplate = normalizeEntryName(templatePath)
            ?: return Result.failure(IllegalArgumentException("模板路径非法：$templatePath"))
        if (normalizedTemplate != TemplateEntryName) {
            return Result.failure(IllegalArgumentException("模板路径必须是包根下的 $TemplateEntryName：$templatePath"))
        }
        // 资源列表解析：别名重复/声明越权都属**拒收**而非崩溃 ⇒ 统一转 Result.failure
        val resources = runCatching {
            val parsed = ArrayList<Resource>()
            val seenAliases = HashSet<String>()
            root.getAsJsonArray("resources")?.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val item = element.asJsonObject
                val alias = item.get("alias")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val path = item.get("path")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val type = item.get("type")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                require(seenAliases.add(alias)) { "素材别名重复：$alias" }
                ReaderAssetPathPolicy.validateDeclaration(
                    ReaderAssetPathPolicy.ResourceDeclaration(alias, path, type)
                )?.let { rejection ->
                    error("素材声明不合法（${rejection.code}）：${rejection.message}")
                }
                parsed += Resource(alias, path, type)
            }
            parsed
        }.getOrElse { error ->
            return Result.failure(IllegalArgumentException(error.localizedMessage.orEmpty(), error))
        }
        return Result.success(Manifest(formatVersion, normalizedTemplate, resources))
    }

    /**
     * 解包 ZIP 字节。
     *
     * 顺序：先读清单并校验声明 ⇒ 再读模板（走 [ReaderTemplateImportMapper] 兼容映射）⇒ 最后收素材。
     * 任何一步失败都返回 failure（**不留半成品**：调用方据此不做任何入库动作）。
     */
    fun unzip(bytes: ByteArray): Result<Package> {
        val entries = runCatching { readZip(bytes) }.getOrElse { error ->
            return Result.failure(IllegalArgumentException("模板包解压失败：${error.localizedMessage}", error))
        }
        val manifestText = entries[ManifestName]?.toString(Charsets.UTF_8)
            ?: return Result.failure(IllegalArgumentException("模板包缺少 $ManifestName"))
        val templateText = entries[TemplateEntryName]?.toString(Charsets.UTF_8)
            ?: return Result.failure(IllegalArgumentException("模板包缺少 $TemplateEntryName"))
        val manifest = parseManifest(manifestText).getOrElse { return Result.failure(it) }

        val importResult = ReaderTemplateImportMapper.importTemplateJson(templateText)
        val template = importResult.template
            ?: return Result.failure(IllegalArgumentException("模板不合法：${importResult.summary()}"))

        val assets = LinkedHashMap<String, ByteArray>()
        manifest.resources.forEach { resource ->
            val content = entries[resource.path]
                ?: return Result.failure(IllegalArgumentException("模板包缺少声明素材：${resource.path}"))
            if (!ReaderAssetPathPolicy.matchesFileHeader(resource.path, content)) {
                return Result.failure(IllegalArgumentException("素材内容与扩展名不符：${resource.path}"))
            }
            assets[resource.path] = content
        }
        val sizeRejection = ReaderAssetPathPolicy.validatePackageSize(
            manifest.resources.map { ReaderAssetPathPolicy.ResourceDeclaration(it.alias, it.path, it.type) }
        ) { declaration -> assets[declaration.path]?.size?.toLong() ?: 0L }
        if (sizeRejection != null) {
            return Result.failure(IllegalArgumentException("素材超限（${sizeRejection.code}）：${sizeRejection.message}"))
        }
        return Result.success(Package(template, manifest, assets, importResult.report))
    }

    // === 内部 ===

    /** 归一化包内条目名；非法（绝对路径/点段/反斜杠/编码）返回 null。 */
    private fun normalizeEntryName(raw: String): String? {
        val name = raw.replace('\\', '/').trim().removePrefix("./")
        if (name.isBlank()) return null
        if (name.startsWith('/') || name.contains('%') || name.contains(':')) return null
        val segments = name.split('/')
        if (segments.any { it == ".." || it == "." || it.isEmpty() }) return null
        return segments.joinToString("/")
    }

    /** 确定性 ZIP：条目时间归零、按插入顺序写入。 */
    private fun writeZip(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                val entry = ZipEntry(normalizeEntryName(name) ?: name).apply {
                    time = 0L
                }
                zip.putNextEntry(entry)
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var total = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = normalizeEntryName(entry.name)
                    ?: error("非法条目名：${entry.name}")
                val content = zip.readBytes()
                total += content.size
                if (content.size.toLong() > ReaderAssetPathPolicy.MaxAssetBytes) {
                    error("条目超限：$name")
                }
                if (total > ReaderAssetPathPolicy.MaxPackageBytes) {
                    error("包总量超限")
                }
                entries[name] = content
            }
        }
        return entries
    }
}