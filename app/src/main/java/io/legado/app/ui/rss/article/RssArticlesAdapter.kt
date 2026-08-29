package io.legado.app.ui.rss.article

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.RssArticle
import io.legado.app.databinding.ItemRssArticleBinding
import io.legado.app.utils.getCompatColor


class RssArticlesAdapter(context: Context, callBack: CallBack) :
    BaseRssArticlesAdapter<ItemRssArticleBinding>(context, callBack) {

    override fun getViewBinding(parent: ViewGroup): ItemRssArticleBinding {
        return ItemRssArticleBinding.inflate(inflater, parent, false)
    }

    @SuppressLint("CheckResult")
    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssArticleBinding,
        item: RssArticle,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty()) {
            payloads.forEach { payload ->
                when (payload) {
                    "read" -> {
                        if (item.read) {
                            binding.tvTitle.setTextColor(context.getCompatColor(R.color.tv_text_summary))
                        } else {
                            binding.tvTitle.setTextColor(context.getCompatColor(R.color.primaryText))
                        }
                    }
                    "title" -> {
                        binding.tvTitle.text = item.title
                    }
                }
            }
            return
        }
        binding.run {
            tvTitle.text = item.title
            if (item.read) {
                tvTitle.setTextColor(context.getCompatColor(R.color.tv_text_summary))
            } else {
                tvTitle.setTextColor(context.getCompatColor(R.color.primaryText))
            }
            tvPubDate.text = item.pubDate
            loadArticleImage(
                holder, imageView, item,
                gridPlaceholder = R.drawable.image_rss_article,
                hideWhenBlank = !callBack.isGridLayout
            )
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssArticleBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.readRss(it)
            }
        }
    }

}