package io.legado.app.ui.rss.article

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.utils.gone
import io.legado.app.utils.visible


abstract class BaseRssArticlesAdapter<VB : ViewBinding>(context: Context, val callBack: CallBack) :
    RecyclerAdapter<RssArticle, VB>(context) {
    interface CallBack {
        val isGridLayout: Boolean
        fun readRss(rssArticle: RssArticle)
    }

    /**
     * 列表封面图异步按需加载（ui-theme-gap-audit R1）
     *
     * RssArticleDao.flowByOriginSort 不再 select image 字段：部分源 image 存 base64 数据图
     * （实测单行最大 395KB），多行一次性 select 会挤满 CursorWindow 2MB 窗口导致读取失败。
     * 改为列表项按需走单行 getImage 查询（单行远小于窗口，安全），封面图完整显示不裁剪。
     *
     * @param holder          绑定 holder（itemView.tag=link 防 RecyclerView 复用错位）
     * @param imageView       封面 ImageView
     * @param item            列表项
     * @param gridPlaceholder grid 布局占位图
     * @param hideWhenBlank   图缺失/加载失败时是否隐藏 imageView（非 grid 列表隐藏，grid 显示占位）
     */
    @SuppressLint("CheckResult")
    protected fun loadArticleImage(
        holder: ItemViewHolder,
        imageView: ImageView,
        item: RssArticle,
        gridPlaceholder: Int = R.drawable.image_rss_article,
        hideWhenBlank: Boolean = true
    ) {
        if (item.link.isNullOrBlank()) {
            if (hideWhenBlank) imageView.gone()
            return
        }
        holder.itemView.tag = item.link
        Coroutine.async {
            appDb.rssArticleDao.getImage(item.origin, item.link)
        }.onSuccess { image ->
            if (holder.itemView.tag != item.link) return@onSuccess
            if (image.isNullOrBlank()) {
                if (hideWhenBlank) imageView.gone()
                return@onSuccess
            }
            val options = RequestOptions().set(OkHttpModelLoader.sourceOriginOption, item.origin)
            ImageLoader.load(context, image).apply(options).apply {
                if (hideWhenBlank) {
                    addListener(object : RequestListener<Drawable> {
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
                } else {
                    placeholder(gridPlaceholder)
                }
            }.into(imageView)
        }.onError {
            if (hideWhenBlank && holder.itemView.tag == item.link) imageView.gone()
        }
    }
}