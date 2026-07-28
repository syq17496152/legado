package io.legado.app.ui.rss.read

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.legado.app.constant.AppLog
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
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ReadRss {
    /**
     * 通过RSS历史记录点击阅读
     */
    fun readRss(activity: AppCompatActivity, record: RssReadRecord) {
        val type = record.type
        if (type == 2) {
            activity.startActivity<VideoPlayerActivity> {
                putExtra("sourceKey", record.origin)
                putExtra("sourceType", SourceType.rss)
                putExtra("record", record.record)
                putExtra("videoTitle", record.title) // R3 title 修复：传递标题给 VideoPlayerActivity
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
            // 视频播放：从详情页传入 rssArticles 支持播放页上/下一个切换文章（废除 AD-07 简化原则）
            VideoPlay.rssArticles = rssArticles
            // B3 修复：分离 null 兜底与 -1 兜底，-1 时输出 WARN 并兜底为 0（配合 B2 source 同步更新）
            val matchedIndex = rssArticles?.indexOfFirst { it.link == rssArticle.link }
            VideoPlay.rssArticleIndex = if (matchedIndex == null) {
                0
            } else if (matchedIndex < 0) {
                // 文章不在列表中（如聚合搜索结果与文章列表源不一致），WARN 日志 + 兜底为 0
                AppLog.put(
                    "ReadRss: source mismatch WARN, rssArticle.origin=${rssArticle.origin.take(2)}***, " +
                        "rssArticles[0].origin=${rssArticles.firstOrNull()?.origin?.take(2)}***, fallback index=0"
                )
                0
            } else {
                matchedIndex
            }
            VideoPlay.rssSortName = sortName
            VideoPlay.rssSortUrl = sortUrl
            VideoPlay.rssNextPageUrl = nextPageUrl
            VideoPlay.rssArticlePage = page
            VideoPlay.rssArticlesHasMore = !nextPageUrl.isNullOrBlank()
            activity.startActivity<VideoPlayerActivity> {
                putExtra("sourceKey", rssArticle.origin)
                putExtra("sourceType", SourceType.rss)
                putExtra("record", rssArticle.link)
                putExtra("videoTitle", rssArticle.title)
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
            //视频播放：设置文章列表到 VideoPlay 单例，支持上下滑动切换文章
            VideoPlay.rssArticles = rssArticles
            // B3 修复：分离 null 兜底与 -1 兜底，-1 时输出 WARN 并兜底为 0（配合 B2 source 同步更新）
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
            fragment.startActivity<VideoPlayerActivity> {
                putExtra("sourceKey", rssArticle.origin)
                putExtra("sourceType", SourceType.rss)
                putExtra("record", rssArticle.link)
                putExtra("videoTitle", rssArticle.title) // R3 title 修复：传递标题给 VideoPlayerActivity
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
     * - 新：设置 ImagePlay 单例，启动 ImageGalleryActivity，由 ViewModel.loadArticleContent 调用 Rss.getContentAwait
     *       获取 body 并解析为图片URL列表（split 换行符），支持多图浏览
     * - ruleContent 为空时：ImageGalleryViewModel 兜底用 article.link 作为单图URL
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
