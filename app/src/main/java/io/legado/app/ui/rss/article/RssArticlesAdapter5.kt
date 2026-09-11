package io.legado.app.ui.rss.article

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.RssArticle
import io.legado.app.databinding.ItemRssArticleFreeBinding
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.visible

/**
 * 订阅源「自由」布局（articleStyle=5）的 Adapter。
 *
 * 每个 item = 「图片区 + 图下固定文字块」，宽高由 [io.legado.app.ui.rss.article.free.RssFreeGridLayoutManager]
 * 按矩形表精确指定（见 `item_rss_article_free.xml` 顶部注释）。
 *
 * 与其他 5 种样式保持一致的三点：
 * 1. 标题 + 时间必显示（自由布局不是纯图墙，见 AD-08）；
 * 2. 已读态用次要文字色表达；
 * 3. 点击回调统一走 [CallBack.readRss]，不引入自由布局专属路由。
 *
 * 自由布局特有的两点：
 * 1. 图片区占满整格，因此**额外用图片透明度**表达已读（仅靠文字次要色辨识度不足）；
 * 2. 视频类文章（type=2）在右上角加角标，否则纯图墙无法区分视频与图片。
 */
class RssArticlesAdapter5(context: Context, callBack: CallBack) :
    BaseRssArticlesAdapter<ItemRssArticleFreeBinding>(context, callBack) {

    override fun getViewBinding(parent: ViewGroup): ItemRssArticleFreeBinding {
        return ItemRssArticleFreeBinding.inflate(inflater, parent, false)
    }

    @SuppressLint("CheckResult")
    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssArticleFreeBinding,
        item: RssArticle,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty()) {
            payloads.forEach { payload ->
                when (payload) {
                    "read" -> applyReadState(binding, item)
                    "title" -> binding.tvTitle.text = item.title
                }
            }
            return
        }
        binding.run {
            tvTitle.text = item.title
            tvPubDate.text = item.pubDate
            applyReadState(this, item)
            // 视频类文章角标：type 0=网页 1=图片 2=视频
            ivVideoBadge.visible(item.type == VIDEO_TYPE)
            // hideWhenBlank=false：自由布局格位由矩形表固定，缺图必须显示占位图，
            // 否则 ImageView 被隐藏后格子会露出空白，破坏整行观感
            loadArticleImage(
                holder, imageView, item,
                gridPlaceholder = R.drawable.transparent_placeholder,
                hideWhenBlank = false
            )
        }
    }

    /**
     * 已读态：标题用次要色 + 图片降亮。
     *
     * 图片降亮用 alpha 而非叠加遮罩 View —— 视觉等价，但省掉一个覆盖层 View 与颜色资源，
     * 且不影响 LayoutManager 的矩形测量（alpha 不参与测量）。
     */
    private fun applyReadState(binding: ItemRssArticleFreeBinding, item: RssArticle) {
        binding.tvTitle.setTextColor(
            context.getCompatColor(
                if (item.read) R.color.tv_text_summary else R.color.primaryText
            )
        )
        binding.imageView.alpha = if (item.read) READ_IMAGE_ALPHA else 1f
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssArticleFreeBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.readRss(it)
            }
        }
    }

    companion object {
        /** RssArticle.type 中表示视频的值 */
        private const val VIDEO_TYPE = 2

        /** 已读文章的图片透明度（0.55 在浅色与深色主题下均可辨识） */
        private const val READ_IMAGE_ALPHA = 0.55f
    }
}
