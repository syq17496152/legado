package io.legado.app.ui.rss.search

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemRssReadRecordBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 订阅源搜索结果换源对话框（rss-unified-search 新增）
 *
 * 参考 [io.legado.app.ui.rss.article.ReadRecordDialog] 的结构：
 * - 继承 BaseDialogFragment(R.layout.dialog_recycler_view)
 * - 内部 RecyclerView + Adapter 显示源列表
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
class ChangeRssArticleSourceDialog : BaseDialogFragment(R.layout.dialog_recycler_view),
    Toolbar.OnMenuItemClickListener {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val adapter by lazy { ChangeRssSourceAdapter(requireContext()) }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.run {
            toolBar.setBackgroundColor(primaryColor)
            toolBar.setTitle(R.string.change_source)
            recyclerView.layoutManager = LinearLayoutManager(requireContext())
            recyclerView.adapter = adapter
        }
        loadData()
        adapter.setOnSourceClickListener(object : ChangeRssSourceAdapter.OnSourceClickListener {
            override fun onSourceClick(item: ChangeRssSourceAdapter.SourceItem?) {
                item?.let { sourceItem ->
                    val activity = activity as? AppCompatActivity ?: return@let
                    // 更新 Holder，让新的详情页可继续换源
                    RssSearchSourceHolder.articles = hashMapOf(sourceItem.origin to sourceItem.rssArticle)
                    // 调用 ReadRss.readRss 重新加载（使用 Activity 重载方法）
                    // 传入搜索结果列表 rssArticles，支持播放页上/下一个切换文章（废除 AD-07 简化原则）
                    ReadRss.readRss(
                        activity,
                        sourceItem.rssArticle,
                        rssArticles = RssSearchSourceHolder.rssArticles
                    )
                    dismiss()
                }
            }
        })
    }

    private fun loadData() {
        val articlesMap = RssSearchSourceHolder.articles
        if (articlesMap.isNullOrEmpty()) {
            dismiss()
            return
        }
        lifecycleScope.launch {
            val items = withContext(IO) {
                val sourceUrls = articlesMap.keys.toList()
                val rssSources = appDb.rssSourceDao.getRssSources(*sourceUrls.toTypedArray())
                val sourceMap = rssSources.associateBy { it.sourceUrl }
                articlesMap.entries.map { (origin, article) ->
                    ChangeRssSourceAdapter.SourceItem(
                        rssSource = sourceMap[origin],
                        rssArticle = article,
                        origin = origin
                    )
                }
            }
            adapter.setItems(items)
        }
    }

    override fun onMenuItemClick(item: android.view.MenuItem?): Boolean {
        return true
    }

}

/**
 * 换源列表 Adapter
 *
 * 复用 [ItemRssReadRecordBinding]（双行布局：title + record）
 * - textTitle 显示 sourceName（查询不到则显示 origin）
 * - textRecord 显示 origin（sourceUrl）
 */
class ChangeRssSourceAdapter(context: Context) :
    RecyclerAdapter<ChangeRssSourceAdapter.SourceItem, ItemRssReadRecordBinding>(context) {

    private var clickListener: OnSourceClickListener? = null

    fun setOnSourceClickListener(listener: OnSourceClickListener) {
        this.clickListener = listener
    }

    override fun getViewBinding(parent: ViewGroup): ItemRssReadRecordBinding {
        return ItemRssReadRecordBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssReadRecordBinding,
        item: SourceItem,
        payloads: MutableList<Any>
    ) {
        binding.run {
            // 优先显示 sourceName，查询不到则显示 origin
            textTitle.text = item.rssSource?.sourceName ?: item.origin
            textRecord.text = item.origin
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssReadRecordBinding) {
        binding.root.setOnClickListener {
            clickListener?.onSourceClick(getItem(holder.bindingAdapterPosition))
        }
    }

    data class SourceItem(
        val rssSource: RssSource?,
        val rssArticle: RssArticle,
        val origin: String
    )

    interface OnSourceClickListener {
        fun onSourceClick(item: SourceItem?)
    }

}
