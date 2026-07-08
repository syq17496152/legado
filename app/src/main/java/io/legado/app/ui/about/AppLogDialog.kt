package io.legado.app.ui.about

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemAppLogBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.LogUtils
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.views.onClick
import java.util.*

class AppLogDialog : BaseDialogFragment(R.layout.dialog_recycler_view),
    Toolbar.OnMenuItemClickListener {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private var currentFilter: AppLog.Level? = null
    private val adapter by lazy {
        LogAdapter(requireContext())
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.run {
            toolBar.setBackgroundColor(primaryColor)
            toolBar.setTitle(R.string.log)
            toolBar.inflateMenu(R.menu.app_log)
            toolBar.setOnMenuItemClickListener(this@AppLogDialog)
            recyclerView.layoutManager = LinearLayoutManager(requireContext())
            recyclerView.adapter = adapter
        }
        refreshLogs()
    }

    private fun refreshLogs() {
        val logs = if (currentFilter == null) {
            AppLog.logs
        } else {
            AppLog.logs.filter { it.level == currentFilter }
        }
        adapter.setItems(logs)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_clear -> {
                AppLog.clear()
                adapter.clearItems()
            }
            R.id.filter_all -> {
                currentFilter = null
                item.isChecked = true
                refreshLogs()
            }
            R.id.filter_error -> {
                currentFilter = AppLog.Level.ERROR
                item.isChecked = true
                refreshLogs()
            }
            R.id.filter_warn -> {
                currentFilter = AppLog.Level.WARN
                item.isChecked = true
                refreshLogs()
            }
            R.id.filter_info -> {
                currentFilter = AppLog.Level.INFO
                item.isChecked = true
                refreshLogs()
            }
            R.id.filter_debug -> {
                currentFilter = AppLog.Level.DEBUG
                item.isChecked = true
                refreshLogs()
            }
        }
        return true
    }

    inner class LogAdapter(context: Context) :
        RecyclerAdapter<AppLog.LogEntry, ItemAppLogBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemAppLogBinding {
            return ItemAppLogBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemAppLogBinding,
            item: AppLog.LogEntry,
            payloads: MutableList<Any>
        ) {
            binding.textTime.text = LogUtils.logTimeFormat.format(Date(item.time))
            binding.textMessage.text = formatMessage(item)
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemAppLogBinding) {
            binding.root.onClick {
                getItem(holder.layoutPosition)?.let { item ->
                    item.throwable?.let {
                        showDialogFragment(TextDialog("Log", it.stackTraceToString()))
                    }
                }
            }
        }

        private fun formatMessage(item: AppLog.LogEntry): String {
            val prefix = when (item.level) {
                AppLog.Level.ERROR -> "[E] "
                AppLog.Level.WARN -> "[W] "
                AppLog.Level.INFO -> "[I] "
                AppLog.Level.DEBUG -> "[D] "
            }
            return prefix + item.message
        }

    }

}
