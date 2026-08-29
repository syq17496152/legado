package io.legado.app.ui.rss.article

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.RssArticle
import io.legado.app.databinding.ItemRssArticle1Binding
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.visible


class RssArticlesAdapter1(context: Context, callBack: CallBack) :
    BaseRssArticlesAdapter<ItemRssArticle1Binding>(context, callBack) {

    override fun getViewBinding(parent: ViewGroup): ItemRssArticle1Binding {
        return ItemRssArticle1Binding.inflate(inflater, parent, false)
    }

    @SuppressLint("CheckResult")
    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssArticle1Binding,
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

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssArticle1Binding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.readRss(it)
            }
        }
    }

}