package io.legado.app.ui.image

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.legado.app.data.entities.RssArticle
import io.legado.app.databinding.ItemImageArticleBinding

/**
 * 图片浏览外层 ViewPager2 适配器（跨文章切换，垂直方向）
 *
 * 架构参考 VideoPlayerActivity 的 ViewPager2 嵌套模式：
 * - 外层 ViewPager2（垂直）：跨文章切换（上下滑动）
 * - 内层 ViewPager2（水平）：图集内多图切换（左右滑动）
 *
 * 数据流：
 * - Activity 观察 ViewModel.imageUrlsLiveData，收到数据后调用 updateCurrentArticle(urls)
 * - Activity 观察 ViewModel.loadingLiveData，调用 showLoading(loading)
 * - 切换文章时调用 ViewModel.loadArticleContent(article) 触发加载
 */
class ImageArticlePagerAdapter(
    private val context: Context
) : RecyclerView.Adapter<ImageArticlePagerAdapter.ArticleViewHolder>() {

    private val articles: List<RssArticle> = ImagePlay.rssArticles ?: emptyList()
    private var currentHolder: ArticleViewHolder? = null
    private var callback: OnArticleCallback? = null

    fun setCallback(callback: OnArticleCallback) {
        this.callback = callback
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArticleViewHolder {
        val binding = ItemImageArticleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ArticleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ArticleViewHolder, position: Int) {
        holder.bind(articles.getOrNull(position) ?: return, position)
    }

    override fun getItemCount(): Int = articles.size

    override fun onViewRecycled(holder: ArticleViewHolder) {
        super.onViewRecycled(holder)
        if (currentHolder == holder) {
            currentHolder = null
        }
    }

    /**
     * 更新当前文章的图片URL列表（由 Activity 在收到 ViewModel.imageUrlsLiveData 后调用）
     */
    fun updateCurrentArticle(urls: List<String>) {
        currentHolder?.updateImages(urls)
    }

    /**
     * 显示/隐藏加载进度（由 Activity 在收到 ViewModel.loadingLiveData 后调用）
     */
    fun showLoading(loading: Boolean) {
        currentHolder?.showLoading(loading)
    }

    /**
     * 获取当前内层适配器（供 Activity 调用旋转操作）
     */
    fun getCurrentPageAdapter(): ImagePageAdapter? = currentHolder?.imagePageAdapter

    inner class ArticleViewHolder(val binding: ItemImageArticleBinding) :
        RecyclerView.ViewHolder(binding.root) {

        var imagePageAdapter: ImagePageAdapter? = null
        private var currentArticle: RssArticle? = null

        fun bind(article: RssArticle, position: Int) {
            currentHolder = this
            currentArticle = article

            // 修复：优先使用 ImagePlay.rssSource.sourceUrl 作为 sourceOrigin，确保 header/cookie 注入
            // article.origin 可能为空或与订阅源 sourceUrl 不一致，导致防盗链失败
            val sourceOrigin = ImagePlay.rssSource?.sourceUrl ?: article.origin
            // 修复（image-gallery）：图片防盗链失败，网页模式 WebView 自动带文章页 URL 作为 Referer
            // 图片模式 Glide 需手动注入 Referer，用文章页 URL（article.link）作为 Referer
            // 注意：article.origin 是订阅源 sourceUrl，article.link 才是文章页 URL
            val referer = article.link
            io.legado.app.constant.AppLog.put("[ImageGallery] ArticleViewHolder.bind: sourceOriginLen=${sourceOrigin?.length ?: 0}, articleOriginLen=${article.origin?.length ?: 0}, refererLen=${referer?.length ?: 0}, articleLinkLen=${article.link?.length ?: 0}")

            // 初始化内层 ViewPager2（水平方向，图集内多图切换）
            if (imagePageAdapter == null) {
                imagePageAdapter = ImagePageAdapter(context, sourceOrigin, referer)
                binding.vpImage.adapter = imagePageAdapter
                binding.vpImage.orientation = ViewPager2.ORIENTATION_HORIZONTAL
            } else {
                // 复用 ViewHolder 时，更新 sourceOrigin
                imagePageAdapter = ImagePageAdapter(context, sourceOrigin, referer)
                binding.vpImage.adapter = imagePageAdapter
            }

            // 通知 Activity 加载文章内容
            callback?.onArticleBind(article, position)

            // 内层 ViewPager2 页码变化回调
            imagePageAdapter?.setCallback(object : ImagePageAdapter.OnImagePageCallback {
                override fun onImageLongClick(imageUrl: String, view: View) {
                    callback?.onImageLongClick(imageUrl, view)
                }

                override fun onImageClick() {
                    callback?.onImageClick()
                }

                override fun onPageChanged(position: Int, total: Int) {
                    callback?.onImagePageChanged(position, total)
                }
            })
        }

        fun updateImages(urls: List<String>) {
            imagePageAdapter?.updateData(urls)
            showLoading(false)
        }

        fun showLoading(loading: Boolean) {
            binding.progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
        }
    }

    interface OnArticleCallback {
        /** 文章绑定回调（触发 ViewModel.loadArticleContent） */
        fun onArticleBind(article: RssArticle, position: Int)

        /** 图片长按回调 */
        fun onImageLongClick(imageUrl: String, view: View)

        /** 图片单击回调（切换沉浸式） */
        fun onImageClick()

        /** 内层页码变化回调 */
        fun onImagePageChanged(position: Int, total: Int)
    }
}
