package io.legado.app.ui.rss.search

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.SearchRssArticle
import io.legado.app.databinding.ItemRssSearchBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.visible

/**
 * 订阅源统一搜索结果 Adapter（rss-unified-search 新增）
 *
 * 参考 [io.legado.app.ui.book.search.SearchAdapter] 的多源聚合展示设计：
 * - 多源聚合后通过 [SearchRssArticle.origins] 显示来源数 BadgeView
 * - 已读状态通过标题颜色区分（参考 RssArticlesAdapter 已读变灰策略）
 * - 图片加载携带 origin 参数（参考 RssArticlesAdapter，部分源需要 referer/cookie）
 *
 * 设计依据：rss-unified-search design.md §4.1
 */
class RssSearchAdapter(context: Context, private val callBack: CallBack) :
    DiffRecyclerAdapter<SearchRssArticle, ItemRssSearchBinding>(context) {

    override val keepScrollPosition = true

    override val diffItemCallback: DiffUtil.ItemCallback<SearchRssArticle>
        get() = object : DiffUtil.ItemCallback<SearchRssArticle>() {

            override fun areItemsTheSame(
                oldItem: SearchRssArticle,
                newItem: SearchRssArticle
            ): Boolean {
                // 参考书源 name + author 策略，使用 deduplicationKey（title + pubDate）
                return oldItem.deduplicationKey() == newItem.deduplicationKey()
            }

            override fun areContentsTheSame(
                oldItem: SearchRssArticle,
                newItem: SearchRssArticle
            ): Boolean {
                return oldItem == newItem
            }

            override fun getChangePayload(
                oldItem: SearchRssArticle,
                newItem: SearchRssArticle
            ): Any {
                val payload = android.os.Bundle()
                if (oldItem.origins.size != newItem.origins.size) {
                    payload.putInt("origins", newItem.origins.size)
                }
                if (oldItem.image != newItem.image) {
                    payload.putString("cover", newItem.image)
                }
                if (oldItem.pubDate != newItem.pubDate) {
                    payload.putString("pubDate", newItem.pubDate)
                }
                if (oldItem.description != newItem.description) {
                    payload.putString("description", newItem.description)
                }
                if (oldItem.isRead != newItem.isRead) {
                    payload.putBoolean("isRead", newItem.isRead)
                }
                return payload
            }

        }

    override fun getViewBinding(parent: ViewGroup): ItemRssSearchBinding {
        return ItemRssSearchBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssSearchBinding,
        item: SearchRssArticle,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            bind(binding, item)
        } else {
            for (i in payloads.indices) {
                val bundle = payloads[i] as android.os.Bundle
                bindChange(binding, item, bundle)
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssSearchBinding) {
        binding.root.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.showArticleInfo(it)
            }
        }
    }

    @SuppressLint("CheckResult")
    private fun bind(binding: ItemRssSearchBinding, item: SearchRssArticle) {
        binding.run {
            tvTitle.text = item.title
            // 已读状态：标题颜色变灰（参考 RssArticlesAdapter 已读策略）
            upReadState(binding, item.isRead)
            // 来源数角标
            bvOriginCount.setBadgeCount(item.origins.size)
            // 发布时间
            upPubDate(binding, item.pubDate)
            // 摘要
            tvDescription.text = item.description
            // 图片加载（携带 origin 参数，部分源需要 referer/cookie）
            upCover(binding, item)
        }
    }

    private fun bindChange(
        binding: ItemRssSearchBinding,
        item: SearchRssArticle,
        bundle: android.os.Bundle
    ) {
        binding.run {
            bundle.keySet().forEach { key ->
                when (key) {
                    "origins" -> bvOriginCount.setBadgeCount(item.origins.size)
                    "pubDate" -> upPubDate(binding, item.pubDate)
                    "description" -> tvDescription.text = item.description
                    "isRead" -> upReadState(binding, item.isRead)
                    "cover" -> upCover(binding, item)
                }
            }
        }
    }

    private fun upReadState(binding: ItemRssSearchBinding, isRead: Boolean) {
        binding.run {
            if (isRead) {
                tvTitle.setTextColor(context.getCompatColor(R.color.tv_text_summary))
                ivRead.visible()
            } else {
                tvTitle.setTextColor(context.getCompatColor(R.color.primaryText))
                ivRead.gone()
            }
        }
    }

    private fun upPubDate(binding: ItemRssSearchBinding, pubDate: String?) {
        binding.run {
            if (pubDate.isNullOrBlank()) {
                tvPubDate.gone()
            } else {
                tvPubDate.text = pubDate
                tvPubDate.visible()
            }
        }
    }

    @SuppressLint("CheckResult")
    private fun upCover(binding: ItemRssSearchBinding, item: SearchRssArticle) {
        binding.run {
            // 取默认源的 origin 用于图片加载（部分源需要 referer/cookie）
            // OkHttpModelLoader.sourceOriginOption 要求非 null String，origins 为空时传空串走默认加载
            val defaultOrigin = item.origins.firstOrNull() ?: ""
            if (item.image.isNullOrBlank()) {
                ivCover.gone()
            } else {
                ivCover.visible()
                val options = RequestOptions().set(
                    OkHttpModelLoader.sourceOriginOption, defaultOrigin
                )
                ImageLoader.load(context, item.image).apply(options).apply {
                    placeholder(R.drawable.image_rss_article)
                    addListener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: Target<Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            ivCover.gone()
                            return false
                        }

                        override fun onResourceReady(
                            resource: Drawable,
                            model: Any,
                            target: Target<Drawable>?,
                            dataSource: DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            ivCover.visible()
                            return false
                        }

                    })
                }.into(ivCover)
            }
        }
    }

    interface CallBack {
        /**
         * 点击搜索结果项，跳转到文章详情页
         */
        fun showArticleInfo(article: SearchRssArticle)
    }
}
