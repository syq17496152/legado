package io.legado.app.ui.book.source.manage

import android.content.Context
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.ItemBookSourceCompactBinding
import io.legado.app.help.source.sourceInitial
import io.legado.app.help.source.sourceUrlHost
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.utils.ColorUtils
import java.util.Collections

/**
 * source-layout-deep-refactor 书源紧凑列表适配器
 *
 * 单行显示：名称 + 类型徽章 + 启用开关。
 * M-01 修复：已添加 selection 机制（选择/批量操作在紧凑模式下生效）。
 * 简化说明：不支持拖拽排序和调试信息 | 已知上限：无法查看URL和校验结果 | 升级路径：长按切换到完整列表模式
 */
class BookSourceAdapterCompact(
    context: Context,
    private val callBack: BookSourceAdapter.CallBack
) : RecyclerAdapter<BookSourcePart, ItemBookSourceCompactBinding>(context),
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
                    && oldItem.bookSourceType == newItem.bookSourceType
                    && oldItem.lastHost == newItem.lastHost  // ADR-7/A5: Compact 无 getChangePayload，靠全量刷新
    }

    override fun getViewBinding(parent: android.view.ViewGroup): ItemBookSourceCompactBinding {
        return ItemBookSourceCompactBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookSourceCompactBinding,
        item: BookSourcePart,
        payloads: MutableList<Any>
    ) {
        binding.run {
            if (payloads.isEmpty()) {
                root.setBackgroundColor(ColorUtils.withAlpha(context.backgroundColor, 0.5f))
                cbBookSource.text = item.getDisPlayNameGroup()
                swtEnabled.isChecked = item.enabled
                cbBookSource.isChecked = selected.contains(item)
                // Issue-6 新增控件绑定（紧凑模式无 getChangePayload，全量分支绑定即可，ADR-7/A5）
                tvSourceInitial.text = item.sourceInitial()
                tvBookSourceUrl.text = item.sourceUrlHost()
                vEnabledDot.isVisible = item.enabled
                // 类型徽章：0=文本, 1=音频, 2=图片, 3=文件, 4=视频
                ivTypeBadge.text = when (item.bookSourceType) {
                    0 -> context.getString(R.string.type_text)
                    1 -> context.getString(R.string.type_audio)
                    2 -> context.getString(R.string.type_image)
                    3 -> context.getString(R.string.type_file)
                    4 -> context.getString(R.string.type_video)
                    else -> context.getString(R.string.type_text)
                }
                ivTypeBadge.isVisible = item.bookSourceType != 0
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "selected" -> cbBookSource.isChecked = selected.contains(item)
                        }
                    }
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBookSourceCompactBinding) {
        binding.apply {
            swtEnabled.setOnUserCheckedChangeListener { checked ->
                getItem(holder.layoutPosition)?.let {
                    it.enabled = checked
                    callBack.enable(checked, it)
                }
            }
            // M-01 修复：cbBookSource 改为选择功能（与 list adapter 一致）
            cbBookSource.setOnUserCheckedChangeListener { checked ->
                getItem(holder.layoutPosition)?.let {
                    if (checked) selected.add(it) else selected.remove(it)
                    callBack.upCountView()
                }
            }
            // M-01 修复：编辑功能移到 root 点击
            root.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.edit(it)
                }
            }
            root.setOnLongClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.del(it)
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
