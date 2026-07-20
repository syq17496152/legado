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
import io.legado.app.exception.ContentEmptyException
import io.legado.app.model.VideoPlay
import io.legado.app.model.rss.Rss
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.showDialogFragment
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
        if (type == 0) {
            ReadRssActivity.start(
                activity,
                record.origin,
                record.title,
                link = record.record,
                sort = record.sort
            )
            return
        }
        if (type == 2) {
            activity.startActivity<VideoPlayerActivity> {
                putExtra("sourceKey", record.origin)
                putExtra("sourceType", SourceType.rss)
                putExtra("record", record.record)
                putExtra("videoTitle", record.title) // R3 title 修复：传递标题给 VideoPlayerActivity
            }
            return
        }
        readNoHtml(activity, record, type)
    }

    /**
     * 订阅源统一搜索结果点击阅读（rss-unified-search 新增）
     *
     * 参考 [readRss] Fragment 版本的设计，使用 activity.lifecycleScope 替代 fragment.viewLifecycleOwner.lifecycleScope。
     *
     * 使用场景：[io.legado.app.ui.rss.search.RssArticleInfoActivity] 详情页点击"阅读"按钮或某源项后调用，
     * 该 Activity 是 AppCompatActivity 而非 Fragment，无法调用 Fragment 版本的 readRss。
     *
     * 设计依据：rss-unified-search design.md §5
     *
     * @param activity 详情页所在的 Activity
     * @param rssArticle 待阅读的文章（来自 RssSearchSourceHolder.articles 的某源对应 RssArticle）
     * @param rssArticles 搜索结果列表转 RssArticle 列表（详情页从 RssSearchSourceHolder.rssArticles 传入，
     *        支持播放页上/下一个切换文章；rss-unified-search 阶段10 废除 AD-07 简化原则）
     * @param sortName 分类名称（搜索场景传 null）
     * @param sortUrl 分类 URL（搜索场景传 null）
     * @param nextPageUrl 下一页 URL（搜索场景传 null）
     * @param page 当前页码（搜索场景传 1）
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
        if (type == 0) {
            // web网页
            ReadRssActivity.start(
                activity,
                rssArticle.origin,
                rssArticle.title,
                link = rssArticle.link,
                sort = rssArticle.sort
            )
            return
        }
        if (type == 2) {
            // 视频播放：从详情页传入 rssArticles 支持播放页上/下一个切换文章（废除 AD-07 简化原则）
            VideoPlay.rssArticles = rssArticles
            // 计算 rssArticle 在列表中的索引，支持从中间文章进入播放页
            VideoPlay.rssArticleIndex = rssArticles?.indexOfFirst { it.link == rssArticle.link } ?: 0
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
        readNoHtml(activity, rssReadRecord, type)
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
        val type = rssArticle.type
        if (type == 0) {
            //web网页
            ReadRssActivity.start(
                fragment.requireContext(),
                rssArticle.origin,
                rssArticle.title,
                link = rssArticle.link,
                sort = rssArticle.sort
            )
            return
        }
        if (type == 2) {
            //视频播放：设置文章列表到 VideoPlay 单例，支持上下滑动切换文章
            VideoPlay.rssArticles = rssArticles
            VideoPlay.rssArticleIndex = rssArticles?.indexOfFirst { it.link == rssArticle.link } ?: 0
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
        readNoHtml(fragment, rssArticle, rssSource, type)
    }

    private fun readNoHtml(fragment: Fragment, rssArticle: RssArticle, rssSource: RssSource? = null, type: Int) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val rssSource = rssSource ?: withContext(IO) { appDb.rssSourceDao.getByKey(rssArticle.origin) }
            rssSource?.let { s ->
                val ruleContent = s.ruleContent
                if (ruleContent.isNullOrBlank()) {
                    when (type) {
                        1 -> fragment.showDialogFragment(PhotoDialog(rssArticle.link))
                    }
                } else {
                    Rss.getContent(fragment.viewLifecycleOwner.lifecycleScope, rssArticle, ruleContent, s)
                        .onSuccess(IO) { body ->
                            if (body.isBlank()) {
                                throw ContentEmptyException("正文为空")
                            }
                            val url = NetworkUtils.getAbsoluteURL(rssArticle.link, body)
                            when (type) {
                                1 -> fragment.showDialogFragment(PhotoDialog(url))
                            }
                        }.onError {
                            AppLog.put("加载为链接的正文失败", it, true)
                        }
                }
            }
        }
    }

    private fun readNoHtml(activity: AppCompatActivity, record: RssReadRecord, type: Int) {
        activity.lifecycleScope.launch {
            val rssSource = withContext(IO) { appDb.rssSourceDao.getByKey(record.origin) }
            rssSource?.let { s ->
                val ruleContent = s.ruleContent
                if (ruleContent.isNullOrBlank()) {
                    when (type) {
                        1 -> activity.showDialogFragment(PhotoDialog(record.record))
                    }
                } else {
                    Rss.getContent(activity.lifecycleScope, record.toRssArticle(), ruleContent, s)
                        .onSuccess(IO) { body ->
                            val url = NetworkUtils.getAbsoluteURL(record.record, body)
                            when (type) {
                                1 -> activity.showDialogFragment(PhotoDialog(url))
                            }
                        }.onError {
                            AppLog.put("加载为链接的正文失败", it, true)
                        }
                }
            }
        }
    }

}
