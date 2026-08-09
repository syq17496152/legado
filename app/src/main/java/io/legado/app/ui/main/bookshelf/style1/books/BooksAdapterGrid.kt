package io.legado.app.ui.main.bookshelf.style1.books

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.viewbinding.ViewBinding
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemBookshelfGrid2Binding
import io.legado.app.databinding.ItemBookshelfGridBinding
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.readProgress
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.visible
import splitties.views.onLongClick

class BooksAdapterGrid(context: Context, private val callBack: CallBack) :
    BaseBooksAdapter<ViewBinding>(context) {
    private val showBookname = AppConfig.showBookname
    override fun getViewBinding(parent: ViewGroup): ViewBinding {
        return when (showBookname) {
            2 -> ItemBookshelfGrid2Binding.inflate(inflater, parent, false)
            else -> ItemBookshelfGridBinding.inflate(inflater, parent, false)
        }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ViewBinding,
        item: Book,
        payloads: MutableList<Any>
    ) {
        when (binding) {
            is ItemBookshelfGridBinding -> binding.run {
                if (payloads.isEmpty()) {
                    if (showBookname == 0) {
                        tvName.visible()
                        tvName.text = item.name
                    } else {
                        tvName.gone()
                    }
                    ivCover.load(item, false)
                    upRefresh(binding, item)
                    upReadProgress(binding, item)
                } else {
                    for (i in payloads.indices) {
                        val bundle = payloads[i] as Bundle
                        bundle.keySet().forEach {
                            when (it) {
                                "name" -> tvName.text = item.name
                                "cover" -> ivCover.load(
                                    item,
                                    false
                                )

                                "refresh" -> upRefresh(binding, item)
                                "progress" -> upReadProgress(binding, item)
                            }
                        }
                    }
                }
            }
            is ItemBookshelfGrid2Binding -> binding.run {
                if (payloads.isEmpty()) {
                    tvName.text = item.name
                    ivCover.load(item, false)
                    upRefresh(binding, item)
                    upReadProgress(binding, item)
                } else {
                    for (i in payloads.indices) {
                        val bundle = payloads[i] as Bundle
                        bundle.keySet().forEach {
                            when (it) {
                                "name" -> tvName.text = item.name
                                "cover" -> ivCover.load(
                                    item,
                                    false
                                )

                                "refresh" -> upRefresh(binding, item)
                                "progress" -> upReadProgress(binding, item)
                            }
                        }
                    }
                }
            }
        }

    }

    private fun upRefresh(binding: ViewBinding, item: Book) {
        when (binding) {
            is ItemBookshelfGridBinding -> binding.run {
                if (!item.isLocal && callBack.isUpdate(item.bookUrl)) {
                    bvUnread.invisible()
                    rlLoading.visible()
                } else {
                    rlLoading.inVisible()
                    if (AppConfig.showUnread) {
                        bvUnread.setBadgeCount(item.getUnreadChapterNum())
                        bvUnread.setHighlight(item.lastCheckCount > 0)
                    } else {
                        bvUnread.invisible()
                    }
                }
            }
            is ItemBookshelfGrid2Binding -> binding.run {
                if (!item.isLocal && callBack.isUpdate(item.bookUrl)) {
                    bvUnread.invisible()
                    rlLoading.visible()
                } else {
                    rlLoading.inVisible()
                    if (AppConfig.showUnread) {
                        bvUnread.setBadgeCount(item.getUnreadChapterNum())
                        bvUnread.setHighlight(item.lastCheckCount > 0)
                    } else {
                        bvUnread.invisible()
                    }
                }
            }
        }
    }

    private fun upReadProgress(binding: ViewBinding, item: Book) {
        if (!AppConfig.showBookshelfReadProgress) {
            when (binding) {
                is ItemBookshelfGridBinding -> binding.pbReadProgress.gone()
                is ItemBookshelfGrid2Binding -> binding.pbReadProgress.gone()
            }
            return
        }
        val progress = kotlin.runCatching { item.readProgress() }.onFailure {
            AppLog.putDebugWithTag(
                AppLog.TAG_SHELF_PROGRESS,
                "readProgress 计算异常",
                it,
                AppLog.Level.ERROR
            )
        }.getOrNull()
        when (binding) {
            is ItemBookshelfGridBinding -> binding.run {
                if (progress == null) {
                    pbReadProgress.gone()
                } else {
                    pbReadProgress.setIndicatorColor(pbReadProgress.context.accentColor)
                    pbReadProgress.visible()
                    pbReadProgress.progress = (progress * 100).toInt()
                }
            }
            is ItemBookshelfGrid2Binding -> binding.run {
                if (progress == null) {
                    pbReadProgress.gone()
                } else {
                    pbReadProgress.setIndicatorColor(pbReadProgress.context.accentColor)
                    pbReadProgress.visible()
                    pbReadProgress.progress = (progress * 100).toInt()
                }
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ViewBinding) {
        holder.itemView.apply {
            setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.open(it)
                }
            }

            onLongClick {
                getItem(holder.layoutPosition)?.let {
                    callBack.openBookInfo(it)
                }
            }
        }
    }
}