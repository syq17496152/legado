package io.legado.app.ui.rss.source.manage

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.ItemRssSourceGridBinding
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import java.util.Collections

/**
 * source-layout-deep-refactor 订阅源网格适配器
 *
 * 卡片显示：首字封面 + 名称 + 启用指示点。
 * M-02 修复：已添加 selection 机制（长按选择 + 滑动多选），选中状态用封面半透明遮罩表示。
 * 简化说明：不支持拖拽排序和调试信息 | 已知上限：网格模式下无法直接切换启用/禁用（需切回列表模式） | 升级路径：M-10 提取基类统一交互
 */
class RssSourceAdapterGrid(
    context: Context,
    private val callBack: RssSourceAdapter.CallBack
) : RecyclerAdapter<RssSource, ItemRssSourceGridBinding>(context),
    RssSourceSelection {

    private val selected = linkedSetOf<RssSource>()

    override val selection: List<RssSource>
        get() = getItems().filter { selected.contains(it) }

    override val dragSelectCallback: DragSelectTouchHelper.Callback =
        object : DragSelectTouchHelper.AdvanceCallback<RssSource>(Mode.ToggleAndReverse) {
            override fun currentSelectedId(): MutableSet<RssSource> = selected
            override fun getItemId(position: Int): RssSource = getItem(position)!!
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

    // M-02 修复：选中状态用封面半透明蓝色遮罩表示
    private fun setSelectedVisual(binding: ItemRssSourceGridBinding, item: RssSource) {
        binding.ivSourceCover.backgroundTintList =
            if (selected.contains(item)) ColorStateList.valueOf(Color.argb(120, 33, 150, 243))
            else null
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssSourceGridBinding,
        item: RssSource,
        payloads: MutableList<Any>
    ) {
        binding.run {
            if (payloads.isEmpty()) {
                tvSourceName.text = item.sourceName
                tvSourceInitial.text = item.sourceName.firstOrNull()?.toString() ?: ""
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

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssSourceGridBinding) {
        binding.apply {
            root.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.edit(it)
                }
            }
            // M-02 修复：root 长按改为选择切换（改自启用/禁用）
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
        // M-02 修复：空判保护，避免无选中项时 Collections.min/max 抛异常
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
