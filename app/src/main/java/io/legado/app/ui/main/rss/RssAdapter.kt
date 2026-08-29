package io.legado.app.ui.main.rss

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.ItemRssBinding
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.titleTypeface
import splitties.views.onLongClick
import io.legado.app.ui.widget.ModernActionPopup

class RssAdapter(
    context: Context,
    private val fragment: Fragment,
    private val callBack: CallBack,
    private val lifecycle: Lifecycle
) : RecyclerAdapter<RssSource, ItemRssBinding>(context) {

    private var menuPopup: ModernActionPopup.Handle? = null

    override fun getViewBinding(parent: ViewGroup): ItemRssBinding {
        return ItemRssBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRssBinding,
        item: RssSource,
        payloads: MutableList<Any>
    ) {
        binding.apply {
            // rss-classic-layout-align S4：视效对齐书架基线——主题圆角（替代 XML 硬编码 12dp）+ 主题标题字体
            ivIcon.setCornerRadius(UiCorner.actionRadius(context).toInt())
            tvName.typeface = context.titleTypeface()
            tvName.text = item.sourceName
            val options = RequestOptions()
                .set(OkHttpModelLoader.sourceOriginOption, item.sourceUrl)
            ImageLoader.load(fragment, lifecycle, item.sourceIcon)
                .apply(options)
                .centerCrop()
                .placeholder(R.drawable.image_rss)
                .error(R.drawable.image_rss)
                .into(ivIcon)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRssBinding) {
        binding.apply {
            root.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let {
                    callBack.openRss(it)
                }
            }
            root.onLongClick {
                getItemByLayoutPosition(holder.layoutPosition)?.let {
                    showMenu(ivIcon, it)
                }
            }
        }
    }

    private fun showMenu(view: View, rssSource: RssSource) {
        menuPopup = ModernActionPopup.showFromMenu(
            anchor = view,
            menuRes = R.menu.rss_main_item,
            previousPopup = menuPopup,
            prepare = {
                findItem(R.id.menu_login)?.isVisible = !rssSource.loginUrl.isNullOrBlank()
            }
        ) {
            when (it.itemId) {
                R.id.menu_edit -> callBack.edit(rssSource)
                R.id.menu_top -> callBack.toTop(rssSource)
                R.id.menu_login -> callBack.login(rssSource)
                R.id.menu_del -> callBack.del(rssSource)
                R.id.menu_disable -> callBack.disable(rssSource)
            }
            true
        }
    }

    interface CallBack {
        fun openRss(rssSource: RssSource)
        fun edit(rssSource: RssSource)
        fun toTop(rssSource: RssSource)
        fun login(rssSource: RssSource)
        fun del(rssSource: RssSource)
        fun disable(rssSource: RssSource)
    }
}
