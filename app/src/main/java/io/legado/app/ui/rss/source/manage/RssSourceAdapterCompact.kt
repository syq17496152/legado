package io.legado.app.ui.rss.source.manage

import android.content.Context
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.ItemRssSourceCompactBinding
import io.legado.app.help.source.sourceInitial
import io.legado.app.help.source.sourceUrlHost
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.utils.ColorUtils
import java.util.Collections

/**
 * source-layout-deep-refactor 订阅源紧凑列表适配器
 *
 * 单行显示：名称 + 类型徽章 + 启用开关。
 * M-02 修复：已添加 selection 机制（cb 选择 + 滑动多选），与 list adapter 一致。
 * 简化说明：不支持拖拽排序 | 已知上限：无法查看URL | 升级路径：M-10 提取基类统一交互
 */
class RssSourceAdapterCompact(
    context: Context,
    private val callBack: RssSourceAdapter.CallBack
) : RecyclerAdapter<RssSource, ItemRssSourceCompactBinding>(context),
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
                    && oldItem.type == newItem.type
                    && oldItem.lastHost == newItem.lastHost  // ADR-7/A5: Compact 无 getChangePayload，靠全量刷新
    }

    override fun getViewBinding(parent: android.view.ViewGroup): ItemRssSourceCompactBinding {
        return ItemRssSourceCompactBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssSourceCompactBinding,
        item: RssSource,
        payloads: MutableList<Any>
    ) {
        binding.run {
            if (payloads.isEmpty()) {
                root.setBackgroundColor(ColorUtils.withAlpha(context.backgroundColor, 0.5f))
                cbSource.text = item.getDisplayNameGroup()
                swtEnabled.isChecked = item.enabled
                // M-02 修复：cb 显示选中状态
                cbSource.isChecked = selected.contains(item)
                // Issue-6 新增控件绑定（紧凑模式无 getChangePayload，全量分支绑定即可，ADR-7/A5）
                tvSourceInitial.text = item.sourceInitial()
                tvRssSourceUrl.text = item.sourceUrlHost()
                vEnabledDot.isVisible = item.enabled
                // 类型徽章：0=网页, 1=图片, 2=视频
                ivTypeBadge.text = when (item.type) {
                    0 -> context.getString(R.string.type_web)
                    1 -> context.getString(R.string.type_image)
                    2 -> context.getString(R.string.type_video)
                    else -> context.getString(R.string.type_web)
                }
                ivTypeBadge.isVisible = item.type != 0
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "selected" -> cbSource.isChecked = selected.contains(item)
                        }
                    }
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssSourceCompactBinding) {
        binding.apply {
            swtEnabled.setOnUserCheckedChangeListener { checked ->
                getItem(holder.layoutPosition)?.let {
                    it.enabled = checked
                    callBack.update(it)
                }
            }
            // M-02 修复：cbSource 改为选择功能（与 list adapter 一致）
            cbSource.setOnUserCheckedChangeListener { checked ->
                getItem(holder.layoutPosition)?.let {
                    if (checked) selected.add(it) else selected.remove(it)
                    callBack.upCountView()
                }
            }
            // M-02 修复：编辑功能移到 root 点击
            root.setOnClickListener {
                getItem(holder.layoutPosition)?.let { callBack.edit(it) }
            }
            root.setOnLongClickListener {
                getItem(holder.layoutPosition)?.let { callBack.del(it) }
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
