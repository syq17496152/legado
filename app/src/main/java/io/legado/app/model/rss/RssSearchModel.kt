package io.legado.app.model.rss

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
    // 阶段11.4 问题4 修复：threadCount 改为 var，initSearchPool 时重读 AppConfig.searchThreadCount
    // 这样用户在其他设置调整线程数后，下次搜索立即生效（无需重启 App）
    var threadCount = AppConfig.searchThreadCount
    private var searchPool: ExecutorCoroutineDispatcher? = null
    private var mSearchId = 0L
    private var searchKey: String = ""
    private var searchJob: Job? = null
    private var workingState = MutableStateFlow(true)

    /**
     * 阶段11.4 问题3 新增：搜索结果类型筛选
     *
     * - -1 = 全部（默认）
     * - 0 = 网页（RssArticle.type == 0）
     * - 1 = 图片（RssArticle.type == 1）
     * - 2 = 视频（RssArticle.type == 2）
     *
     * 注意：RssSource 本身没有类型字段（一个源可输出多种类型文章），
     * 所以无法在源头过滤，只能在结果层过滤。
     * 类型筛选改变后，需要重新触发搜索才能生效。
     */
    var searchType: Int = -1
        private set

    fun setSearchType(type: Int) {
        if (searchType != type) {
            searchType = type
        }
    }

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
        // 阶段11.4 问题4 修复：重读 AppConfig.searchThreadCount，响应用户设置变更
        // 阶段11.4 问题1 验收反馈修复：去掉 min(threadCount, AppConst.MAX_THREAD) 硬上限，
        // 让线程池大小完全跟随用户在"其他设置→搜索线程数"的配置。
        //
        // 用户反馈："配置的是32，应该使用系统配置呀，比如我手机性能好，
        // 我根据系统配置线程数配到60，你还不让我配置了？"
        //
        // 原设计 MAX_THREAD=9 硬上限限制了用户配置（用户配 32 实际只用 9），
        // 导致 222 个源最坏需要 740s 才能完成搜索（9 并发 × 30s 超时 × 24.67 批次）。
        // 去掉上限后，用户配 32 则实际并发 32，最坏 222/32×30s ≈ 208s（提升 3.5 倍）。
        //
        // 注意：AppConfig.searchThreadCount 在 UI 配置项已有合理范围限制（用户自行负责），
        // 此处不再加额外上限，完全尊重用户配置。
        threadCount = AppConfig.searchThreadCount
        searchPool = Executors
            .newFixedThreadPool(threadCount).asCoroutineDispatcher()
    }

    /**
     * 搜索入口
     *
     * 阻塞点 11 修复：必须先调用 initSearchPool() 初始化线程池，否则 searchPool!! 会 NPE
     *
     * 阶段11.4 问题2 修复：原代码 searchId != mSearchId 分支中调用 close() 会将 searchPool 置 null，
     * 随后 startSearch() 中 searchPool!! 抛 NPE，导致搜索"时好时坏"。
     * 修复：改用 cancelSearch()，只取消旧 Job 不关闭线程池，线程池在下次 initSearchPool 时重建。
     */
    fun search(searchId: Long, key: String) {
        AppLog.putDebugWithTag(AppLog.TAG_RSS, "开始RSS搜索 searchId=$searchId keyLen=${key.length}", level = AppLog.Level.INFO)
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
                // 阶段11.4 问题2 修复：cancelSearch() 仅取消 Job，不关闭线程池，避免后续 searchPool!! NPE
                cancelSearch()
            }
            searchArticlesMap.clear()
            searchArticles = emptyList()
            mSearchId = searchId
        }
        startSearch(rssSources)
    }

    private fun startSearch(rssSources: List<io.legado.app.data.entities.RssSource>) {
        AppLog.putDebugWithTag(AppLog.TAG_RSS, "启动RSS搜索 源数量=${rssSources.size} 线程数=$threadCount", level = AppLog.Level.INFO)
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

        // 阶段11.4 问题3 新增：按 searchType 过滤文章（-1=全部 不过滤）
        val filteredArticles = if (searchType == -1) {
            newArticles
        } else {
            newArticles.filter { it.type == searchType }
        }
        if (filteredArticles.isEmpty()) return

        // 阻塞点 15 修复：批量查询已读状态（按 origin 分组查询，避免 N 次单条查询）
        val readLinksByOrigin = mutableMapOf<String, HashSet<String>>()
        filteredArticles.groupBy { it.origin }.forEach { (origin, articles) ->
            val links = articles.map { it.link }
            val readLinks = appDb.rssArticleDao.getReadLinks(origin, links).toHashSet()
            readLinksByOrigin[origin] = readLinks
        }

        for (article in filteredArticles) {
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
        AppLog.putDebugWithTag(AppLog.TAG_RSS, "RSS搜索结果合并完成 总数=${searchArticles.size} equal=${equalData.size} contains=${containsData.size} other=${otherData.size}", level = AppLog.Level.INFO)
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
