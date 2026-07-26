package io.legado.app.ui.image

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * 图片浏览状态单例（参考 VideoPlay 机制）
 *
 * 用于在 RssArticlesFragment / RssArticleInfoActivity 与 ImageGalleryActivity 之间
 * 传递文章列表、索引、分页信息，避免 Intent Binder 事务大小限制（1MB）。
 *
 * 生命周期：在 ImageGalleryActivity onDestroy 时调用 clear() 清理引用，避免内存泄漏。
 */
object ImagePlay {
    /** 订阅源文章列表（上下滑动切换文章，从 RssArticlesFragment 传入） */
    var rssArticles: List<RssArticle>? = null
    /** 当前订阅源文章索引（上下滑动切换文章） */
    var rssArticleIndex: Int = 0
    /** 订阅源对象（跨文章切换时调用 Rss.getContentAwait 需要 ruleContent） */
    var rssSource: RssSource? = null
    /** 分页加载：分类名称（从 RssArticlesViewModel 传入） */
    var rssSortName: String? = null
    /** 分页加载：分类URL（从 RssArticlesViewModel 传入） */
    var rssSortUrl: String? = null
    /** 分页加载：下一页URL（Rss.getArticles 返回） */
    var rssNextPageUrl: String? = null
    /** 分页加载：当前页码 */
    var rssArticlePage: Int = 1
    /** 分页加载：是否还有更多文章 */
    var rssArticlesHasMore: Boolean = true
    /** 位置记忆：退出图片浏览时正在看的文章link（RssArticlesFragment onResume 滚动到对应位置） */
    var lastPlayedArticleLink: String? = null
    /** 当前文章的图片URL列表（缓存，避免重复加载） */
    var currentImageUrls: List<String>? = null

    // ==================== V3 B-6 / V4 B-14：垂直画布扩展字段 ====================
    /** 所有文章的图片 URL 列表（V3 B-6 StateFlow 封装，线程安全；含文章分隔符） */
    private val _allImageUrls = MutableStateFlow<List<ImageCanvasItem>>(emptyList())
    val allImageUrls: StateFlow<List<ImageCanvasItem>> = _allImageUrls.asStateFlow()
    /** 已加载完成的文章索引集合（V3 B-6/E3：ConcurrentHashMap.newKeySet 线程安全，协程并发读写安全） */
    val loadedArticleIndices: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    /** 已预加载的文章索引集合（V3 B-6/E3：ConcurrentHashMap.newKeySet 线程安全，协程并发读写安全） */
    val preloadedArticles: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /**
     * 追加图片项到 allImageUrls（V3 B-6 StateFlow 封装，线程安全）
     *
     * @param items 图片项列表（ImageItem 或 ArticleDivider）
     */
    @Synchronized
    fun appendItems(items: List<ImageCanvasItem>) {
        _allImageUrls.update { current -> current + items }
    }

    /**
     * 清理垂直画布状态（allImageUrls / loadedArticleIndices / preloadedArticles）
     *
     * 用于 ImageGalleryActivity.onDestroy 或切换订阅源时清理，避免脏数据残留。
     */
    @Synchronized
    fun clearImageCanvasState() {
        val clearedSize = _allImageUrls.value.size
        _allImageUrls.value = emptyList()
        loadedArticleIndices.clear()
        preloadedArticles.clear()
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_PLAY,
            "clearImageCanvasState: clearedSize=$clearedSize loadedArticleIndicesCleared=true preloadedArticlesCleared=true",
            level = AppLog.Level.INFO
        )
    }

    /**
     * 重置订阅源（切换到新订阅源时调用）
     *
     * 清理垂直画布状态 + 保留 rssSource/rssArticles 等基础字段（由调用方重新设置）
     */
    @Synchronized
    fun resetForNewSource() {
        val clearedSize = _allImageUrls.value.size
        clearImageCanvasState()
        currentImageUrls = null
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_PLAY,
            "resetForNewSource: clearedSize=$clearedSize sourceUrlHash=${rssSource?.sourceUrl?.hashCode() ?: 0}",
            level = AppLog.Level.INFO
        )
    }

    /**
     * 初始化订阅源（进入图片浏览前调用，AD-12 / R1.24 / 6.5.4.2）
     *
     * 流程：
     * 1. 首行调用 resetForNewSource() 清理旧数据（避免跨订阅源切换时脏数据残留）
     * 2. 设置 rssSource / rssArticles / rssArticleIndex 基础字段
     * 3. 设置分页字段默认值
     *
     * @param rssSource 订阅源对象
     * @param rssArticles 订阅源文章列表
     * @param rssArticleIndex 初始文章索引
     * @param rssSortName 分类名称（分页加载用）
     * @param rssSortUrl 分类URL（分页加载用）
     */
    @Synchronized
    fun init(
        rssSource: RssSource,
        rssArticles: List<RssArticle>,
        rssArticleIndex: Int,
        rssSortName: String? = null,
        rssSortUrl: String? = null
    ) {
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_PLAY,
            "init: start sourceUrlHash=${rssSource.sourceUrl.hashCode()} articleCount=${rssArticles.size} initialIndex=$rssArticleIndex",
            level = AppLog.Level.INFO
        )
        resetForNewSource()
        this.rssSource = rssSource
        this.rssArticles = rssArticles
        this.rssArticleIndex = rssArticleIndex
        this.rssSortName = rssSortName
        this.rssSortUrl = rssSortUrl
        this.rssArticlePage = 1
        this.rssArticlesHasMore = true
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_PLAY,
            "init: completed sourceUrlHash=${rssSource.sourceUrl.hashCode()} rssArticleIndex=$rssArticleIndex",
            level = AppLog.Level.INFO
        )
    }

    /**
     * 清理所有引用（在 ImageGalleryActivity onDestroy 时调用，避免内存泄漏）
     */
    fun clear() {
        rssArticles = null
        rssSource = null
        currentImageUrls = null
        // 保留 lastPlayedArticleLink 供 RssArticlesFragment onResume 使用
        // 保留分页字段供下次进入使用（实际上也应该清理，但 VideoPlay 没清理，保持一致）
    }
}
