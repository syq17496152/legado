package io.legado.app.service.kernel

import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.DimState
import io.legado.app.model.SourceQualityChecker
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

/**
 * 订阅源域业务内核（web-mcp-productization 一期 · 2.3.1 / REQ-1-201 · REQ-1-202）。
 *
 * 契约同 [BookSourceKernel]；删源同样委派 [SourceHelp.deleteRssSources]（内部已接入回收站）。
 */
object RssSourceKernel {

    suspend fun sources(): List<RssSource> = withContext(IO) { appDb.rssSourceDao.all }

    suspend fun source(url: String): RssSource? =
        withContext(IO) { appDb.rssSourceDao.getByKey(url) }

    suspend fun saveSource(source: RssSource) {
        withContext(IO) { appDb.rssSourceDao.insert(source) }
    }

    /** 批量保存有效源，返回实际写入的源。 */
    suspend fun saveSources(sources: List<RssSource>): List<RssSource> {
        val saved = ArrayList<RssSource>(sources.size)
        sources.forEach { source ->
            if (hasValidIdentity(source)) {
                saveSource(source)
                saved.add(source)
            }
        }
        return saved
    }

    suspend fun deleteSources(sources: List<RssSource>) {
        withContext(IO) { SourceHelp.deleteRssSources(sources) }
    }

    /** 源身份是否完整（口径同 [BookSourceKernel.hasValidIdentity]）。 */
    internal fun hasValidIdentity(source: RssSource): Boolean =
        source.sourceName.isNotBlank() && source.sourceUrl.isNotBlank()

    // ============================================================ 导入校验链（2.3.1 / REQ-1-311）

    /** 批量导入前的校验结果。 */
    data class ValidatedSources(
        val accepted: List<RssSource>,
        val skipped: List<SkippedSource>,
    )

    /** 被跳过的源（含白话原因）。 */
    data class SkippedSource(val name: String, val url: String, val reason: String)

    /**
     * 解析 + 校验（**与书源同口径**，REQ-1-311）。
     *
     * 与书源侧的差异仅在解析件：订阅源无增量解析器 ⇒ 用 GSON 数组解析
     * （`fromJsonArray`，与原 Controller 相同路径）；**校验件与判据完全一致**
     * （[SourceQualityChecker.l1StaticCheckRssSource] + `deterministicFail`，L1 0 网络成本）。
     *
     * @throws Exception 解析失败 —— 由门面转 "转换源失败" / "格式不对"
     */
    suspend fun parseAndValidate(json: String): ValidatedSources {
        val parsed = GSON.fromJsonArray<RssSource>(json).getOrThrow()
        val accepted = ArrayList<RssSource>(parsed.size)
        val skipped = ArrayList<SkippedSource>()
        parsed.forEach { source ->
            val reason = skipReason(source)
            if (reason == null) {
                accepted.add(source)
            } else {
                skipped.add(SkippedSource(source.sourceName, source.sourceUrl, reason))
            }
        }
        return ValidatedSources(accepted, skipped)
    }

    /** 单条保存前的校验口径（与批量完全一致）。 */
    fun skipReason(source: RssSource): String? {
        if (!hasValidIdentity(source)) return "源名称和URL不能为空"
        val report = SourceQualityChecker.l1StaticCheckRssSource(source)
        if (!report.deterministicFail) return null
        return report.dimensions.values.firstOrNull { it.state == DimState.FAIL }?.evidence
            ?: "规则残缺"
    }
}
