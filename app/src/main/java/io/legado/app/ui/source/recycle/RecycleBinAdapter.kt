package io.legado.app.ui.source.recycle

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.SourceRecycleBin
import io.legado.app.databinding.ItemRecycleBinBinding
import io.legado.app.help.source.SourceRecycleBinHelp
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.utils.ColorUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecycleBinAdapter(context: Context, var callBack: CallBack) :
    RecyclerAdapter<SourceRecycleBin, ItemRecycleBinBinding>(context) {

    private val selected = linkedSetOf<SourceRecycleBin>()

    val selection: List<SourceRecycleBin>
        get() {
            return getItems().filter {
                selected.contains(it)
            }
        }

    val diffItemCallBack = object : DiffUtil.ItemCallback<SourceRecycleBin>() {

        override fun areItemsTheSame(oldItem: SourceRecycleBin, newItem: SourceRecycleBin): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SourceRecycleBin, newItem: SourceRecycleBin): Boolean {
            return oldItem == newItem
        }
    }

    fun selectAll() {
        getItems().forEach {
            selected.add(it)
        }
        notifyItemRangeChanged(0, itemCount, bundleOf(Pair("selected", null)))
        callBack.upCountView()
    }

    fun revertSelection() {
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

    override fun getViewBinding(parent: ViewGroup): ItemRecycleBinBinding {
        return ItemRecycleBinBinding.inflate(inflater, parent, false)
    }

    override fun onCurrentListChanged() {
        callBack.upCountView()
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRecycleBinBinding,
        item: SourceRecycleBin,
        payloads: MutableList<Any>
    ) {
        binding.run {
            if (payloads.isEmpty()) {
                root.setBackgroundColor(ColorUtils.withAlpha(context.backgroundColor, 0.5f))
                cbName.isChecked = selected.contains(item)
                tvName.text = item.name.ifBlank { item.key }
                tvType.text = typeText(item.type)
                tvTime.text = timeText(item.deletedAt)
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "selected" -> cbName.isChecked = selected.contains(item)
                        }
                    }
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRecycleBinBinding) {
        binding.apply {
            cbName.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    if (cbName.isChecked) {
                        selected.add(it)
                    } else {
                        selected.remove(it)
                    }
                }
                callBack.upCountView()
            }
            ivRestore.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.restore(it)
                }
            }
            ivDelete.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.delete(it)
                }
            }
        }
    }

    val dragSelectCallback: DragSelectTouchHelper.Callback =
        object : DragSelectTouchHelper.AdvanceCallback<SourceRecycleBin>(Mode.ToggleAndReverse) {
            override fun currentSelectedId(): MutableSet<SourceRecycleBin> {
                return selected
            }

            override fun getItemId(position: Int): SourceRecycleBin {
                return getItem(position)!!
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

    private fun typeText(type: String): String {
        return when (type) {
            SourceRecycleBinHelp.TYPE_BOOK_SOURCE -> context.getString(R.string.recycle_bin_type_book_source)
            SourceRecycleBinHelp.TYPE_RSS_SOURCE -> context.getString(R.string.recycle_bin_type_rss_source)
            SourceRecycleBinHelp.TYPE_REPLACE_RULE -> context.getString(R.string.recycle_bin_type_replace_rule)
            SourceRecycleBinHelp.TYPE_TXT_TOC_RULE -> context.getString(R.string.recycle_bin_type_txt_toc_rule)
            SourceRecycleBinHelp.TYPE_HTTP_TTS -> context.getString(R.string.recycle_bin_type_http_tts)
            SourceRecycleBinHelp.TYPE_DICT_RULE -> context.getString(R.string.recycle_bin_type_dict_rule)
            SourceRecycleBinHelp.TYPE_HIGHLIGHT_RULE -> context.getString(R.string.recycle_bin_type_highlight_rule)
            else -> type
        }
    }

    private fun timeText(time: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))
    }

    interface CallBack {
        fun restore(item: SourceRecycleBin)
        fun delete(item: SourceRecycleBin)
        fun upCountView()
    }
}
