package io.legado.app.ui.autoTask

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemManageBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.AutoTaskRule
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.dpToPx
import io.legado.app.utils.startActivity
import io.legado.app.utils.visible
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * F-P1-1 自动任务列表页适配器
 *
 * 选择模式参照 BookSourceAdapter，不实现 SelectableAdapter（本项目不存在该接口）。
 * 用 task.id 作为选择 key（AutoTaskRule 字段可变，用 id 比 用对象引用更稳定）。
 */
class AutoTaskAdapter(context: Context, private val callBack: CallBack) :
    RecyclerAdapter<AutoTaskRule, ItemManageBinding>(context),
    ItemTouchCallback.Callback {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val selected = linkedSetOf<String>()

    val selection: List<AutoTaskRule>
        get() = getItems().filter { selected.contains(it.id) }

    val diffItemCallBack = object : DiffUtil.ItemCallback<AutoTaskRule>() {
        override fun areItemsTheSame(oldItem: AutoTaskRule, newItem: AutoTaskRule): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: AutoTaskRule, newItem: AutoTaskRule): Boolean {
            return oldItem == newItem
        }

        override fun getChangePayload(oldItem: AutoTaskRule, newItem: AutoTaskRule): Any? {
            val payload = Bundle()
            if (oldItem.name != newItem.name) {
                payload.putBoolean("name", true)
            }
            if (oldItem.enable != newItem.enable) {
                payload.putBoolean("enabled", true)
            }
            if (oldItem.lastRunAt != newItem.lastRunAt ||
                oldItem.lastError != newItem.lastError ||
                oldItem.cron != newItem.cron
            ) {
                payload.putBoolean("summary", true)
            }
            return if (payload.isEmpty) null else payload
        }
    }

    override fun getViewBinding(parent: ViewGroup): ItemManageBinding {
        return ItemManageBinding.inflate(inflater, parent, false).apply {
            tvSubtitle.visible()
        }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemManageBinding,
        item: AutoTaskRule,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            binding.cbName.text = item.name.ifBlank { item.id }
            binding.swtEnabled.isChecked = item.enable
            binding.tvSubtitle.text = buildSummary(item)
            binding.cbName.isChecked = isSelected(item)
            upSelectStroke(binding, item)
        } else {
            for (i in payloads.indices) {
                val bundle = payloads[i] as? Bundle ?: continue
                bundle.keySet().forEach {
                    when (it) {
                        "name" -> binding.cbName.text = item.name.ifBlank { item.id }
                        "enabled" -> binding.swtEnabled.isChecked = item.enable
                        "summary" -> binding.tvSubtitle.text = buildSummary(item)
                        "selected" -> binding.cbName.isChecked = isSelected(item)
                    }
                }
            }
            binding.cbName.isChecked = isSelected(item)
            upSelectStroke(binding, item)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemManageBinding) {
        binding.cbName.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                getItem(holder.layoutPosition)?.let { task ->
                    setSelected(task, isChecked)
                    upSelectStroke(binding, task)
                    callBack.upCountView()
                }
            }
        }
        binding.swtEnabled.setOnCheckedChangeListener { buttonView, isChecked ->
            val item = getItem(holder.layoutPosition) ?: return@setOnCheckedChangeListener
            if (buttonView.isPressed) {
                callBack.toggle(item, isChecked)
            }
        }
        binding.ivEdit.setOnClickListener {
            getItem(holder.layoutPosition)?.let { callBack.edit(it) }
        }
        binding.ivMenuMore.setOnClickListener { view ->
            getItem(holder.layoutPosition)?.let { showMenu(view, it) }
        }
        binding.contentLayout.setOnClickListener {
            getItem(holder.layoutPosition)?.let { task ->
                val nowSelected = !isSelected(task)
                setSelected(task, nowSelected)
                binding.cbName.isChecked = nowSelected
                upSelectStroke(binding, task)
                callBack.upCountView()
            }
        }
    }

    override fun onCurrentListChanged() {
        val currentIds = getItems().map { it.id }.toHashSet()
        val iterator = selected.iterator()
        while (iterator.hasNext()) {
            if (!currentIds.contains(iterator.next())) {
                iterator.remove()
            }
        }
        callBack.upCountView()
    }

    // ItemTouchCallback.Callback
    override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
        swapItem(srcPosition, targetPosition)
        return true
    }

    override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        callBack.upOrder(getItems())
    }

    val dragSelectCallback: DragSelectTouchHelper.Callback =
        object : DragSelectTouchHelper.AdvanceCallback<String>(DragSelectTouchHelper.AdvanceCallback.Mode.ToggleAndReverse) {
            override fun currentSelectedId(): Set<String>? {
                return selected
            }

            override fun getItemId(position: Int): String {
                return getItem(position)!!.id
            }

            override fun updateSelectState(position: Int, isSelected: Boolean): Boolean {
                getItem(position)?.let { task ->
                    setSelected(task, isSelected)
                    notifyItemChanged(position, bundleOf(Pair("selected", null)))
                    callBack.upCountView()
                    return true
                }
                return false
            }
        }

    fun selectAll() {
        getItems().forEach { selected.add(it.id) }
        notifyItemRangeChanged(0, itemCount, bundleOf(Pair("selected", null)))
        callBack.upCountView()
    }

    fun revertSelection() {
        val currentIds = getItems().map { it.id }.toHashSet()
        val newSelected = linkedSetOf<String>()
        getItems().forEach { task ->
            if (!selected.contains(task.id)) {
                newSelected.add(task.id)
            }
        }
        selected.clear()
        selected.addAll(newSelected)
        notifyItemRangeChanged(0, itemCount, bundleOf(Pair("selected", null)))
        callBack.upCountView()
    }

    private fun isSelected(task: AutoTaskRule): Boolean = selected.contains(task.id)

    private fun setSelected(task: AutoTaskRule, isSelected: Boolean) {
        if (isSelected) {
            selected.add(task.id)
        } else {
            selected.remove(task.id)
        }
    }

    private fun upSelectStroke(binding: ItemManageBinding, task: AutoTaskRule) {
        binding.rootCard.strokeColor = context.accentColor
        binding.rootCard.strokeWidth = if (isSelected(task)) 2.dpToPx() else 0
    }

    private fun buildSummary(task: AutoTaskRule): String {
        val cron = task.cron?.trim().orEmpty().ifBlank { "-" }
        val status = when {
            !task.lastError.isNullOrBlank() ->
                context.getString(R.string.auto_task_last_error, task.lastError)
            task.lastRunAt > 0L ->
                context.getString(
                    R.string.auto_task_last_run,
                    timeFormat.format(Date(task.lastRunAt))
                )
            else -> context.getString(R.string.auto_task_not_run)
        }
        return context.getString(R.string.auto_task_item_summary, cron, status)
    }

    private fun showMenu(view: View, task: AutoTaskRule) {
        val popupMenu = PopupMenu(context, view)
        popupMenu.inflate(R.menu.auto_task_item)
        popupMenu.menu.findItem(R.id.menu_item_login).isVisible = !task.loginUrl.isNullOrBlank()
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_item_login -> context.startActivity<SourceLoginActivity> {
                    putExtra("type", "autoTask")
                    putExtra("key", task.id)
                }
                R.id.menu_item_log -> callBack.showLog(task)
                R.id.menu_item_delete -> callBack.delete(task)
            }
            true
        }
        popupMenu.show()
    }

    interface CallBack {
        fun edit(task: AutoTaskRule)
        fun delete(task: AutoTaskRule)
        fun toggle(task: AutoTaskRule, enabled: Boolean)
        fun upCountView()
        fun showLog(task: AutoTaskRule)
        fun upOrder(items: List<AutoTaskRule>)
    }
}
