package io.legado.app.ui.rss.search

import android.view.ViewGroup
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.ui.widget.anima.explosion_field.ExplosionField
import splitties.views.onLongClick

/**
 * 订阅源搜索历史 Adapter（rss-unified-search 新增）
 *
 * 参考 [io.legado.app.ui.book.search.HistoryKeyAdapter] 的设计：
 * - 点击搜索历史项触发搜索
 * - 长按触发爆炸动画并删除该项
 *
 * 数据来源：SearchKeyword 表 type=1（订阅源）的记录，由 RssSearchViewModel 过滤
 */
class RssSearchHistoryAdapter(
    activity: RssSearchActivity,
    private val callBack: CallBack
) : RecyclerAdapter<SearchKeyword, ItemFilletTextBinding>(activity) {

    private val explosionField = ExplosionField.attach2Window(activity)

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getViewBinding(parent: ViewGroup): ItemFilletTextBinding {
        return ItemFilletTextBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemFilletTextBinding,
        item: SearchKeyword,
        payloads: MutableList<Any>
    ) {
        binding.run {
            textView.text = item.word
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemFilletTextBinding) {
        holder.itemView.apply {
            setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let {
                    callBack.searchHistory(it.word)
                }
            }
            onLongClick {
                explosionField.explode(this, true)
                getItemByLayoutPosition(holder.layoutPosition)?.let {
                    callBack.deleteHistory(it)
                }
            }
        }
    }

    interface CallBack {
        fun searchHistory(key: String)
        fun deleteHistory(searchKeyword: SearchKeyword)
    }
}
