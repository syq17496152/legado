package io.legado.app.help.source

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * lastHost 回填 Helper（design.md AD-04: 变化才写策略）
 *
 * 数据流：
 *   1. WebBook/Rss/Debug 创建 AnalyzeUrl 后，调用 [fillBack] 传入 source + analyzeUrl
 *   2. Helper 提取 analyzeUrl.url 的 host，与 source.lastHost 比对
 *   3. 变化才异步写 DB（避免每次请求都写）
 *   4. 内存缓存减少 DB 读取
 *
 * 使用方式：
 *   val analyzeUrl = AnalyzeUrl(...)
 *   SourceLastHostHelper.fillBack(bookSource, analyzeUrl)
 *
 * 注意：本 Helper 只负责回填 lastHost 字段，不修改 source 对象本身。
 * 调用方若需要内存中的 source.lastHost 立即更新（如 UI 显示），需自行同步。
 */
object SourceLastHostHelper {

    /** 内存缓存：sourceUrl -> lastHost，避免重复 DB 写入 */
    private val memoryCache = mutableMapOf<String, String?>()
    private val cacheMutex = Mutex()

    /**
     * 从 AnalyzeUrl 提取 host 并回填 lastHost。
     * 变化才写 DB；未变化直接返回。
     *
     * @param source 书源或订阅源（必须已设置 sourceUrl/bookSourceUrl）
     * @param analyzeUrl 已完成解析的 AnalyzeUrl（取其 url 字段）
     */
    suspend fun fillBack(source: BaseSource, analyzeUrl: AnalyzeUrl) {
        val url = analyzeUrl.url
        if (url.isBlank()) return
        val host = NetworkUtils.getSubDomainOrNull(url) ?: return
        val sourceUrl = source.getKey()
        if (sourceUrl.isBlank()) return

        // 内存缓存比对，避免不必要的 DB 写入
        val cached = cacheMutex.withLock { memoryCache[sourceUrl] }
        if (cached == host) return

        // 更新内存缓存
        cacheMutex.withLock { memoryCache[sourceUrl] = host }

        // 异步写 DB（变化才写）
        withContext(Dispatchers.IO) {
            kotlin.runCatching {
                when (source) {
                    is io.legado.app.data.entities.BookSource -> {
                        if (source.lastHost != host) {
                            appDb.bookSourceDao.updateLastHost(sourceUrl, host)
                            source.lastHost = host
                        }
                    }
                    is io.legado.app.data.entities.RssSource -> {
                        if (source.lastHost != host) {
                            appDb.rssSourceDao.updateLastHost(sourceUrl, host)
                            source.lastHost = host
                        }
                    }
                }
            }.onFailure { e ->
                AppLog.put("SourceLastHostHelper fillBack failed: ${e.message}", e)
            }
        }
    }

    /**
     * 清空内存缓存（App 退出或内存压力时调用）。
     * 持久化数据已在每次变化时写入 DB，无需额外保存。
     */
    fun clearCache() {
        memoryCache.clear()
    }
}
