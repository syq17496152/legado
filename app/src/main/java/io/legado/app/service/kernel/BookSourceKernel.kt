package io.legado.app.service.kernel

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SourceRecycleBin
import io.legado.app.help.source.BookSourceIncrementalParser
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SourceRecycleBinHelp
import io.legado.app.model.DimState
import io.legado.app.model.SourceQualityChecker
import io.legado.app.utils.GSON
import io.legado.app.utils.QRCodeUtils
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * 书源域业务内核（web-mcp-productization 一期 · 2.2 / REQ-1-201 · REQ-1-202）。
 *
 * 契约同 [BookKernel]：**只返回领域对象**、**全链挂起**、**零 `runBlocking`**、失败抛异常。
 *
 * 说明（决策 #2）：删源**无需在此另接回收站** —— [SourceHelp.deleteBookSources] 内部已走
 * `SourceHelp.deleteBookSourceInternal`（内含 `SourceRecycleBinHelp.recycleBookSources` + 开关 +
 * `runCatching`，见 `SourceHelp.kt:141-145`），故本层只需原样委派，由测试断言该委派不被摘除
 * （防未来有人"顺手改成直删"把回收站行为弄丢）。
 */
object BookSourceKernel {

    suspend fun sources(): List<BookSource> = withContext(IO) { appDb.bookSourceDao.all }

    suspend fun source(url: String): BookSource? =
        withContext(IO) { appDb.bookSourceDao.getBookSource(url) }

    suspend fun saveSource(source: BookSource) {
        withContext(IO) { appDb.bookSourceDao.insert(source) }
    }

    /**
     * 批量保存**有效**源，返回实际写入的源（与原 Controller 的 `okSources` 语义一致）。
     *
     * 无效源（名称/URL 为空）被跳过而非报错 —— 保持原行为：批量导入时"能进的都进"。
     */
    suspend fun saveSources(sources: List<BookSource>): List<BookSource> {
        val saved = ArrayList<BookSource>(sources.size)
        sources.forEach { source ->
            if (hasValidIdentity(source)) {
                saveSource(source)
                saved.add(source)
            }
        }
        return saved
    }

    /** 删源：委派 [SourceHelp]（其内部已接入回收站，REQ-1-307）。 */
    suspend fun deleteSources(sources: List<BookSource>) {
        withContext(IO) { SourceHelp.deleteBookSources(sources) }
    }

    /**
     * 源身份是否完整。
     *
     * 与改造前口径的**唯一差别**：原 `TextUtils.isEmpty` 只判 null/""，本实现用 `isNotBlank()`
     * 顺带拒掉**纯空白**名称/URL（"空白"实际也是无效源，故视为同义收紧，不影响合法源）。
     */
    internal fun hasValidIdentity(source: BookSource): Boolean =
        source.bookSourceName.isNotBlank() && source.bookSourceUrl.isNotBlank()

    // ============================================================ 导入校验链（2.2.2）

    /** 批量导入前的校验结果。 */
    data class ValidatedSources(
        val accepted: List<BookSource>,
        val skipped: List<SkippedSource>,
    )

    /** 被跳过的源（含白话原因，供日志/后续控制台明细使用）。 */
    data class SkippedSource(val name: String, val url: String, val reason: String)

    /**
     * 解析 + 校验（2.2.2 / REQ-1-306 的**可复用抽取**）。
     *
     * - **解析**复用真实导入入口 [BookSourceIncrementalParser.parseBookSourcesIncrementalInto]
     *   （自带体积/条数上限，防超大 payload 打爆内存）；
     * - **校验**复用 App 导入页同一件 [SourceQualityChecker.l1StaticCheckBookSource]（L1 **0 网络成本**）。
     *
     * 判据与 App 导入页**同口径**（`ImportBookSourceViewModel:329-334`：`deterministicFail` 的源默认不勾选），
     * 只是 Web 侧没有"手动勾选恢复"的 UI，故表现为"跳过 + 提示失败数"。
     *
     * ⚠️ 该口径依据两条实证：①App 导入页默认不选结构残缺源；②老 vue 页 `ToolBar.vue:112-129`
     * 以 `data`（成功数组）长度算失败数 ⇒ 跳过即显示为"失败 N 条"，**无需改前端**且不误报成功。
     *
     * @throws Exception 解析失败（非法 JSON / 非书源格式）—— 由门面转 "转换源失败"
     */
    suspend fun parseAndValidate(json: String): ValidatedSources {
        val parsed = ArrayList<BookSource>()
        BookSourceIncrementalParser.parseBookSourcesIncrementalInto(
            ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)),
            parsed,
        )
        val accepted = ArrayList<BookSource>(parsed.size)
        val skipped = ArrayList<SkippedSource>()
        parsed.forEach { source ->
            val reason = skipReason(source)
            if (reason == null) {
                accepted.add(source)
            } else {
                skipped.add(SkippedSource(source.bookSourceName, source.bookSourceUrl, reason))
            }
        }
        return ValidatedSources(accepted, skipped)
    }

    /** 单条保存前的校验口径（与批量完全一致）。 */
    fun skipReason(source: BookSource): String? {
        if (!hasValidIdentity(source)) return "源名称和URL不能为空"
        val report = SourceQualityChecker.l1StaticCheckBookSource(source)
        if (!report.deterministicFail) return null
        return report.dimensions.values.firstOrNull { it.state == DimState.FAIL }?.evidence
            ?: "规则残缺"
    }

    // ============================================================ 分组（二期 tasks 2.11）

    /** 全部书源分组名（`BookSourceDao.allGroups` 已做逗号拆分 + 去重）。 */
    suspend fun groups(): List<String> = withContext(IO) { appDb.bookSourceDao.allGroups() }

    /** 某分组下的书源（DAO 用 `like` 匹配，与 App 内分组页同口径）。 */
    suspend fun sourcesByGroup(group: String): List<BookSource> =
        withContext(IO) { appDb.bookSourceDao.getByGroup(group) }

    /**
     * 分组重命名（`newName` 为空 ⇒ 视为删除该分组标记）。
     *
     * 书源分组的存储形态是**每条源上的逗号串**（`BookSource.bookSourceGroup`），没有独立分组表，
     * 故重命名 = 遍历受影响源、逐条改串后回写（与 App 内分组管理同一份数据模型）。
     */
    suspend fun renameGroup(oldName: String, newName: String?): Int = withContext(IO) {
        val targets = appDb.bookSourceDao.getByGroup(oldName)
        var affected = 0
        targets.forEach { source ->
            val parts = source.bookSourceGroup.orEmpty()
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val renamed = parts.map { if (it == oldName) newName.orEmpty() else it }
                .filter { it.isNotEmpty() }
                .distinct()
            val joined = renamed.joinToString(",")
            if (joined != source.bookSourceGroup.orEmpty()) {
                appDb.bookSourceDao.upGroup(source.bookSourceUrl, joined)
                affected++
            }
        }
        affected
    }

    /** 批量启用 / 停用书源。 */
    suspend fun setEnabled(urls: List<String>, enabled: Boolean): Int = withContext(IO) {
        urls.count { url ->
            val exist = appDb.bookSourceDao.getBookSource(url) ?: return@count false
            appDb.bookSourceDao.enable(exist.bookSourceUrl, enabled)
            true
        }
    }

    // ============================================================ 回收站（二期 tasks 2.11）

    /** 回收站条目（`type` 为空 ⇒ 全部，否则按 book/rss 过滤）。 */
    suspend fun recycleBin(type: String?): List<SourceRecycleBin> = withContext(IO) {
        if (type.isNullOrBlank()) {
            appDb.sourceRecycleBinDao.flowAll().first()
        } else {
            appDb.sourceRecycleBinDao.flowByType(type).first()
        }
    }

    /** 从回收站恢复单条（`overwrite=false` 且同名冲突时返回 `conflict=true`，不落库）。 */
    suspend fun restoreFromRecycle(id: Long, overwrite: Boolean): Map<String, Any?> = withContext(IO) {
        val item = appDb.sourceRecycleBinDao.getById(id)
            ?: throw NoSuchElementException("回收站条目不存在：$id")
        if (!overwrite && SourceRecycleBinHelp.hasConflict(item)) {
            return@withContext mapOf("restored" to false, "conflict" to true, "name" to item.name)
        }
        SourceRecycleBinHelp.restore(item, overwrite)
        appDb.sourceRecycleBinDao.deleteById(id)
        mapOf("restored" to true, "conflict" to false, "name" to item.name, "type" to item.type)
    }

    /** 清空回收站（含已过期条目），返回删除条数。 */
    suspend fun purgeRecycle(): Int = withContext(IO) {
        val all = appDb.sourceRecycleBinDao.flowAll().first()
        if (all.isEmpty()) 0 else {
            appDb.sourceRecycleBinDao.delete(*all.toTypedArray())
            all.size
        }
    }

    // ============================================================ 源变量（二期 tasks 2.11）

    /** 读取源变量（`BaseSource.getVariable`，落 `CacheManager` 的 `sourceVariable_{key}`）。 */
    suspend fun variables(url: String): Map<String, Any?> = withContext(IO) {
        val source = appDb.bookSourceDao.getBookSource(url)
            ?: throw NoSuchElementException("书源不存在：$url")
        mapOf("url" to url, "variable" to source.getVariable())
    }

    /**
     * 保存源变量。
     *
     * `variable` 为 null / 空串 ⇒ 清除（`setVariable(null)`）；否则按 App 的 `putVariable` 语义写入
     * （大值自动转 `RuleBigDataHelp`，与 `VariableDialog` 完全同一条链）。
     */
    suspend fun saveVariables(url: String, variable: String?): Map<String, Any?> = withContext(IO) {
        val source = appDb.bookSourceDao.getBookSource(url)
            ?: throw NoSuchElementException("书源不存在：$url")
        if (variable.isNullOrEmpty()) source.setVariable(null) else source.putVariable(variable)
        mapOf("url" to url, "variable" to source.getVariable())
    }

    // ============================================================ 导出 / 分享（二期 tasks 2.11）

    /** 导出书源 JSON 串（指定 url 列表；为空 ⇒ 全部启用源）。 */
    suspend fun exportJson(urls: List<String>): String = withContext(IO) {
        val list = if (urls.isEmpty()) {
            appDb.bookSourceDao.allEnabled
        } else {
            urls.mapNotNull { appDb.bookSourceDao.getBookSource(it) }
        }
        GSON.toJson(list)
    }

    /**
     * 生成分享二维码（返回内容串 + PNG base64）。
     *
     * 二维码内容串与 App 内「分享书源」同格式（`GSON.toJson(源列表)`），
     * 图片由 [QRCodeUtils.createQRCode] 生成；MCP 出参是 JSON，故以 base64 承载 PNG。
     */
    suspend fun shareQr(urls: List<String>, size: Int): Map<String, Any?> {
        val content = exportJson(urls)
        val bitmap = QRCodeUtils.createQRCode(content, size.coerceIn(200, 1024))
            ?: throw IllegalStateException("二维码生成失败（内容可能过长）")
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bitmap.recycle()
        return mapOf(
            "content" to content,
            "size" to bytes.size,
            "pngBase64" to android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
        )
    }

    // ============================================================ 导入（二期 tasks 2.11）

    /**
     * 批量导入书源（复用 [parseAndValidate] 同一校验口径后落库）。
     *
     * 与 App 导入页判据一致：`deterministicFail` 的源默认不导入（返回在 `skipped` 里）。
     */
    suspend fun importJson(json: String): Map<String, Any?> {
        val validated = parseAndValidate(json)
        val saved = saveSources(validated.accepted)
        return mapOf(
            "imported" to saved.size,
            "skipped" to validated.skipped.size,
            "skippedDetail" to validated.skipped.take(20),
        )
    }

    // ============================================================ 源质量报告（二期 tasks 2.11）

    /**
     * 批量源质量报告（**L1 静态体检，0 网络**）。
     *
     * 与 App 导入页/质量报告页同一件 [SourceQualityChecker.l1StaticCheckBookSource]；
     * 需要联网实测的深度体检走 L3 工具 `validate_source`（debug 面）。
     *
     * @param apply 非空时落库：`disable_failed` ⇒ 停用确定性失败的源；`delete_failed` ⇒ 从书架源中删除
     */
    suspend fun qualityReport(urls: List<String>, apply: String?): Map<String, Any?> {
        val sources = withContext(IO) {
            if (urls.isEmpty()) appDb.bookSourceDao.all
            else urls.mapNotNull { appDb.bookSourceDao.getBookSource(it) }
        }
        val reports = sources.map { source ->
            val report = SourceQualityChecker.l1StaticCheckBookSource(source)
            mapOf(
                "bookSourceUrl" to source.bookSourceUrl,
                "bookSourceName" to source.bookSourceName,
                "score" to report.score,
                "coverage" to report.coverage,
                "deterministicFail" to report.deterministicFail,
                "failureKind" to report.failureKind.name,
                "suspectReasons" to report.suspectReasons.map { it.toString() },
                "dimensions" to report.dimensions.map { (dim, result) ->
                    mapOf(
                        "dim" to dim.name,
                        "state" to result.state.name,
                        "resultCount" to result.resultCount,
                        "durationMs" to result.durationMs,
                        "evidence" to result.evidence,
                    )
                },
            )
        }
        var applied = 0
        when (apply) {
            null, "", "none" -> Unit
            "disable_failed" -> {
                val failed = sources.filter { SourceQualityChecker.l1StaticCheckBookSource(it).deterministicFail }
                applied = setEnabled(failed.map { it.bookSourceUrl }, false)
            }

            "delete_failed" -> {
                val failed = sources.filter { SourceQualityChecker.l1StaticCheckBookSource(it).deterministicFail }
                deleteSources(failed)
                applied = failed.size
            }

            else -> throw IllegalArgumentException("apply 仅支持 none/disable_failed/delete_failed")
        }
        return mapOf(
            "total" to reports.size,
            "failedCount" to reports.count { it["deterministicFail"] == true },
            "applied" to applied,
            "reports" to reports,
        )
    }
}
