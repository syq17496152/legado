package io.legado.app.ui.highlight

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemManageBinding
import io.legado.app.ui.book.read.config.HighlightRule
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.gone

/**
 * F-P1-2 高亮规则列表 Adapter（借鉴阅读T，适配 SharedPreferences 存储）
 *
 * 适配说明：当前项目 item_manage.xml 无 tv_name 字段, 改用 cb_name 显示名称 + isChecked=enabled
 * 隐藏 swt_enabled（语义合并到 cb_name 勾选状态）
 * 已知上限：无独立开关控件 | 升级路径：后续自定义 item_highlight_rule.xml 布局
 */
class HighlightRuleAdapter(context: Context, val callBack: CallBack) :
    RecyclerAdapter<HighlightRule, ItemManageBinding>(context),
    ItemTouchCallback.Callback {

    val diffItemCallBack = object : DiffUtil.ItemCallback<HighlightRule>() {
        override fun areItemsTheSame(oldItem: HighlightRule, newItem: HighlightRule) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: HighlightRule, newItem: HighlightRule): Boolean {
            if (oldItem.getDisplayName() != newItem.getDisplayName()) return false
            if (oldItem.enabled != newItem.enabled) return false
            return true
        }

        override fun getChangePayload(oldItem: HighlightRule, newItem: HighlightRule): Any? {
            val payload = Bundle()
            if (oldItem.getDisplayName() != newItem.getDisplayName()) payload.putBoolean("upName", true)
            if (oldItem.enabled != newItem.enabled) payload.putBoolean("enabled", newItem.enabled)
            return if (payload.isEmpty) null else payload
        }
    }

    override fun getViewBinding(parent: ViewGroup): ItemManageBinding {
        return ItemManageBinding.inflate(inflater, parent, false).apply {
            // 适配：当前项目无 tv_name, 用 cb_name 显示名称 + isChecked=enabled; 隐藏 swt_enabled
            swtEnabled.gone()
        }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemManageBinding,
        item: HighlightRule,
        payloads: MutableList<Any>
    ) {
        binding.run {
            if (payloads.isEmpty()) {
                cbName.text = item.getDisplayName()
                cbName.isChecked = item.enabled
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "upName" -> cbName.text = item.getDisplayName()
                            "enabled" -> cbName.isChecked = item.enabled
                        }
                    }
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemManageBinding) {
        binding.apply {
            cbName.setOnCheckedChangeListener { buttonView, isChecked ->
                if (buttonView.isPressed) {
                    getItem(holder.layoutPosition)?.let {
                        it.enabled = isChecked
                        callBack.update(it)
                    }
                }
            }
            ivEdit.setOnClickListener {
                getItem(holder.layoutPosition)?.let { callBack.edit(it) }
            }
            contentLayout.setOnClickListener {
                getItem(holder.layoutPosition)?.let { callBack.edit(it) }
            }
            ivMenuMore.setOnClickListener {
                getItem(holder.layoutPosition)?.let { showMenu(ivMenuMore, it) }
            }
        }
    }

    private fun showMenu(view: View, item: HighlightRule) {
        val popupMenu = PopupMenu(context, view)
        popupMenu.inflate(R.menu.highlight_rule_item)
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_item_to_top -> callBack.toTop(item)
                R.id.menu_item_to_bottom -> callBack.toBottom(item)
                R.id.menu_item_delete -> callBack.delete(item)
            }
            true
        }
        popupMenu.show()
    }

    override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
        swapItem(srcPosition, targetPosition)
        return true
    }

    override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        callBack.upOrder(getItems())
    }

    interface CallBack {
        fun update(vararg rule: HighlightRule)
        fun delete(rule: HighlightRule)
        fun edit(rule: HighlightRule)
        fun toTop(rule: HighlightRule)
        fun toBottom(rule: HighlightRule)
        fun upOrder(items: List<HighlightRule>)
    }
}
