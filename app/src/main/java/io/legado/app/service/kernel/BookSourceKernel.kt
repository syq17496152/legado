package io.legado.app.service.kernel

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.source.BookSourceIncrementalParser
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.DimState
import io.legado.app.model.SourceQualityChecker
import kotlinx.coroutines.Dispatchers.IO
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
}
