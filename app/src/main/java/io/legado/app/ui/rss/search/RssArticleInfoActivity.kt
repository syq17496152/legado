package io.legado.app.ui.rss.search

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.ui.theme.LegadoTheme
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 订阅源文章详情页 Activity（rss-unified-search 阶段10 新增，阶段11 重构美化）
 *
 * my-compose-full W6.1：主体 View 体系全量重写为 [RssArticleInfoScreen]
 * （原 ArcView/CardView/SwipeRefresh/RecyclerView/AccentBgTextView 布局与
 * RssArticleInfoSourceAdapter、applyThemeColors 手动取色链删除，配色由 LegadoTheme+
 * AppManagementPalette 统一跟随主题体系）。
 *
 * 数据来源：[RssSearchSourceHolder]（SearchRssArticle 非 Parcelable，无法通过 Intent 传递）
 * - searchArticle: 文章标题/简介/发布时间/图片/类型
 * - articles: 多源映射（用于显示多源列表）
 * - rssArticles: 搜索结果列表转 RssArticle 列表（传给 ReadRss.readRss 支持播放页上下切换）
 *
 * 交互逻辑（与原实现一致）：
 * 1. 多源列表每项点击：选中该源并立即跳阅读页/播放页（传入 rssArticles 支持上下切换）
 * 2. 底部"阅读"：用当前选中源（或默认源）的 RssArticle 跳阅读页/播放页
 * 3. 底部"取消"：finish()
 */
class RssArticleInfoActivity : BaseActivity<ViewBinding>() {

    // W6.1：原 activity_rss_article_info.xml 已删除，改合成 ViewBinding 空壳
    // （对齐 AiImageProviderEditActivity 模式），Compose 全权接管
    override val binding: ViewBinding by lazy {
        object : ViewBinding {
            override fun getRoot(): android.view.View = root
        }
    }

    private val root: FrameLayout by lazy { FrameLayout(this) }

    /**当前选中的源的 origin（sourceUrl），默认取 origins 的第一个**/
    private var selectedOrigin: String? = null

    // W6.1 Compose 桥接状态
    private var titleText by mutableStateOf("")
    private var pubDateText by mutableStateOf("")
    private var typeText by mutableStateOf("")
    private var sourceCountText by mutableStateOf("")
    private var descriptionText by mutableStateOf("")
    private var coverUrl by mutableStateOf<String?>(null)
    private var coverSourceOrigin by mutableStateOf<String?>(null)
    private val sourceItems = mutableStateListOf<RssArticleSourceUi>()
    private var selectedOriginState by mutableStateOf<String?>(null)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        loadData()
    }

    private fun initComposeContent() {
        val container = binding.root as? ViewGroup ?: return
        val cv = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                LegadoTheme {
                    RssArticleInfoScreen(
                        title = titleText,
                        pubDate = pubDateText,
                        typeText = typeText,
                        sourceCountText = sourceCountText,
                        description = descriptionText,
                        coverUrl = coverUrl,
                        coverSourceOrigin = coverSourceOrigin,
                        sources = sourceItems,
                        selectedOrigin = selectedOriginState,
                        onSourceClick = ::onSourceClick,
                        onRead = ::onReadClick,
                        onBack = { finish() }
                    )
                }
            }
        }
        container.addView(cv)
    }

    /**
     * 从 Holder 读取数据并填充 Compose 状态（原 loadData 的状态桥接版）
     */
    private fun loadData() {
        val searchArticle = RssSearchSourceHolder.searchArticle ?: run {
            finish()
            return
        }
        val articlesMap = RssSearchSourceHolder.articles ?: run {
            finish()
            return
        }

        titleText = searchArticle.title
        pubDateText = searchArticle.pubDate?.takeIf { it.isNotBlank() }
            ?: getString(R.string.rss_article_info_no_pubdate)
        descriptionText = searchArticle.description?.takeIf { it.isNotBlank() }
            ?: getString(R.string.rss_article_info_no_description)

        // 文章类型（0=网页, 1=图片, 2=视频）
        typeText = when (searchArticle.type) {
            1 -> getString(R.string.rss_article_type_image)
            2 -> getString(R.string.rss_article_type_video)
            else -> getString(R.string.rss_article_type_web)
        }

        sourceCountText = getString(R.string.rss_source_count_format, articlesMap.size)

        // 封面图：无图时 Screen 不渲染封面区（加载失败由 RssArticleCover 内部隐藏）
        coverUrl = searchArticle.image?.takeIf { it.isNotBlank() }
        // 取默认源的 origin 用于图片加载（部分源需要 referer/cookie）
        coverSourceOrigin = articlesMap.keys.firstOrNull()

        // B1 修复（原铁证保留）：默认选中源用 searchArticle.origins.firstOrNull()
        // （LinkedHashSet 插入顺序），articlesMap 是 HashMap 顺序不保证一致，
        // 错配会导致 VideoPlay.switchToArticle(0) 加载 rssArticles[0] 与选中源不一致而播放失败
        selectedOrigin = searchArticle?.origins?.firstOrNull() ?: articlesMap.keys.firstOrNull()
        selectedOriginState = selectedOrigin
        AppLog.put("RssArticleInfo: selectedOrigin=${selectedOrigin?.take(2)}***, source=origins")

        lifecycleScope.launch {
            val items = withContext(IO) {
                val sourceUrls = articlesMap.keys.toList()
                val rssSources = appDb.rssSourceDao.getRssSources(*sourceUrls.toTypedArray())
                val sourceMap = rssSources.associateBy { it.sourceUrl }
                articlesMap.entries.map { (origin, _) ->
                    RssArticleSourceUi(
                        displayName = sourceMap[origin]?.sourceName ?: origin,
                        origin = origin
                    )
                }
            }
            sourceItems.clear()
            sourceItems.addAll(items)
        }
    }

    private fun onSourceClick(source: RssArticleSourceUi) {
        selectedOriginState = source.origin
        val article = RssSearchSourceHolder.articles?.get(source.origin) ?: return
        // 选中后立即跳阅读页（与书源详情页点击章节即阅读行为一致）
        startRead(article)
    }

    private fun onReadClick() {
        // 用当前选中源（或默认源）的 RssArticle 跳阅读页
        val articlesMap = RssSearchSourceHolder.articles ?: return
        val origin = selectedOrigin ?: articlesMap.keys.firstOrNull() ?: return
        val article = articlesMap[origin] ?: return
        startRead(article)
    }

    /**
     * 跳转阅读页/播放页
     *
     * 传入 RssSearchSourceHolder.rssArticles 支持播放页上/下一个切换文章
     */
    private fun startRead(rssArticle: RssArticle) {
        val rssArticles = RssSearchSourceHolder.rssArticles
        ReadRss.readRss(this, rssArticle, rssArticles = rssArticles)
    }

    override fun onDestroy() {
        // 不清理 Holder：阅读页/播放页 onDestroy 时会清理（ReadRssActivity/VideoPlayerActivity）
        // 详情页跳阅读页后，阅读页内换源对话框仍需读取 Holder.articles 和 Holder.rssArticles
        super.onDestroy()
    }

}
