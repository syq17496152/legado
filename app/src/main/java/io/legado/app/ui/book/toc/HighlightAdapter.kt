package io.legado.app.ui.book.toc

import android.content.Context
import android.view.ViewGroup
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.BookHighlight
import io.legado.app.databinding.ItemHighlightBinding
import io.legado.app.utils.gone
import splitties.views.onLongClick

/**
 * F-P1-2 高亮规则系统（借鉴阅读T）
 * 标注列表 Adapter, 展示手动高亮的章节名/原文/笔记/颜色条
 */
class HighlightAdapter(context: Context, val callback: Callback) :
    RecyclerAdapter<BookHighlight, ItemHighlightBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemHighlightBinding {
        return ItemHighlightBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemHighlightBinding,
        item: BookHighlight,
        payloads: MutableList<Any>
    ) {
        binding.tvChapterName.text = item.chapterName
        binding.tvBookText.gone(item.bookText.isEmpty())
        binding.tvBookText.text = item.bookText
        binding.tvNote.gone(item.note.isEmpty())
        binding.tvNote.text = item.note
        val s = item.styleObj()
        val chip = s.fill.takeIf { it != 0 }
            ?: s.textColor.takeIf { it != 0 }
            ?: s.underline?.color?.takeIf { it != 0 }
            ?: s.strike?.color?.takeIf { it != 0 }
            ?: s.box?.color?.takeIf { it != 0 }
            ?: s.emphasis?.color?.takeIf { it != 0 }
            ?: 0
        binding.viewColor.setBackgroundColor(chip)
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemHighlightBinding) {
        binding.root.setOnClickListener {
            getItem(holder.layoutPosition)?.let { callback.onClick(it) }
        }
        binding.root.onLongClick {
            getItem(holder.layoutPosition)?.let { callback.onLongClick(it, holder.layoutPosition) }
        }
    }

    interface Callback {
        fun onClick(highlight: BookHighlight)
        fun onLongClick(highlight: BookHighlight, pos: Int)
    }

}
