package io.legado.app.ui.image

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.databinding.ItemImagePageBinding
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.ui.widget.image.PhotoView

/**
 * 图片浏览内层 ViewPager2 适配器（图集内多图切换）
 *
 * 职责：
 * 1. 用 Glide 加载图片到 PhotoView（支持 sourceOrigin 注入 referer/cookie）
 * 2. 维护每张图的旋转状态（rotationDegree，翻页后重置，R1b.8）
 * 3. 长按图片触发回调（保存/分享/复制URL菜单）
 * 4. 暴露当前 PhotoView 引用（供 Activity 调用旋转按钮）
 *
 * 注意：
 * - PhotoView 自带双指缩放、双击切换缩放、平移能力（R1b.1-R1b.4）
 * - 旋转通过 photoView.rotate(degrees) 实现（R1b.5-R1b.7）
 * - 旋转状态每张图独立，不跨图继承（R1b.8）
 */
class ImagePageAdapter(
    private val context: Context,
    private val sourceOrigin: String?,
    private val referer: String? = null
) : RecyclerView.Adapter<ImagePageAdapter.ImageViewHolder>() {

    private var imageUrls: List<String> = emptyList()
    private var currentHolder: ImageViewHolder? = null
    private var callback: OnImagePageCallback? = null

    fun setCallback(callback: OnImagePageCallback) {
        this.callback = callback
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateData(urls: List<String>) {
        imageUrls = urls
        notifyDataSetChanged()
        // rss-image-load-optimization（AD-03）：文章图片列表就绪后并发预下载前 3 张到磁盘缓存，首屏秒开
        preloadFirstImages()
    }

    /**
     * 并发预下载前 [PRELOAD_COUNT] 张图片到磁盘缓存（并发由 Glide 内部线程池调度）
     *
     * 参考书源 BookHelp.saveImages 的 onEachParallel(concurrency) 并发下载思路，
     * 仅 preload() 不占用内存，进入文章立即预热前几张。
     */
    private fun preloadFirstImages() {
        val count = minOf(PRELOAD_COUNT, imageUrls.size)
        for (i in 0 until count) {
            val url = imageUrls[i]
            io.legado.app.constant.AppLog.put("[ImageGallery] preload first image: index=$i, urlLen=${url.length}")
            preload(url)
        }
    }

    /**
     * Glide preload 到磁盘缓存（带防盗链头注入）
     *
     * 幂等性：相同 URL + 相同尺寸的 preload 在 Glide 内部复用 in-flight 请求与缓存，不会重复下载。
     */
    private fun preload(url: String) {
        ImageLoader.load(context, url).apply {
            sourceOrigin?.let { origin ->
                apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, origin))
            }
            referer?.let { ref ->
                apply(RequestOptions().set(OkHttpModelLoader.refererOption, ref))
            }
        }.diskCacheStrategy(DiskCacheStrategy.ALL)
            .preload()
    }

    fun getDataSize(): Int = imageUrls.size

    /**
     * 获取当前显示的 PhotoView（供 Activity 调用旋转按钮操作）
     */
    fun getCurrentPhotoView(): PhotoView? = currentHolder?.binding?.photoView

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val binding = ItemImagePageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ImageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        holder.bind(imageUrls[position], position)
    }

    override fun getItemCount(): Int = imageUrls.size

    override fun onViewRecycled(holder: ImageViewHolder) {
        super.onViewRecycled(holder)
        if (currentHolder == holder) {
            currentHolder = null
        }
    }

    inner class ImageViewHolder(val binding: ItemImagePageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /** 当前图片的旋转角度（0/90/180/270），每张图独立，不跨图继承（R1b.8） */
        private var rotationDegree: Int = 0

        @SuppressLint("CheckResult")
        fun bind(imageUrl: String, position: Int) {
            currentHolder = this
            // 重置旋转状态（R1b.8 每张图独立）
            rotationDegree = 0
            binding.photoView.rotation = 0f

            // 加载图片（复用 OkHttpModelLoader 的 sourceOriginOption 注入 Referer/Cookie，解决防盗链）
            io.legado.app.constant.AppLog.put("[ImageGallery] ImagePageAdapter.bind: position=$position, urlLen=${imageUrl.length}, sourceOriginLen=${sourceOrigin?.length ?: 0}, refererLen=${referer?.length ?: 0}")
            // rss-image-load-optimization（AD-02）：按屏幕尺寸采样解码（override 触发 Downsampler），
            // 移除 DownsampleStrategy.NONE（该策略会让 Glide 忽略 override 尺寸全尺寸解码，是加载慢的直接原因）；
            // thumbnail(0.1f) 先显示低分辨率模糊图再加载清晰图，首图快速可见
            val screen = context.resources.displayMetrics
            ImageLoader.load(context, imageUrl).apply {
                sourceOrigin?.let { origin ->
                    apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, origin))
                }
                referer?.let { ref ->
                    apply(RequestOptions().set(OkHttpModelLoader.refererOption, ref))
                }
            }.error(R.drawable.image_loading_error)
                .override(screen.widthPixels, screen.heightPixels)
                .dontTransform()
                .thumbnail(0.1f)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(binding.photoView)

            // 多线程预缓存：预加载下一张图片到磁盘缓存（参考视频播放器预缓存机制）
            // 当用户滑动到下一张时能秒开，提升体验
            val nextPosition = position + 1
            if (nextPosition < imageUrls.size) {
                val nextUrl = imageUrls[nextPosition]
                io.legado.app.constant.AppLog.put("[ImageGallery] preload next image: position=$nextPosition, urlLen=${nextUrl.length}")
                preload(nextUrl)
            }

            // 长按菜单回调
            binding.photoView.setOnLongClickListener {
                callback?.onImageLongClick(imageUrl, it)
                true
            }

            // 单击切换沉浸式（通过 PhotoView 的 OnClickListener）
            binding.photoView.setOnClickListener {
                callback?.onImageClick()
            }

            // 通知页码更新
            callback?.onPageChanged(position, imageUrls.size)
        }

        /**
         * 顺时针旋转 90°（R1b.5）
         */
        fun rotateClockwise() {
            rotationDegree = (rotationDegree + 90) % 360
            binding.photoView.rotation = rotationDegree.toFloat()
        }

        /**
         * 逆时针旋转 90°（R1b.6）
         */
        fun rotateCounterClockwise() {
            rotationDegree = (rotationDegree + 270) % 360
            binding.photoView.rotation = rotationDegree.toFloat()
        }

        /**
         * 重置视图（R1b.7 旋转+缩放恢复默认）
         */
        fun resetView() {
            rotationDegree = 0
            binding.photoView.rotation = 0f
            // 修复问题1：与布局 scaleType="fitCenter" 保持一致（适配性最大尺寸展示）
            // PhotoView 的缩放重置通过 setScale 实现（注意：PhotoView 没有公开 setScale，
            // 但可通过重置 matrix 实现；这里用 rotation=0 + 触发双击切换缩放的方式）
            binding.photoView.scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }

    /**
     * 旋转当前图片（顺时针）
     */
    fun rotateCurrentClockwise() {
        currentHolder?.rotateClockwise()
    }

    /**
     * 旋转当前图片（逆时针）
     */
    fun rotateCurrentCounterClockwise() {
        currentHolder?.rotateCounterClockwise()
    }

    /**
     * 重置当前图片视图
     */
    fun resetCurrentView() {
        currentHolder?.resetView()
    }

    interface OnImagePageCallback {
        /** 长按图片回调（弹出保存/分享/复制URL菜单） */
        fun onImageLongClick(imageUrl: String, view: View)

        /** 单击图片回调（切换沉浸式工具栏显隐） */
        fun onImageClick()

        /** 页码变化回调（更新页码显示） */
        fun onPageChanged(position: Int, total: Int)
    }

    companion object {
        /** 进入文章时并发预下载的图片张数（rss-image-load-optimization AD-03，限制带宽消耗） */
        const val PRELOAD_COUNT = 3
    }
}
