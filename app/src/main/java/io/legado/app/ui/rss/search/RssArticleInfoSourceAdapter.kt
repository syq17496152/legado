package io.legado.app.ui.rss.search

import android.content.Context
import android.view.ViewGroup
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.ItemRssArticleInfoSourceBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.getCompatColor

/**
 * 文章详情页多源列表 Adapter（rss-unified-search 阶段10 新增，阶段11 重构美化）
 *
 * 阶段11 重构：
 * - 复用专用布局 [ItemRssArticleInfoSourceBinding]（左选中图标 + 源名称 + origin）
 * - 选中状态用 ic_check 图标 + 主题色文字（替代原 ✓ 前缀）
 *
 * 参考 [ChangeRssSourceAdapter] 的结构，差异：
 * - 增加 isSelected 字段标记当前选中源
 * - 增加 setSelected 方法更新选中状态
 */
class RssArticleInfoSourceAdapter(context: Context) :
    RecyclerAdapter<RssArticleInfoSourceAdapter.SourceItem, ItemRssArticleInfoSourceBinding>(context) {

    private var clickListener: OnSourceClickListener? = null
    private var selectedOrigin: String? = null

    fun setOnSourceClickListener(listener: OnSourceClickListener) {
        this.clickListener = listener
    }

    /**
     * 更新选中状态（通知 UI 刷新）
     */
    fun setSelected(origin: String?) {
        if (selectedOrigin == origin) return
        selectedOrigin = origin
        notifyItemRangeChanged(0, itemCount)
    }

    override fun getViewBinding(parent: ViewGroup): ItemRssArticleInfoSourceBinding {
        return ItemRssArticleInfoSourceBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssArticleInfoSourceBinding,
        item: SourceItem,
        payloads: MutableList<Any>
    ) {
        binding.run {
            // 优先显示 sourceName，查询不到则显示 origin
            val displayName = item.rssSource?.sourceName ?: item.origin
            val isSelected = item.origin == selectedOrigin
            tvSourceName.text = displayName
            tvSourceName.setTextColor(
                if (isSelected) context.accentColor
                else context.getCompatColor(R.color.primaryText)
            )
            tvSourceOrigin.text = item.origin
            // 选中时显示 ic_check 主题色图标，未选中时隐藏
            ivChecked.isVisible = isSelected
            if (isSelected) {
                ivChecked.setColorFilter(context.accentColor)
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssArticleInfoSourceBinding) {
        binding.root.setOnClickListener {
            clickListener?.onSourceClick(getItem(holder.bindingAdapterPosition))
        }
    }

    data class SourceItem(
        val rssSource: RssSource?,
        val rssArticle: RssArticle,
        val origin: String,
        val isSelected: Boolean = false
    )

    interface OnSourceClickListener {
        fun onSourceClick(item: SourceItem?)
    }

}
