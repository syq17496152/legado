package io.legado.app.ui.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemSourceFolderBinding

/**
 * F-P1-8 书源/订阅源文件夹视图 Adapter
 *
 * 通用文件夹卡片 Adapter，书源和订阅源管理界面共用。
 * 数据项为分组名称（String），点击触发 [CallBack.onFolderClick]。
 *
 * 简化说明：不显示分组内源数量 | 已知上限：书源分组是共享的（一个源可属多个分组），数量含义不明确 | 升级路径：如需显示数量，新增 DAO COUNT 查询并扩展数据模型为 data class
 */
class SourceFolderAdapter(
    context: Context,
    private val callBack: CallBack
) : RecyclerAdapter<String, ItemSourceFolderBinding>(context) {

    val diffItemCallback = object : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean =
            oldItem == newItem

        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean =
            oldItem == newItem
    }

    override fun getViewBinding(parent: ViewGroup): ItemSourceFolderBinding {
        return ItemSourceFolderBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemSourceFolderBinding,
        item: String,
        payloads: MutableList<Any>
    ) {
        binding.tvFolderName.text = item
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemSourceFolderBinding) {
        binding.root.setOnClickListener {
            getItem(holder.layoutPosition)?.let { callBack.onFolderClick(it) }
        }
    }

    interface CallBack {
        fun onFolderClick(group: String)
    }
}
