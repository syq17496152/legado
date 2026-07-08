package io.legado.app.ui.book.source.manage

import android.content.Context
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.ItemBookSourceGridBinding

/**
 * source-layout-deep-refactor 书源网格适配器
 *
 * 卡片显示：首字封面 + 名称 + 启用指示点。
 * 简化说明：不支持拖拽排序、选择模式和调试信息 | 已知上限：网格模式下批量操作需切换回列表模式 | 升级路径：为网格适配器添加 LongClick 进入选择模式
 */
class BookSourceAdapterGrid(
    context: Context,
    private val callBack: BookSourceAdapter.CallBack
) : RecyclerAdapter<BookSourcePart, ItemBookSourceGridBinding>(context) {

    val diffItemCallback = object : DiffUtil.ItemCallback<BookSourcePart>() {
        override fun areItemsTheSame(oldItem: BookSourcePart, newItem: BookSourcePart): Boolean =
            oldItem.bookSourceUrl == newItem.bookSourceUrl

        override fun areContentsTheSame(oldItem: BookSourcePart, newItem: BookSourcePart): Boolean =
            oldItem.bookSourceName == newItem.bookSourceName
                    && oldItem.enabled == newItem.enabled
    }

    override fun getViewBinding(parent: android.view.ViewGroup): ItemBookSourceGridBinding {
        return ItemBookSourceGridBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookSourceGridBinding,
        item: BookSourcePart,
        payloads: MutableList<Any>
    ) {
        binding.run {
            tvSourceName.text = item.bookSourceName
            tvSourceInitial.text = item.bookSourceName.firstOrNull()?.toString() ?: ""
            // 启用状态指示点：启用=绿色可见，禁用=隐藏
            vEnabledDot.isVisible = item.enabled
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBookSourceGridBinding) {
        binding.apply {
            root.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.edit(it)
                }
            }
            root.setOnLongClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.enable(!it.enabled, it)
                }
                true
            }
        }
    }
}
