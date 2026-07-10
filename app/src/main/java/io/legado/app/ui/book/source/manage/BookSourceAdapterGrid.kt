package io.legado.app.ui.book.source.manage

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.ItemBookSourceGridBinding
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import java.util.Collections

/**
 * source-layout-deep-refactor 书源网格适配器
 *
 * 卡片显示：首字封面 + 名称 + 启用指示点。
 * M-01 修复：已添加 selection 机制（长按选择 + 滑动多选），选中状态用封面半透明遮罩表示。
 * 简化说明：不支持拖拽排序和调试信息 | 已知上限：网格模式下无法直接切换启用/禁用（需切回列表模式） | 升级路径：M-10 提取基类统一交互
 */
class BookSourceAdapterGrid(
    context: Context,
    private val callBack: BookSourceAdapter.CallBack
) : RecyclerAdapter<BookSourcePart, ItemBookSourceGridBinding>(context),
    BookSourceSelection {

    private val selected = linkedSetOf<BookSourcePart>()

    override val selection: List<BookSourcePart>
        get() = getItems().filter { selected.contains(it) }

    override val dragSelectCallback: DragSelectTouchHelper.Callback =
        object : DragSelectTouchHelper.AdvanceCallback<BookSourcePart>(Mode.ToggleAndReverse) {
            override fun currentSelectedId(): MutableSet<BookSourcePart> = selected
            override fun getItemId(position: Int): BookSourcePart = getItem(position)!!
            override fun updateSelectState(position: Int, isSelected: Boolean): Boolean {
                getItem(position)?.let {
                    if (isSelected) selected.add(it) else selected.remove(it)
                    notifyItemChanged(position, bundleOf("selected" to null))
                    callBack.upCountView()
                    return true
                }
                return false
            }
        }

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

    // M-01 修复：选中状态用封面半透明蓝色遮罩表示
    private fun setSelectedVisual(binding: ItemBookSourceGridBinding, item: BookSourcePart) {
        binding.ivSourceCover.backgroundTintList =
            if (selected.contains(item)) ColorStateList.valueOf(Color.argb(120, 33, 150, 243))
            else null
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookSourceGridBinding,
        item: BookSourcePart,
        payloads: MutableList<Any>
    ) {
        binding.run {
            if (payloads.isEmpty()) {
                tvSourceName.text = item.bookSourceName
                tvSourceInitial.text = item.bookSourceName.firstOrNull()?.toString() ?: ""
                vEnabledDot.isVisible = item.enabled
                setSelectedVisual(binding, item)
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "selected" -> setSelectedVisual(binding, item)
                        }
                    }
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBookSourceGridBinding) {
        binding.apply {
            root.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.edit(it)
                }
            }
            // M-01 修复：root 长按改为选择切换（改自启用/禁用）
            root.setOnLongClickListener {
                getItem(holder.layoutPosition)?.let {
                    if (selected.contains(it)) selected.remove(it) else selected.add(it)
                    notifyItemChanged(holder.layoutPosition, bundleOf("selected" to null))
                    callBack.upCountView()
                }
                true
            }
        }
    }

    override fun selectAll() {
        getItems().forEach { selected.add(it) }
        notifyItemRangeChanged(0, itemCount, bundleOf("selected" to null))
        callBack.upCountView()
    }

    override fun revertSelection() {
        getItems().forEach {
            if (selected.contains(it)) selected.remove(it) else selected.add(it)
        }
        notifyItemRangeChanged(0, itemCount, bundleOf("selected" to null))
        callBack.upCountView()
    }

    override fun checkSelectedInterval() {
        val selectedPosition = linkedSetOf<Int>()
        getItems().forEachIndexed { index, it ->
            if (selected.contains(it)) selectedPosition.add(index)
        }
        if (selectedPosition.isEmpty()) return
        val minPosition = Collections.min(selectedPosition)
        val maxPosition = Collections.max(selectedPosition)
        val itemCount = maxPosition - minPosition + 1
        for (i in minPosition..maxPosition) {
            getItem(i)?.let { selected.add(it) }
        }
        notifyItemRangeChanged(minPosition, itemCount, bundleOf("selected" to null))
        callBack.upCountView()
    }
}
