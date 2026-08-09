package io.legado.app.ui.download

import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemDownloadTaskBinding
import io.legado.app.service.DownloadStatus
import io.legado.app.service.DownloadTask
import io.legado.app.ui.book.cache.formatBytes

/**
 * 下载任务列表适配器（precise-manage：DownloadManageActivity）
 */
class DownloadTaskAdapter(context: android.content.Context) :
    RecyclerAdapter<DownloadTask, ItemDownloadTaskBinding>(context) {

    var callBack: CallBack? = null

    interface CallBack {
        fun onClick(task: DownloadTask)
    }

    override fun getViewBinding(parent: ViewGroup): ItemDownloadTaskBinding {
        return ItemDownloadTaskBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemDownloadTaskBinding,
        item: DownloadTask,
        payloads: MutableList<Any>
    ) {
        binding.tvFileName.text = item.fileName
        binding.tvSource.text = item.url
        binding.tvStatus.text = when (item.status) {
            DownloadStatus.WAITING -> context.getString(R.string.wait_download)
            DownloadStatus.RUNNING -> context.getString(R.string.downloading)
            DownloadStatus.PAUSED -> context.getString(R.string.pause)
            DownloadStatus.COMPLETED -> context.getString(R.string.download_success)
            DownloadStatus.FAILED -> context.getString(R.string.download_error)
        }
        binding.tvStatus.setTextColor(
            when (item.status) {
                DownloadStatus.COMPLETED -> 0xFF43A047.toInt()
                DownloadStatus.FAILED -> 0xFFE53935.toInt()
                else -> context.getColor(R.color.secondaryText)
            }
        )
        val total = item.totalSize
        binding.tvSize.text = if (total > 0) {
            "${formatBytes(item.downloadedSize.toLong())} / ${formatBytes(total.toLong())}"
        } else {
            formatBytes(item.downloadedSize.toLong())
        }
        binding.progress.visibility = if (item.status == DownloadStatus.RUNNING) View.VISIBLE else View.GONE
        if (item.status == DownloadStatus.RUNNING && total > 0) {
            binding.progress.progress = (item.progress * 100 / total).coerceIn(0, 100)
        }
        binding.ivAction.visibility = if (item.status == DownloadStatus.COMPLETED) View.VISIBLE else View.GONE
        binding.ivAction.setOnClickListener { callBack?.onClick(item) }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemDownloadTaskBinding) {
        binding.root.setOnClickListener {
            getItem(holder.layoutPosition)?.let { task ->
                if (task.status == DownloadStatus.COMPLETED) {
                    callBack?.onClick(task)
                }
            }
        }
    }
}