package io.legado.app.ui.image.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ItemImagePageBinding
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.ui.image.ImageCanvasItem
import io.legado.app.ui.image.ImagePlay
import io.legado.app.ui.widget.image.PhotoView

/**
 * 图片大图模式适配器（V4 实施 Phase 1.4）
 *
 * 设计参考：design.md §1.1 架构图 + AD-02 大图模式容器选择
 *
 * 与 ImagePageAdapter 的区别：
 * 1. 数据源：从 ImagePlay.allImageUrls 过滤 ImageItem（剥离 ArticleDivider）
 * 2. 加载原图：不限制尺寸（ImageCanvasAdapter 是缩略图模式 override）
 * 3. 长按保存：通过 OnImageDetailCallback 回调 Activity 处理（保存图片到相册）
 * 4. 旋转能力：保留 PhotoView 缩放/旋转/重置能力（迁移自 ImagePageAdapter）
 *
 * @param context Activity Context
 * @param sourceOrigin 订阅源 URL（用于 Referer 注入防盗链）
 * @param referer 文章页 URL（用于 Referer 注入）
 */
open class ImageDetailAdapter(
    private val context: Context,
    private val sourceOrigin: String?,
    private val referer: String? = null
) : RecyclerView.Adapter<ImageDetailAdapter.ImageDetailViewHolder>() {

    /** 当前图片 URL 列表（从 ImagePlay.allImageUrls 过滤 ImageItem） */
    private val imageItems: List<ImageCanvasItem.ImageItem> = ImagePlay.allImageUrls.value
        .filterIsInstance<ImageCanvasItem.ImageItem>()

    /** 当前 ViewHolder 引用（供 Activity 调用旋转按钮） */
    private var currentHolder: ImageDetailViewHolder? = null

    /** 长按/单击/翻页回调 */
    private var callback: OnImageDetailCallback? = null

    fun setCallback(callback: OnImageDetailCallback) {
        this.callback = callback
    }

    /** 获取图片总数 */
    fun getDataSize(): Int = imageItems.size

    /**
     * 获取当前显示的 PhotoView（供 Activity 旋转按钮操作）
     */
    fun getCurrentPhotoView(): PhotoView? = currentHolder?.binding?.photoView

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageDetailViewHolder {
        val binding = ItemImagePageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ImageDetailViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ImageDetailViewHolder, position: Int) {
        holder.bind(imageItems[position], position)
    }

    override fun getItemCount(): Int = imageItems.size

    override fun onViewRecycled(holder: ImageDetailViewHolder) {
        super.onViewRecycled(holder)
        if (currentHolder == holder) {
            currentHolder = null
        }
        // E6: 清理 Glide 资源避免内存泄漏（大图模式左右滑动时旧图片未释放）
        com.bumptech.glide.Glide.with(context).clear(holder.binding.photoView)
    }

    /**
     * 大图模式 ViewHolder（迁移自 ImagePageAdapter）
     *
     * 能力：
     * - PhotoView 双指缩放、双击切换缩放、平移（PhotoView 自带）
     * - 旋转按钮触发 photoView.rotate()（每张图独立 rotationDegree）
     * - 长按图片触发保存/分享/复制URL菜单回调
     * - 单击切换沉浸式工具栏显隐
     * - 加载原图（不含 override 限制尺寸）
     * - 预加载下一张图片到磁盘缓存（用户滑动到下一张时秒开）
     */
    inner class ImageDetailViewHolder(val binding: ItemImagePageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /** 当前图片的旋转角度（0/90/180/270），每张图独立（R1b.8） */
        private var rotationDegree: Int = 0

        @SuppressLint("CheckResult")
        fun bind(item: ImageCanvasItem.ImageItem, position: Int) {
            currentHolder = this
            // 重置旋转状态（R1b.8：每张图独立）
            rotationDegree = 0
            binding.photoView.rotation = 0f

            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_DETAIL,
                "bind position=$position articleIndex=${item.articleIndex} imageIndex=${item.imageIndex} urlLen=${item.url.length}",
                level = AppLog.Level.INFO
            )

            // 加载原图（不限制尺寸，区别于 ImageCanvasAdapter 的缩略图模式）
            ImageLoader.load(context, item.url).apply {
                sourceOrigin?.let { origin ->
                    apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, origin))
                }
                referer?.let { ref ->
                    apply(RequestOptions().set(OkHttpModelLoader.refererOption, ref))
                }
            }.error(R.drawable.image_loading_error)
                .dontTransform()
                .downsample(DownsampleStrategy.NONE)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(binding.photoView)

            // 预加载下一张图片到磁盘缓存（用户滑动到下一张时秒开）
            val nextPosition = position + 1
            if (nextPosition < imageItems.size) {
                val nextUrl = imageItems[nextPosition].url
                ImageLoader.load(context, nextUrl).apply {
                    sourceOrigin?.let { origin ->
                        apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, origin))
                    }
                    referer?.let { ref ->
                        apply(RequestOptions().set(OkHttpModelLoader.refererOption, ref))
                    }
                }.diskCacheStrategy(DiskCacheStrategy.ALL)
                    .preload()
            }

            // 长按菜单回调（保存/分享/复制URL）
            binding.photoView.setOnLongClickListener {
                callback?.onImageLongClick(item.url, it)
                true
            }

            // 单击切换沉浸式（隐藏/显示工具栏）
            binding.photoView.setOnClickListener {
                callback?.onImageClick()
            }

            // 通知页码更新（"文章N/M 图片X/Y"）
            callback?.onPageChanged(position, imageItems.size)
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
         * 重置视图（R1b.7：旋转+缩放恢复默认）
         */
        fun resetView() {
            rotationDegree = 0
            binding.photoView.rotation = 0f
            // 与布局 scaleType="fitCenter" 保持一致（适配性最大尺寸展示）
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

    /**
     * 大图模式回调接口（与 ImagePageAdapter.OnImagePageCallback 一致）
     */
    interface OnImageDetailCallback {
        /** 长按图片回调（弹出保存/分享/复制URL菜单） */
        fun onImageLongClick(imageUrl: String, view: View)

        /** 单击图片回调（切换沉浸式工具栏显隐） */
        fun onImageClick()

        /** 页码变化回调（更新 TitleBar 页码 "文章N/M 图片X/Y"） */
        fun onPageChanged(position: Int, total: Int)
    }
}
