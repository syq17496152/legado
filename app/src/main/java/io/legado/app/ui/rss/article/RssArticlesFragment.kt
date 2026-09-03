package io.legado.app.ui.rss.article

import android.os.Bundle
import android.view.View
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.data.entities.RssArticle
import io.legado.app.model.VideoPlay
import io.legado.app.ui.image.ImagePlay
import io.legado.app.ui.main.rss.RssFragment
import io.legado.app.ui.rss.article.compose.ListBottomInset
import io.legado.app.ui.rss.article.compose.RssArticleListBridge
import io.legado.app.ui.rss.article.compose.RssArticleListState
import io.legado.app.ui.rss.article.compose.toRssArticleListStyle
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.ui.theme.LegadoTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * D4 §3.4 兼容层壳（modern 嵌入专用，design-b3-d4-flagship）：
 * childFragmentManager 契约不变（renderCurrentSort 零改动），壳内 ComposeView 承载
 * RssArticleListBridge；壳仅保留参数、VM 桥、宿主回调、ReadRss/定位联动。
 * View 侧五代 Adapter 家族随批 3 删除（§6）。
 */
class RssArticlesFragment() : VMBaseFragment<RssArticlesViewModel>(R.layout.fragment_rss_articles) {

    constructor(sortName: String, sortUrl: String, searchKey: String?) : this() {
        arguments = Bundle().apply {
            putString("sortName", sortName)
            putString("sortUrl", sortUrl)
            putString("searchKey", searchKey)
        }
    }

    // modern-rss: 嵌入 RssFragment（新版订阅）时取父 Fragment 作用域 RssSortViewModel，其余（RssSortActivity）取 Activity 作用域
    private val activityViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(parentFragment ?: requireActivity())[RssSortViewModel::class.java]
    }
    override val viewModel by viewModels<RssArticlesViewModel>()
    private val isPreload by lazy { activityViewModel.rssSource?.preload ?: false }
    private val embeddedInModernRss: Boolean
        get() = parentFragment is RssFragment

    /** §3.4 兼容层：Compose state holder（组合期就绪；setTopOverlaySpace 先到时经 snapshotState 驱动重组） */
    internal var listState: RssArticleListState? = null
        private set

    /** 顶部覆盖顶栏占位（snapshotState 承载：View 侧写入即驱动 Compose 重组，对齐原 view?.post 回放语义） */
    private val topOverlaySpacePx = mutableStateOf(0)

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.init(arguments)
        viewModel.bindOrigin(activityViewModel.url)
        setupComposeContent(view)
        scheduleInitialLoad()
    }

    private fun setupComposeContent(view: View) {
        val composeView = view.findViewById<ComposeView>(R.id.recycler_view) ?: return
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            LegadoTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                // modern 嵌入：VM 驱动的初始/登录刷新圈不显示（对齐原 isRefreshing = !embeddedInModernRss）；
                // 手势下拉经 pullRefreshing 展示（对齐 SwipeRefreshLayout 自动刷新语义）。
                // design §3.4 注意②：嵌入态语义由 isRefreshing 参数控制，组件不感知 parentFragment
                var pullRefreshing by remember { mutableStateOf(false) }
                LaunchedEffect(uiState.isRefreshing) {
                    if (!uiState.isRefreshing) pullRefreshing = false
                }
                RssArticleListBridge(
                    viewModel = viewModel,
                    style = remember { (activityViewModel.articleStyle ?: 0).toRssArticleListStyle() },
                    isRefreshing = pullRefreshing || (uiState.isRefreshing && !embeddedInModernRss),
                    bottomInset = if (embeddedInModernRss) {
                        ListBottomInset.MAIN_BOTTOM_BAR
                    } else {
                        ListBottomInset.NAVIGATION_BARS
                    },
                    topOverlaySpacePx = topOverlaySpacePx.value,
                    onHostStateReady = { listState = it },
                    onHostStateDisposed = { listState = null },
                    onLoadMore = ::loadMoreArticles,
                    onRefresh = {
                        pullRefreshing = true
                        loadArticles()
                    },
                    onArticleClick = ::readArticle,
                )
            }
        }
    }

    /** 初始加载时机：preload 旁路立即加载；否则首次 RESUMED 一次性触发（对齐原 repeatOnLifecycle+cancel） */
    private fun scheduleInitialLoad() {
        if (isPreload) {
            view?.post {
                loadArticles()
            }
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                loadArticles()
                this@launch.cancel()
            }
        } //只刷新可见页面,非预加载时使用
    }

    /** modern-rss: 供 RssFragment（新版订阅）设置顶部覆盖顶栏占位空间（调用点签名不变，§3.4） */
    fun setTopOverlaySpace(space: Int, overlay: Boolean) {
        topOverlaySpacePx.value = if (embeddedInModernRss) space else 0
    }

    /** 供 RssFragment.gotoTop 定位（§3.4 新 API；currentRssScrollTarget findViewById 兜底随 B5 移除） */
    fun scrollToTop() {
        listState?.requestScrollToTop()
    }

    /** 供 RssSortActivity 登录后刷新当前列表 */
    fun refreshAfterLogin() {
        loadArticles()
    }

    private fun loadArticles() {
        activityViewModel.rssSource?.let {
            viewModel.loadArticles(it)
        }
    }

    private fun loadMoreArticles() {
        if (viewModel.isLoading) return
        activityViewModel.rssSource?.let {
            viewModel.loadMore(it)
        }
    }

    private fun readArticle(rssArticle: RssArticle) {
        // 传递文章列表给播放器，支持上下滑动切换文章（video-article-swipe-switch spec）
        ReadRss.readRss(
            this, rssArticle, activityViewModel.rssSource, listState?.articles.orEmpty(),
            sortName = viewModel.sortName,
            sortUrl = viewModel.sortUrl,
            nextPageUrl = viewModel.nextPageUrl,
            page = viewModel.page
        )
    }

    override fun onResume() {
        super.onResume()
        // 阶段8 F11 / image-gallery-activity：从播放器/图片浏览器返回时一次性定位
        //（原 scrollToPosition 语义 → state.requestScrollToLink，由 ScrollRestoreEffect 消费）
        VideoPlay.lastPlayedArticleLink?.let { link ->
            VideoPlay.lastPlayedArticleLink = null  // 一次性使用，清除标记
            listState?.requestScrollToLink(link)
        }
        ImagePlay.lastPlayedArticleLink?.let { link ->
            ImagePlay.lastPlayedArticleLink = null  // 一次性使用，清除标记
            listState?.requestScrollToLink(link)
        }
    }
}
