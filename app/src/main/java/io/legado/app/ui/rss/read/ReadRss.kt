package io.legado.app.ui.rss.read

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.SourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.entities.RssSource
import io.legado.app.model.VideoPlay
import io.legado.app.ui.image.ImageGalleryActivity
import io.legado.app.ui.image.ImagePlay
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.utils.startActivity
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ReadRss {
    /**
     * 视频播放上下文的**单一写入点**（REQ-11 / tasks 2.2）。
     *
     * 抽出原因：原 `readRss(activity, rssArticle, …)` 与 `readRss(fragment, …)` **各写一遍**同一批
     * `VideoPlay` 字段；新增的「type=0 正文含 `<video>` 自动转播放器」路径（`ReadRssViewModel`）
     * 需要与二者**完全一致**的上下文 ⇒ 三处各写一遍必然漂移（本项目已多次发生同类漂移）。
     *
     * 语义**保持原样**：`indexOfFirst` 未命中（列表为 null 或找不到）一律兜底 0；找不到时输出
     * source mismatch WARN（供误配源排查）。
     *
     * W1 2.2 补强（2026-09-27 L2 真机缺陷回归）：`rssArticles` 为 null 时**兜底为「仅含本篇」的列表**
     * —— 否则播放器侧解析不到文章（详见函数体内注释与 `RssVideoRouteTest.playContextMustBeResolvableByPlayer`）。
     */
    fun prepareVideoPlayContext(
        rssArticle: RssArticle,
        rssArticles: List<RssArticle>? = null,
        sortName: String? = null,
        sortUrl: String? = null,
        nextPageUrl: String? = null,
        page: Int = 1
    ) {
        // W1 2.2 补强（2026-09-27 L2 真机缺陷）：调用方 `ReadRssViewModel` 只有「单篇文章」上下文，
        // 若原样写入 null，`VideoPlay.startPlay` 的解析式
        // `rssStar ?: rssRecord ?: rssArticles?.getOrNull(rssArticleIndex)` 三项全空 ⇒ 静默 return，
        // 表现为「自动进了播放器但一直不播」（真机日志 `VideoPlay: rssArticle is null in startPlay`）。
        // 兜底为「仅含本篇」的列表：既能被解析到（indexOfFirst 命中 ⇒ 索引 0），又诚实表达
        // 「本路由无上下滑动上下文」（rssArticlesHasMore=false）。
        VideoPlay.rssArticles = rssArticles ?: listOf(rssArticle)
        // B3 修复：分离 null 兜底与 -1 兜底，-1 时输出 WARN 并兜底为 0
        val matchedIndex = rssArticles?.indexOfFirst { it.link == rssArticle.link }
        VideoPlay.rssArticleIndex = if (matchedIndex == null) {
            0
        } else if (matchedIndex < 0) {
            AppLog.put(
                "ReadRss: source mismatch WARN, rssArticle.origin=${rssArticle.origin.take(2)}***, " +
                    "rssArticles[0].origin=${rssArticles.firstOrNull()?.origin?.take(2)}***, fallback index=0"
            )
            0
        } else {
            matchedIndex
        }
        // 阶段8 F9：传递分页上下文给 VideoPlay，支持播放器内分页加载
        VideoPlay.rssSortName = sortName
        VideoPlay.rssSortUrl = sortUrl
        VideoPlay.rssNextPageUrl = nextPageUrl
        VideoPlay.rssArticlePage = page
        VideoPlay.rssArticlesHasMore = !nextPageUrl.isNullOrBlank()
    }

    /**
     * 补齐视频播放的「文章列表上下文」（历史记录 / 收藏页 / 单篇上下文）。
     *
     * 为什么需要：沉浸式上下滑切换视频 与 传统式「上一部/下一部」的可见性都由播放器侧
     * `VideoPlay.rssArticles.size > 1` 决定。列表页与阅读页自动路由已各自补齐，但
     * **历史记录**（`readRss(activity, record)`）与**收藏页**（只带单篇 star）上下文缺失
     * ⇒ 从这两个入口进入播放器时两处功能同时失效（与列表路径互不干扰，须分别覆盖）。
     *
     * 口径与列表页 `flowByOriginSort`、阅读页自动路由 `getListByOriginSort` 完全一致
     * （同源 + 同分类）；查不到或未含本篇时把本篇补入并置首，保证播放器侧按 link 匹配能命中。
     */
    private suspend fun resolveVideoArticles(
        rssArticle: RssArticle,
        given: List<RssArticle>?
    ): List<RssArticle> {
        if (given != null && given.size > 1) return given
        val list = runCatching {
            appDb.rssArticleDao.getListByOriginSort(rssArticle.origin, rssArticle.sort)
        }.getOrDefault(emptyList()).toMutableList()
        if (list.none { it.link == rssArticle.link }) {
            list.add(0, rssArticle)
        }
        return list
    }

    /**
     * 启动视频播放页（Fragment 入口）。
     *
     * 列表主查询 `flowByOriginSort` 不含 image 列（CursorWindow 优化），内存对象 image 恒 null
     * ⇒ 播放页信息区无封面；此处异步单行 `getImage` 回填，完成后发 sticky VIDEO_SUB_TITLE
     * 触发播放页信息区重刷（与原实现同口径）。
     */
    private fun startVideoFromFragment(
        fragment: Fragment,
        rssArticle: RssArticle,
        rssArticles: List<RssArticle>,
        sortName: String?,
        sortUrl: String?,
        nextPageUrl: String?,
        page: Int
    ) {
        fragment.viewLifecycleOwner.lifecycleScope.launch(IO) {
            rssArticles.forEach { article ->
                if (article.image.isNullOrBlank()) {
                    article.image = appDb.rssArticleDao.getImage(article.origin, article.link)
                }
            }
            postEvent(EventBus.VIDEO_SUB_TITLE, VideoPlay.videoTitle ?: "")
        }
        prepareVideoPlayContext(rssArticle, rssArticles, sortName, sortUrl, nextPageUrl, page)
        fragment.startActivity<VideoPlayerActivity> {
            putExtra("sourceKey", rssArticle.origin)
            putExtra("sourceType", SourceType.rss)
            putExtra("record", rssArticle.link)
            putExtra("videoTitle", rssArticle.title) // R3 title 修复：传递标题给 VideoPlayerActivity
        }
    }

    /** 启动视频播放页（Activity 入口；同时服务列表路径与历史记录路径） */
    private fun startVideoFromActivity(
        activity: AppCompatActivity,
        rssArticle: RssArticle,
        rssArticles: List<RssArticle>,
        sortName: String?,
        sortUrl: String?,
        nextPageUrl: String?,
        page: Int
    ) {
        prepareVideoPlayContext(rssArticle, rssArticles, sortName, sortUrl, nextPageUrl, page)
        activity.startActivity<VideoPlayerActivity> {
            putExtra("sourceKey", rssArticle.origin)
            putExtra("sourceType", SourceType.rss)
            putExtra("record", rssArticle.link)
            putExtra("videoTitle", rssArticle.title)
        }
    }

    /**
     * 通过RSS历史记录点击阅读
     */
    fun readRss(activity: AppCompatActivity, record: RssReadRecord) {
        val type = record.type
        if (type == 2) {
            // 历史记录入口只带「单篇」上下文（`record` 不含列表）⇒ 按同源同分类补齐，
            // 否则播放器侧 `rssArticles.size > 1` 不成立，沉浸式上下滑 与 传统式上一部下一部
            // 同时失效（与列表点击、阅读页自动路由是相互独立的第三条链路）。
            val article = record.toRssArticle()
            activity.lifecycleScope.launch(IO) {
                val articles = resolveVideoArticles(article, null)
                withContext(Dispatchers.Main) {
                    startVideoFromActivity(
                        activity, article, articles,
                        sortName = article.sort, sortUrl = null, nextPageUrl = null, page = 1
                    )
                }
            }
            return
        }
        if (type == 1) {
            // type=1 图片订阅源，直接走 ImageGalleryActivity
            readNoHtml(activity, record, type)
            return
        }
        // type=0 网页模式：走 ReadRssActivity
        // 回退说明（用户2026-07-26 10:09 反馈）：
        // 即使订阅源 articleStyle=2（图片列表样式），用户主动选择网页模式就必须走网页模式
        // 禁止"自动识别为图片就转为图片查看器"，图片查看器入口改为用户主动选择
        ReadRssActivity.start(
            activity,
            record.origin,
            record.title,
            link = record.record,
            sort = record.sort
        )
    }

    /**
     * 订阅源统一搜索结果点击阅读（rss-unified-search 新增）
     *
     * 参考 [readRss] Fragment 版本的设计，使用 activity.lifecycleScope 替代 fragment.viewLifecycleOwner.lifecycleScope。
     */
    fun readRss(
        activity: AppCompatActivity,
        rssArticle: RssArticle,
        rssArticles: List<RssArticle>? = null,
        sortName: String? = null,
        sortUrl: String? = null,
        nextPageUrl: String? = null,
        page: Int = 1
    ) {
        val rssReadRecord = rssArticle.toRecord()
        activity.lifecycleScope.launch(IO) {
            appDb.rssReadRecordDao.insertRecord(rssReadRecord)
        }
        val type = rssArticle.type
        if (type == 2) {
            // 视频播放：`rssArticles` 支持播放页上/下一个切换文章（废除 AD-07 简化原则）。
            // 调用方可能只带单篇（如搜索结果、收藏页单篇转文章）⇒ 统一在 IO 线程补齐同源同分类列表，
            // 保证播放器侧 `size > 1` 成立（沉浸式上下滑 / 传统式上一部下一部）。
            activity.lifecycleScope.launch(IO) {
                val articles = resolveVideoArticles(rssArticle, rssArticles)
                withContext(Dispatchers.Main) {
                    startVideoFromActivity(
                        activity, rssArticle, articles,
                        sortName = sortName, sortUrl = sortUrl, nextPageUrl = nextPageUrl, page = page
                    )
                }
            }
            return
        }
        if (type == 1) {
            // type=1 图片订阅源，直接走 ImageGalleryActivity
            readNoHtml(
                activity, rssReadRecord, type, rssArticles, sortName, sortUrl, nextPageUrl, page
            )
            return
        }
        // type=0 网页模式：走 ReadRssActivity（禁止自动转为图片查看器，回退说明见上）
        ReadRssActivity.start(
            activity,
            rssArticle.origin,
            rssArticle.title,
            link = rssArticle.link,
            sort = rssArticle.sort
        )
    }

    fun readRss(
        fragment: Fragment,
        rssArticle: RssArticle,
        rssSource: RssSource? = null,
        rssArticles: List<RssArticle>? = null,
        sortName: String? = null,
        sortUrl: String? = null,
        nextPageUrl: String? = null,
        page: Int = 1
    ) {
        val rssReadRecord = rssArticle.toRecord()
        fragment.viewLifecycleOwner.lifecycleScope.launch(IO) {
            appDb.rssReadRecordDao.insertRecord(rssReadRecord)
        }
        // 优先用 rssSource.type（订阅源当前类型），rssArticle.type 作为兜底（旧缓存可能未更新）
        // 修复场景：用户将图片源(type=1)改为网页模式(type=0)后，旧文章缓存 type 仍为 1 导致路由错误
        val type = rssSource?.type ?: rssArticle.type
        if (type == 2) {
            // 视频播放：设置文章列表到 VideoPlay 单例，支持上下滑动切换文章。
            // 上下文可能缺失（收藏页只带单篇 star / 单篇搜索结果）⇒ 按同源同分类补齐后再启动。
            if (rssArticles != null && rssArticles.size > 1) {
                // 列表路径上下文已完整：同步启动，保持既有启动时序（不引入查库延迟）
                startVideoFromFragment(fragment, rssArticle, rssArticles, sortName, sortUrl, nextPageUrl, page)
            } else {
                // 上下文缺失：Room suspend 查询自带线程切换，`launch` 默认 Main 不会阻塞 UI
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    val articles = resolveVideoArticles(rssArticle, rssArticles)
                    startVideoFromFragment(fragment, rssArticle, articles, sortName, sortUrl, nextPageUrl, page)
                }
            }
            return
        }
        if (type == 1) {
            // type=1 图片订阅源，直接走 ImageGalleryActivity
            readNoHtml(
                fragment, rssArticle, rssSource, type, rssArticles, sortName, sortUrl, nextPageUrl, page
            )
            return
        }
        // type=0 网页模式：走 ReadRssActivity（禁止自动转为图片查看器，回退说明见上）
        ReadRssActivity.start(
            fragment.requireContext(),
            rssArticle.origin,
            rssArticle.title,
            link = rssArticle.link,
            sort = rssArticle.sort
        )
    }

    /**
     * 图片订阅源（type==1）入口：设置 ImagePlay 单例 + 启动 ImageGalleryActivity
     *
     * 改造说明（image-gallery-activity spec）：
     * - 原：ruleContent 解析为单URL，用 PhotoDialog 显示单图
     * - 新：设置 ImagePlay 单例，启动 ImageGalleryActivity，由
     *       ImageCanvasViewModel.loadArticleInternal → Rss.getContentAwait 取 body，
     *       再交 ImageUrlExtractor.extractImageList 解析为图片URL列表，支持多图浏览
     * - ruleContent 为空时：由 ImageUrlExtractor 的「单URL兜底」策略用 article.link 作单图
     *       （原图集 ViewModel 已随死件清理删除，逻辑全部落在 ImageUrlExtractor）
     */
    private fun readNoHtml(
        fragment: Fragment,
        rssArticle: RssArticle,
        rssSource: RssSource? = null,
        type: Int,
        rssArticles: List<RssArticle>? = null,
        sortName: String? = null,
        sortUrl: String? = null,
        nextPageUrl: String? = null,
        page: Int = 1
    ) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val rssSource = rssSource ?: withContext(IO) { appDb.rssSourceDao.getByKey(rssArticle.origin) }
            rssSource?.let { s ->
                // V-004-Image-Regress: 进入前先清理垂直画布状态（防止 Activity 异常退出后残留旧数据）
                // 根因：ImageGalleryActivity.onDestroy 调用 clearImageCanvasState 正常流程会清理，
                //      但 Activity 异常崩溃/被系统杀死时 onDestroy 不执行，下次进入残留旧 allImageUrls/loadedArticleIndices，
                //      导致 ImageCanvasAdapter 展示旧图 + loadNextArticle 误跳过索引。
                // 方案：进入前显式清理，保证每次进入都是干净状态。
                ImagePlay.clearImageCanvasState()
                // 设置 ImagePlay 单例（参考 VideoPlay 机制，支持跨文章切换）
                ImagePlay.rssArticles = rssArticles
                ImagePlay.rssArticleIndex = rssArticles?.indexOfFirst { it.link == rssArticle.link } ?: 0
                ImagePlay.rssSource = s
                ImagePlay.rssSortName = sortName
                ImagePlay.rssSortUrl = sortUrl
                ImagePlay.rssNextPageUrl = nextPageUrl
                ImagePlay.rssArticlePage = page
                ImagePlay.rssArticlesHasMore = !nextPageUrl.isNullOrBlank()
                // 启动 ImageGalleryActivity（type==1 图片订阅源）
                when (type) {
                    1 -> fragment.startActivity<ImageGalleryActivity> {
                        putExtra("sourceKey", rssArticle.origin)
                        putExtra("record", rssArticle.link)
                        putExtra("title", rssArticle.title)
                    }
                }
            }
        }
    }

    /**
     * 图片订阅源（type==1）入口：Activity 版本（从历史记录点击）
     */
    private fun readNoHtml(
        activity: AppCompatActivity,
        record: RssReadRecord,
        type: Int,
        rssArticles: List<RssArticle>? = null,
        sortName: String? = null,
        sortUrl: String? = null,
        nextPageUrl: String? = null,
        page: Int = 1
    ) {
        activity.lifecycleScope.launch {
            val rssSource = withContext(IO) { appDb.rssSourceDao.getByKey(record.origin) }
            rssSource?.let { s ->
                // V-004-Image-Regress: 进入前先清理垂直画布状态（同 Fragment 版本理由）
                ImagePlay.clearImageCanvasState()
                // 设置 ImagePlay 单例
                ImagePlay.rssArticles = rssArticles
                ImagePlay.rssArticleIndex = rssArticles?.indexOfFirst { it.link == record.record } ?: 0
                ImagePlay.rssSource = s
                ImagePlay.rssSortName = sortName
                ImagePlay.rssSortUrl = sortUrl
                ImagePlay.rssNextPageUrl = nextPageUrl
                ImagePlay.rssArticlePage = page
                ImagePlay.rssArticlesHasMore = !nextPageUrl.isNullOrBlank()
                // 启动 ImageGalleryActivity
                when (type) {
                    1 -> activity.startActivity<ImageGalleryActivity> {
                        putExtra("sourceKey", record.origin)
                        putExtra("record", record.record)
                        putExtra("title", record.title)
                    }
                }
            }
        }
    }

}
