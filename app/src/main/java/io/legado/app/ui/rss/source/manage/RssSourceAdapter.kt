package io.legado.app.ui.rss.source.manage

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.ItemRssSourceBinding
import io.legado.app.help.source.sourceInitial
import io.legado.app.help.source.sourceUrlHost
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.gone
import io.legado.app.utils.toTimeAgo
import io.legado.app.utils.visible
import java.util.Collections


class RssSourceAdapter(context: Context, val callBack: CallBack) :
    RecyclerAdapter<RssSource, ItemRssSourceBinding>(context),
    ItemTouchCallback.Callback,
    RssSourceSelection {

    private val selected = linkedSetOf<RssSource>()
    private val handler = buildMainHandler()
    // Issue-6 ADR-15: 订阅源域名分组字段（参考 BookSourceAdapter）
    var showSourceHost = false

    override val selection: List<RssSource>
        get() {
            return getItems().filter {
                selected.contains(it)
            }
        }

    val diffItemCallback = object : DiffUtil.ItemCallback<RssSource>() {

        override fun areItemsTheSame(oldItem: RssSource, newItem: RssSource): Boolean {
            return oldItem.sourceUrl == newItem.sourceUrl
        }

        override fun areContentsTheSame(oldItem: RssSource, newItem: RssSource): Boolean {
            return oldItem.sourceName == newItem.sourceName
                    && oldItem.sourceGroup == newItem.sourceGroup
                    && oldItem.enabled == newItem.enabled
                    && oldItem.lastHost == newItem.lastHost  // ADR-7: 追踪 lastHost 变化触发刷新
        }

        override fun getChangePayload(oldItem: RssSource, newItem: RssSource): Any? {
            val payload = Bundle()
            if (oldItem.sourceName != newItem.sourceName
                || oldItem.sourceGroup != newItem.sourceGroup
            ) {
                payload.putBoolean("upName", true)
            }
            if (oldItem.enabled != newItem.enabled) {
                payload.putBoolean("enabled", newItem.enabled)
            }
            // ADR-7: lastHost 变化时增加 upHost payload，触发 tv_rss_source_url 增量刷新
            if (oldItem.lastHost != newItem.lastHost) {
                payload.putBoolean("upHost", true)
            }
            if (payload.isEmpty) {
                return null
            }
            return payload
        }
    }

    override fun getViewBinding(parent: ViewGroup): ItemRssSourceBinding {
        return ItemRssSourceBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssSourceBinding,
        item: RssSource,
        payloads: MutableList<Any>
    ) {
        binding.run {
            if (payloads.isEmpty()) {
                root.setBackgroundColor(ColorUtils.withAlpha(context.backgroundColor, 0.5f))
                cbSource.text = item.getDisplayNameGroup()
                swtEnabled.isChecked = item.enabled
                cbSource.isChecked = selected.contains(item)
                // Issue-6 新增控件绑定
                tvSourceInitial.text = item.sourceInitial()
                tvRssSourceUrl.text = item.sourceUrlHost()
                vEnabledDot.visibility = if (item.enabled) View.VISIBLE else View.GONE
                // tv_last_update 显示最后更新时间（ADR-13: 替代书源的 tv_debug_text）
                if (item.lastUpdateTime > 0) {
                    tvLastUpdate.text = item.lastUpdateTime.toTimeAgo()
                    tvLastUpdate.isVisible = true
                } else {
                    tvLastUpdate.text = ""
                    tvLastUpdate.isVisible = false
                }
                upSourceHost(binding, holder.layoutPosition)
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "enabled" -> {
                                swtEnabled.isChecked = bundle.getBoolean("enabled")
                                vEnabledDot.visibility = if (bundle.getBoolean("enabled")) View.VISIBLE else View.GONE
                            }
                            "upName" -> {
                                cbSource.text = item.getDisplayNameGroup()
                                tvSourceInitial.text = item.sourceInitial()
                            }
                            "upHost" -> tvRssSourceUrl.text = item.sourceUrlHost()
                            "selected" -> cbSource.isChecked = selected.contains(item)
                            "upSourceHost" -> upSourceHost(binding, holder.layoutPosition)
                        }
                    }
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssSourceBinding) {
        binding.apply {
            swtEnabled.setOnUserCheckedChangeListener { checked ->
                getItem(holder.layoutPosition)?.let {
                    it.enabled = checked
                    callBack.update(it)
                }
            }
            cbSource.setOnUserCheckedChangeListener { checked ->
                getItem(holder.layoutPosition)?.let {
                    if (checked) {
                        selected.add(it)
                    } else {
                        selected.remove(it)
                    }
                    callBack.upCountView()
                }
            }
            ivEdit.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.edit(it)
                }
            }
            ivMenuMore.setOnClickListener {
                showMenu(ivMenuMore, holder.layoutPosition)
            }
        }
    }

    override fun onCurrentListChanged() {
        callBack.upCountView()
        // Issue-6 ADR-15: 列表变化时刷新域名分组标题（参考 BookSourceAdapter.onCurrentListChanged）
        handler.post {
            notifyItemRangeChanged(0, itemCount, bundleOf("upSourceHost" to null))
        }
    }

    // Issue-6 ADR-15: 订阅源域名分组辅助方法（参考 BookSourceAdapter 实现）
    private fun upSourceHost(binding: ItemRssSourceBinding, position: Int) = binding.run {
        if (showSourceHost && isItemHeader(position)) {
            tvHostText.text = getHeaderText(position)
            tvHostText.visible()
        } else {
            tvHostText.gone()
        }
    }

    fun getHeaderText(position: Int): String {
        val source = getItem(position)!!
        // ADR-11: 优先用 lastHost，与 sourceUrlHost() 逻辑一致
        return callBack.getSourceHost(source.lastHost ?: source.sourceUrl)
    }

    fun isItemHeader(position: Int): Boolean {
        if (position == 0) return true
        val lastHost = getHeaderText(position - 1)
        val curHost = getHeaderText(position)
        return lastHost != curHost
    }

    override fun selectAll() {
        getItems().forEach {
            selected.add(it)
        }
        notifyItemRangeChanged(0, itemCount, bundleOf(Pair("selected", null)))
        callBack.upCountView()
    }

    override fun revertSelection() {
        getItems().forEach {
            if (selected.contains(it)) {
                selected.remove(it)
            } else {
                selected.add(it)
            }
        }
        notifyItemRangeChanged(0, itemCount, bundleOf(Pair("selected", null)))
        callBack.upCountView()
    }

    override fun checkSelectedInterval() {
        val selectedPosition = linkedSetOf<Int>()
        getItems().forEachIndexed { index, it ->
            if (selected.contains(it)) {
                selectedPosition.add(index)
            }
        }
        // M-02 修复：空判保护，避免无选中项时 Collections.min/max 抛 NoSuchElementException
        if (selectedPosition.isEmpty()) return
        val minPosition = Collections.min(selectedPosition)
        val maxPosition = Collections.max(selectedPosition)
        val itemCount = maxPosition - minPosition + 1
        for (i in minPosition..maxPosition) {
            getItem(i)?.let {
                selected.add(it)
            }
        }
        notifyItemRangeChanged(minPosition, itemCount, bundleOf(Pair("selected", null)))
        callBack.upCountView()
    }

    private fun showMenu(view: View, position: Int) {
        val source = getItem(position) ?: return
        val popupMenu = PopupMenu(context, view)
        popupMenu.inflate(R.menu.rss_source_item)
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_top -> callBack.toTop(source)
                R.id.menu_bottom -> callBack.toBottom(source)
                R.id.menu_del -> {
                    callBack.del(source)
                    selected.remove(source)
                }
            }
            true
        }
        popupMenu.show()
    }

    override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
        val srcItem = getItem(srcPosition)
        val targetItem = getItem(targetPosition)
        if (srcItem != null && targetItem != null) {
            if (srcItem.customOrder == targetItem.customOrder) {
                callBack.upOrder()
            } else {
                val srcOrder = srcItem.customOrder
                srcItem.customOrder = targetItem.customOrder
                targetItem.customOrder = srcOrder
                movedItems.add(srcItem)
                movedItems.add(targetItem)
            }
        }
        swapItem(srcPosition, targetPosition)
        return true
    }

    private val movedItems = hashSetOf<RssSource>()

    override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        if (movedItems.isNotEmpty()) {
            callBack.update(*movedItems.toTypedArray())
            movedItems.clear()
        }
    }

    override val dragSelectCallback: DragSelectTouchHelper.Callback =
        object : DragSelectTouchHelper.AdvanceCallback<RssSource>(Mode.ToggleAndReverse) {
            override fun currentSelectedId(): MutableSet<RssSource> {
                return selected
            }

            override fun getItemId(position: Int): RssSource {
                // R4.6 修复：getItem(position) 可能返回 null（数据未加载/position 越界），
                // 原代码 !! 导致 NPE 崩溃。改用空对象兜底，mOriginalSelection.contains(空对象)
                // 返回 false，mFirstWasSelected = false，不影响拖拽选择逻辑。
                return getItem(position) ?: RssSource()
            }

            override fun updateSelectState(position: Int, isSelected: Boolean): Boolean {
                getItem(position)?.let {
                    if (isSelected) {
                        selected.add(it)
                    } else {
                        selected.remove(it)
                    }
                    notifyItemChanged(position, bundleOf(Pair("selected", null)))
                    callBack.upCountView()
                    return true
                }
                return false
            }
        }

    interface CallBack {
        fun del(source: RssSource)
        fun edit(source: RssSource)
        fun update(vararg source: RssSource)
        fun toTop(source: RssSource)
        fun toBottom(source: RssSource)
        fun upOrder()
        fun upCountView()
        // Issue-6 ADR-15: 新增 getSourceHost 用于域名分组
        fun getSourceHost(origin: String): String
    }
}

/**
 * M-02 修复：选择模式接口（compact/grid 选择机制统一）
 * P2 的 M-10 会提取 BaseSourceAdapter 基类，届时本接口可移除
 */
interface RssSourceSelection {
    val selection: List<RssSource>
    val dragSelectCallback: DragSelectTouchHelper.Callback
    fun selectAll()
    fun revertSelection()
    fun checkSelectedInterval()
}
