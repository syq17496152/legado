package io.legado.app.ui.rss.favorites


import android.os.Bundle
import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssStar
import io.legado.app.ui.rss.article.RssArticlesShellFragment
import io.legado.app.ui.rss.article.compose.RssArticleListStateHolder
import io.legado.app.ui.rss.favorites.compose.RssStarComposeList
import io.legado.app.ui.rss.read.ReadRss
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

// CE-b：原 fragment_rss_articles.xml 已退役 ⇒ 继承共享合成壳基类（与 RssArticlesFragment 同源）
// CF 6.2：原 `RssFavoritesAdapter` + `item_rss_article.xml` 换装 Compose（行实现与文章列表样式 0 共用单源）
class RssFavoritesFragment() : RssArticlesShellFragment<RssFavoritesViewModel>() {

    constructor(group: String) : this() {
        arguments = Bundle().apply {
            putString("group", group)
        }
    }

    override val viewModel by viewModels<RssFavoritesViewModel>()
    private val listStateHolder by lazy { RssArticleListStateHolder(0) }
    /** 收藏列表数据（Compose 路径唯一数据源） */
    private val stars = mutableStateOf<List<RssStar>>(emptyList())

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        loadArticles()
    }

    private fun initView() = run {
        refreshLayout.isEnabled = false
        installComposeList {
            RssStarComposeList(
                items = stars.value,
                stateHolder = listStateHolder,
                bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                onItemClick = { readRss(it) },
                onItemLongClick = { delStar(it) },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    private fun loadArticles() {
        lifecycleScope.launch {
            val group = arguments?.getString("group") ?: "默认分组"
            appDb.rssStarDao.flowByGroup(group).catch {
                AppLog.put("订阅文章界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                stars.value = it
            }
        }
    }

    fun readRss(rssStar: RssStar) {
        ReadRss.readRss(this, rssStar.toRssArticle())
    }

    fun delStar(rssStar: RssStar) {
        (activity as? RssFavoritesActivity)?.confirmDeleteStar(rssStar)
    }
}