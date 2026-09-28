package io.legado.app.service.kernel

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.source.SourceHelp
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

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
}
