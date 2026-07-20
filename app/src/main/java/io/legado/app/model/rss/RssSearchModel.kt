package io.legado.app.model.rss

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.SearchRssArticle
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.mapParallelSafe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * 订阅源统一搜索并发调度核心（rss-unified-search 新增）
 *
 * 参考 [io.legado.app.model.webBook.SearchModel] 的并发调度设计：
 * - 固定线程池并发搜索多个订阅源
 * - 30 秒超时机制
 * - 支持暂停/恢复/停止
 * - 多源结果聚合去重
 *
 * 与 SearchModel 的差异：
 * - 不支持分页加载（AD-07，每个源仅取第 1 页结果）
 * - 不支持精度搜索（订阅源无 kind 字段）
 * - 去重 key 为 title + pubDate（参考书源 name + author）
 * - 单源失败时记录日志但不影响其他源（遗漏点 31 修复）
 */
class RssSearchModel(
    private val scope: CoroutineScope,
    private val callBack: CallBack
) {
    val threadCount = AppConfig.threadCount
    private var searchPool: ExecutorCoroutineDispatcher? = null
    private var mSearchId = 0L
    private var searchKey: String = ""
    private var searchJob: Job? = null
    private var workingState = MutableStateFlow(true)

    /**
     * 已聚合的文章 Map（阻塞点 12 修复：使用成员变量保留去重信息，避免每次创建局部 Map 覆盖之前结果）
     *
     * key: SearchRssArticle.deduplicationKey() = "$title|$pubDate"
     * value: 聚合后的 SearchRssArticle
     */
    private val searchArticlesMap = linkedMapOf<String, SearchRssArticle>()

    /**
     * 当前搜索结果列表（按分组排序后）
     */
    private var searchArticles: List<SearchRssArticle> = emptyList()

    private fun initSearchPool() {
        searchPool?.close()
        searchPool = Executors
            .newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    }

    /**
     * 搜索入口
     *
     * 阻塞点 11 修复：必须先调用 initSearchPool() 初始化线程池，否则 searchPool!! 会 NPE
     */
    fun search(searchId: Long, key: String) {
        val rssSources = callBack.getSearchScope().getRssSources()
        if (rssSources.isEmpty()) {
            callBack.onSearchCancel(NoStackTraceException("启用订阅源为空或无 searchUrl"))
            return
        }
        // 阻塞点 11 修复：先初始化线程池
        initSearchPool()
        // searchId 检查：连续搜索时取消旧搜索
        if (searchId != mSearchId) {
            if (key.isEmpty()) {
                return
            }
            searchKey = key
            if (mSearchId != 0L) {
                close()
            }
            searchArticlesMap.clear()
            searchArticles = emptyList()
            mSearchId = searchId
        }
        startSearch(rssSources)
    }

    private fun startSearch(rssSources: List<io.legado.app.data.entities.RssSource>) {
        searchJob = scope.launch(searchPool!!) {
            flow {
                for (source in rssSources) {
                    emit(source)
                    workingState.first { it }  // 支持暂停/恢复
                }
            }.onStart {
                callBack.onSearchStart()
            }.mapParallelSafe(threadCount) { source ->
                // 遗漏点 31 修复：mapParallelSafe 内部增加 try/catch，单个源失败时记录日志并返回空列表
                // 遗漏点 38 修复：异常分类，便于用户感知
                try {
                    withTimeout(30000L) {
                        val (articles, _) = Rss.getArticlesAwait(
                            sortName = "搜索",
                            sortUrl = source.searchUrl!!,
                            rssSource = source,
                            page = 1,
                            key = searchKey
                        )
                        articles
                    }
                } catch (e: Throwable) {
                    val errMsg = when (e) {
                        is UnknownHostException -> "源[${source.sourceName}]搜索失败：网络不通"
                        is SocketTimeoutException -> "源[${source.sourceName}]搜索超时（30s）"
                        is ConnectException -> "源[${source.sourceName}]连接被拒绝"
                        else -> "源[${source.sourceName}]搜索失败：${e.localizedMessage}"
                    }
                    AppLog.put(errMsg, e)
                    emptyList()
                }
            }.onEach { articles ->
                mergeItems(articles, searchKey)
                currentCoroutineContext().ensureActive()
                callBack.onSearchSuccess(searchArticles)
            }.onCompletion {
                if (it == null) callBack.onSearchFinish(searchArticles.isEmpty())
            }.catch {
                AppLog.put("订阅源搜索出错\n${it.localizedMessage}", it)
            }.collect()
        }
    }

    /**
     * 多源结果去重合并（阻塞点 12 修复：使用成员变量 searchArticlesMap 保留去重信息）
     *
     * 参考 [io.legado.app.model.webBook.SearchModel.mergeItems] 的分组策略：
     * - equalData：标题完全匹配 searchKey
     * - containsData：标题包含 searchKey
     * - otherData：其他（因订阅源自身规则匹配）
     *
     * 与书源 SearchModel.mergeItems 的差异：
     * - 书源有 4 个分组（equalData/tagsData/containsData/otherData），tagsData 用于 kind 字段
     * - 订阅源无 kind 字段，只用 3 个分组（equalData/containsData/otherData）
     *
     * 阻塞点 15 修复：批量查询 rssArticles 表判断已读状态
     *
     * @param newArticles 单个源返回的文章列表
     * @param searchKey 搜索关键词（用于分组排序）
     */
    private suspend fun mergeItems(newArticles: List<RssArticle>, searchKey: String) {
        if (newArticles.isEmpty()) return

        // 阻塞点 15 修复：批量查询已读状态（按 origin 分组查询，避免 N 次单条查询）
        val readLinksByOrigin = mutableMapOf<String, HashSet<String>>()
        newArticles.groupBy { it.origin }.forEach { (origin, articles) ->
            val links = articles.map { it.link }
            val readLinks = appDb.rssArticleDao.getReadLinks(origin, links).toHashSet()
            readLinksByOrigin[origin] = readLinks
        }

        for (article in newArticles) {
            currentCoroutineContext().ensureActive()
            val key = article.deduplicationKey()

            // 已读状态判断（阻塞点 15）
            val isRead = readLinksByOrigin[article.origin]?.contains(article.link) == true

            searchArticlesMap[key]?.let { existing ->
                // 已存在：追加源信息
                existing.addOrigin(article.origin, article)
                // 已读状态：任一源已读则标记为已读
                if (isRead) existing.isRead = true
            } ?: run {
                // 新文章：创建 SearchRssArticle 并加入 map
                SearchRssArticle(
                    title = article.title,
                    pubDate = article.pubDate,
                    description = article.description,
                    image = article.image,
                    type = article.type,
                    isRead = isRead
                ).apply {
                    addOrigin(article.origin, article)
                    searchArticlesMap[key] = this
                }
            }
        }

        // 分组排序：标题完全匹配 > 标题包含 > 其他，组内按 origins.size 降序
        val equalData = arrayListOf<SearchRssArticle>()
        val containsData = arrayListOf<SearchRssArticle>()
        val otherData = arrayListOf<SearchRssArticle>()

        searchArticlesMap.values.forEach { article ->
            when {
                article.title == searchKey -> equalData.add(article)
                article.title.contains(searchKey) -> containsData.add(article)
                else -> otherData.add(article)
            }
        }

        equalData.sortByDescending { it.origins.size }
        containsData.sortByDescending { it.origins.size }
        otherData.sortByDescending { it.origins.size }

        searchArticles = equalData + containsData + otherData
    }

    /**
     * 扩展函数：RssArticle 的去重 key（与 SearchRssArticle.deduplicationKey 一致）
     */
    private fun RssArticle.deduplicationKey(): String = "$title|${pubDate ?: ""}"

    fun pause() {
        workingState.value = false
    }

    fun resume() {
        workingState.value = true
    }

    fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
    }

    fun close() {
        cancelSearch()
        searchPool?.close()
        searchPool = null
    }

    /**
     * 回调接口（遗漏点 32 修复：明确方法签名）
     *
     * 与 SearchModel.CallBack 的差异：
     * - 无 hasMore 参数（AD-07 不支持分页加载）
     * - getSearchScope 返回 RssSearchScope（非 SearchScope）
     * - onSearchSuccess 返回 List<SearchRssArticle>（非 List<SearchBook>）
     */
    interface CallBack {
        fun getSearchScope(): RssSearchScope
        fun onSearchStart()
        fun onSearchSuccess(articles: List<SearchRssArticle>)
        fun onSearchFinish(isEmpty: Boolean)
        fun onSearchCancel(exception: Throwable? = null)
    }

}
