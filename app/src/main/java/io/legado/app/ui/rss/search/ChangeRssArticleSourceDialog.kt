package io.legado.app.ui.rss.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionRow
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 订阅源搜索结果换源对话框（rss-unified-search 新增）
 *
 * 迁移说明：原 BaseDialogFragment(R.layout.dialog_recycler_view) + RecyclerView + Adapter
 * 已迁移为 ComposeDialogFragment + AppDialogFrame + LazyColumn，数据加载与点击交互保持等价。
 *
 * 数据来源：[RssSearchSourceHolder.articles]（多源映射）
 *
 * 交互逻辑：
 * 1. 启动时异步查询所有源的 RssSource 信息（获取 sourceName）
 * 2. 列表显示每个源的 sourceName + origin（sourceUrl）
 * 3. 点击某项 → 取出对应的 RssArticle → 调用 [ReadRss.readRss] 重新加载
 *
 * 设计依据：rss-unified-search design.md §5
 */
class ChangeRssArticleSourceDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private var sourceItems by mutableStateOf(emptyList<SourceItem>())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    val style = rememberAppDialogStyle()
                    val palette = style.toMiuixPalette()
                    LaunchedEffect(Unit) {
                        loadData()
                    }
                    AppDialogFrame(
                        title = stringResource(R.string.change_source),
                        scrollContent = false,
                        content = {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(sourceItems) { item ->
                                    LegadoMiuixActionRow(
                                        text = item.rssSource?.sourceName ?: item.origin,
                                        description = item.origin,
                                        palette = palette,
                                        onClick = { onSourceClick(item) }
                                    )
                                }
                            }
                        },
                        actions = {}
                    )
                }
            }
        }
    }

    private fun loadData() {
        val articlesMap = RssSearchSourceHolder.articles
        if (articlesMap.isNullOrEmpty()) {
            dismissAllowingStateLoss()
            return
        }
        lifecycleScope.launch {
            val items = withContext(IO) {
                val sourceUrls = articlesMap.keys.toList()
                val rssSources = appDb.rssSourceDao.getRssSources(*sourceUrls.toTypedArray())
                val sourceMap = rssSources.associateBy { it.sourceUrl }
                articlesMap.entries.map { (origin, article) ->
                    SourceItem(
                        rssSource = sourceMap[origin],
                        rssArticle = article,
                        origin = origin
                    )
                }
            }
            sourceItems = items
        }
    }

    private fun onSourceClick(item: SourceItem) {
        val activity = activity as? AppCompatActivity ?: return
        // 更新 Holder，让新的详情页可继续换源
        RssSearchSourceHolder.articles = hashMapOf(item.origin to item.rssArticle)
        // 调用 ReadRss.readRss 重新加载（使用 Activity 重载方法）
        // 传入搜索结果列表 rssArticles，支持播放页上/下一个切换文章（废除 AD-07 简化原则）
        ReadRss.readRss(
            activity,
            item.rssArticle,
            rssArticles = RssSearchSourceHolder.rssArticles
        )
        dismissAllowingStateLoss()
    }

    /**
     * 换源列表条目（原 ChangeRssSourceAdapter.SourceItem，迁移后保留同结构）
     * - rssSource 查询不到时显示 origin
     */
    private data class SourceItem(
        val rssSource: RssSource?,
        val rssArticle: RssArticle,
        val origin: String
    )

}
