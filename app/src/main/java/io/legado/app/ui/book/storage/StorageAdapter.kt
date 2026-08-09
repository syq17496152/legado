package io.legado.app.ui.book.storage

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemCacheItemBinding
import io.legado.app.ui.book.cache.CacheStorageDetail
import io.legado.app.ui.book.cache.formatBytes

class StorageManageAdapter(
    context: Context,
    private val callBack: CallBack
) : RecyclerAdapter<CacheStorageDetail, ItemCacheItemBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemCacheItemBinding {
        return ItemCacheItemBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemCacheItemBinding,
        item: CacheStorageDetail,
        payloads: MutableList<Any>
    ) {
        binding.run {
            tvName.setText(item.nameRes)
            tvSize.text = formatBytes(item.bytes)
            tvPath.text = item.deletePaths.firstOrNull()
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemCacheItemBinding) {
        binding.run {
            tvClear.setOnClickListener {
                getItem(holder.layoutPosition)?.let { detail ->
                    callBack.clear(detail)
                }
            }
            root.setOnClickListener {
                getItem(holder.layoutPosition)?.let { detail ->
                    callBack.showDetail(detail)
                }
            }
        }
    }

    interface CallBack {
        fun clear(detail: CacheStorageDetail)
        fun showDetail(detail: CacheStorageDetail)
    }
}