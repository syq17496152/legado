package io.legado.app.ui.rss.favorites

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssStar
import io.legado.app.databinding.ItemRssArticleBinding
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.utils.gone
import io.legado.app.utils.visible


class RssFavoritesAdapter(context: Context, val callBack: CallBack) :
    RecyclerAdapter<RssStar, ItemRssArticleBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemRssArticleBinding {
        return ItemRssArticleBinding.inflate(inflater, parent, false)
    }

    @SuppressLint("CheckResult")
    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssArticleBinding,
        item: RssStar,
        payloads: MutableList<Any>
    ) {
        binding.run {
            tvTitle.text = item.title
            tvPubDate.text = item.pubDate
            // ui-theme-gap-audit R1：收藏列表主查询不再携带 image（见 RssStarDao.flowByGroup），
            // 封面图按需单行 getImage 查询后加载，大图完整显示不裁剪、不置空。
            val imageKey = item.link
            if (imageKey.isNullOrBlank()) {
                imageView.gone()
                return@run
            }
            holder.itemView.tag = imageKey
            Coroutine.async {
                appDb.rssStarDao.getImage(item.origin, item.link)
            }.onSuccess { image ->
                if (holder.itemView.tag != imageKey || image.isNullOrBlank()) {
                    return@onSuccess
                }
                val options =
                    RequestOptions().set(OkHttpModelLoader.sourceOriginOption, item.origin)
                ImageLoader.load(context, image)
                    .apply(options)
                    .addListener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: Target<Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            imageView.gone()
                            return false
                        }

                        override fun onResourceReady(
                            resource: Drawable,
                            model: Any,
                            target: Target<Drawable>?,
                            dataSource: DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            imageView.visible()
                            return false
                        }

                    })
                    .into(imageView)
            }.onError {
                if (holder.itemView.tag == imageKey) imageView.gone()
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssArticleBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.readRss(it)
            }
        }
        holder.itemView.setOnLongClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.delStar(it)
            }
            true
        }
    }

    interface CallBack {
        fun readRss(rssStar: RssStar)

        fun delStar(rssStar: RssStar)
    }
}