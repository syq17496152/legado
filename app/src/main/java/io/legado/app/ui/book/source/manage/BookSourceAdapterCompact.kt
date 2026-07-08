package io.legado.app.ui.book.source.manage

import android.content.Context
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.ItemBookSourceCompactBinding
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.utils.ColorUtils

/**
 * source-layout-deep-refactor 书源紧凑列表适配器
 *
 * 单行显示：名称 + 类型徽章 + 启用开关。
 * 简化说明：不支持拖拽排序和调试信息 | 已知上限：无法查看URL和校验结果 | 升级路径：长按切换到完整列表模式
 */
class BookSourceAdapterCompact(
    context: Context,
    private val callBack: BookSourceAdapter.CallBack
) : RecyclerAdapter<BookSourcePart, ItemBookSourceCompactBinding>(context) {

    val diffItemCallback = object : DiffUtil.ItemCallback<BookSourcePart>() {
        override fun areItemsTheSame(oldItem: BookSourcePart, newItem: BookSourcePart): Boolean =
            oldItem.bookSourceUrl == newItem.bookSourceUrl

        override fun areContentsTheSame(oldItem: BookSourcePart, newItem: BookSourcePart): Boolean =
            oldItem.bookSourceName == newItem.bookSourceName
                    && oldItem.enabled == newItem.enabled
                    && oldItem.bookSourceType == newItem.bookSourceType
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
            root.setBackgroundColor(ColorUtils.withAlpha(context.backgroundColor, 0.5f))
            cbBookSource.text = item.getDisPlayNameGroup()
            swtEnabled.isChecked = item.enabled
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
            cbBookSource.setOnClickListener {
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
}
