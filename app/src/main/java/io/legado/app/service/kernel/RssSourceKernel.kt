package io.legado.app.service.kernel

import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.SourceHelp
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
}
