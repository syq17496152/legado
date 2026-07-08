package io.legado.app.ui.rss.source.manage

import android.content.Context
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.ItemRssSourceGridBinding

/**
 * source-layout-deep-refactor 订阅源网格适配器
 *
 * 卡片显示：首字封面 + 名称 + 启用指示点。
 * 简化说明：不支持拖拽排序、选择模式 | 已知上限：网格模式下批量操作需切换回列表模式 | 升级路径：为网格适配器添加 LongClick 进入选择模式
 */
class RssSourceAdapterGrid(
    context: Context,
    private val callBack: RssSourceAdapter.CallBack
) : RecyclerAdapter<RssSource, ItemRssSourceGridBinding>(context) {

    val diffItemCallback = object : DiffUtil.ItemCallback<RssSource>() {
        override fun areItemsTheSame(oldItem: RssSource, newItem: RssSource): Boolean =
            oldItem.sourceUrl == newItem.sourceUrl

        override fun areContentsTheSame(oldItem: RssSource, newItem: RssSource): Boolean =
            oldItem.sourceName == newItem.sourceName
                    && oldItem.enabled == newItem.enabled
    }

    override fun getViewBinding(parent: android.view.ViewGroup): ItemRssSourceGridBinding {
        return ItemRssSourceGridBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssSourceGridBinding,
        item: RssSource,
        payloads: MutableList<Any>
    ) {
        binding.run {
            tvSourceName.text = item.sourceName
            tvSourceInitial.text = item.sourceName.firstOrNull()?.toString() ?: ""
            vEnabledDot.isVisible = item.enabled
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssSourceGridBinding) {
        binding.apply {
            root.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.edit(it)
                }
            }
            root.setOnLongClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.update(it.copy(enabled = !it.enabled))
                }
                true
            }
        }
    }
}
