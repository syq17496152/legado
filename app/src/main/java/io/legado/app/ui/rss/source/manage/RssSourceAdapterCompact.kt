package io.legado.app.ui.rss.source.manage

import android.content.Context
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.ItemRssSourceCompactBinding
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.utils.ColorUtils

/**
 * source-layout-deep-refactor 订阅源紧凑列表适配器
 *
 * 单行显示：名称 + 类型徽章 + 启用开关。
 * 简化说明：不支持拖拽排序 | 已知上限：无法查看URL | 升级路径：长按切换到完整列表模式
 */
class RssSourceAdapterCompact(
    context: Context,
    private val callBack: RssSourceAdapter.CallBack
) : RecyclerAdapter<RssSource, ItemRssSourceCompactBinding>(context) {

    val diffItemCallback = object : DiffUtil.ItemCallback<RssSource>() {
        override fun areItemsTheSame(oldItem: RssSource, newItem: RssSource): Boolean =
            oldItem.sourceUrl == newItem.sourceUrl

        override fun areContentsTheSame(oldItem: RssSource, newItem: RssSource): Boolean =
            oldItem.sourceName == newItem.sourceName
                    && oldItem.enabled == newItem.enabled
                    && oldItem.type == newItem.type
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
            root.setBackgroundColor(ColorUtils.withAlpha(context.backgroundColor, 0.5f))
            cbSource.text = item.getDisplayNameGroup()
            swtEnabled.isChecked = item.enabled
            // 类型徽章：0=网页, 1=图片, 2=视频
            ivTypeBadge.text = when (item.type) {
                0 -> context.getString(R.string.type_web)
                1 -> context.getString(R.string.type_image)
                2 -> context.getString(R.string.type_video)
                else -> context.getString(R.string.type_web)
            }
            ivTypeBadge.isVisible = item.type != 0
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
            cbSource.setOnClickListener {
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
