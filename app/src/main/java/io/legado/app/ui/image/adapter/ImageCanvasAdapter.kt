package io.legado.app.ui.image.adapter

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ItemImageCanvasBinding
import io.legado.app.databinding.ItemImageCanvasDividerBinding
import io.legado.app.databinding.ItemImageCanvasFooterBinding
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.ui.image.ImageCanvasItem
import io.legado.app.ui.image.ImagePlay

/**
 * 图片垂直画布 RecyclerView 适配器（V4 实施 Phase 1.2）
 *
 * 设计参考：design.md §5.1 ImageCanvasAdapter 多 ViewType 实现
 *
 * 5 种 ViewType：
 * - TYPE_IMAGE：图片项（PhotoView + 高度自适应）
 * - TYPE_LOADING：加载中（ProgressBar）
 * - TYPE_ERROR：加载失败（错误文本 + 重试按钮）
 * - TYPE_NO_MORE：没有更多了（文本提示）
 * - TYPE_ARTICLE_DIVIDER：文章分隔符（"—— 下一篇 ——" + 文章标题）
 *
 * 数据源：从 ImagePlay.allImageUrls（StateFlow）读取快照，避免双重数据源同步问题。
 *
 * 列表 position ↔ 大图 imageIndex 双向映射（design.md AD-10）：
 * - 列表 position 包含 ImageItem + ArticleDivider
 * - 大图 ViewPager2 position 是纯图片索引
 *
 * 日志规范（tasks.md §AOAdapt 日志模板）：
 * - 永久日志：AppLog.putDebugWithTag + TAG_IMAGE_CANVAS（受 BuildConfig.DEBUG 控制）
 */
class ImageCanvasAdapter(
    private val onItemClick: (listPosition: Int, sharedView: View) -> Unit,
    private val onRetryClick: () -> Unit,
    /** 降级3 回调：WebView 即时预热（V4 6.1.3） */
    private val onWebViewFallback: (url: String, position: Int) -> Unit = { _, _ -> },
    /** 降级4 回调：网页模式回退（V4 6.1.4） */
    private val onWebModeFallback: (articleIndex: Int) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_IMAGE = 0
        const val TYPE_LOADING = 1
        const val TYPE_ERROR = 2
        const val TYPE_NO_MORE = 3
        const val TYPE_ARTICLE_DIVIDER = 4

        /** 分页加载触发阈值（剩余未可见项数 ≤ 3 时触发下一篇加载） */
        const val PAGINATION_THRESHOLD = 3
    }

    /**
     * 加载状态（design.md §3.3 数据流 3：分页加载流）
     *
     * - IDLE：初始状态（无数据 + 未加载）
     * - LOADING：正在加载下一篇
     * - SUCCESS：加载成功（footer 隐藏）
     * - ERROR：加载失败（显示重试按钮）
     * - NO_MORE：没有更多文章了
     */
    sealed class LoadState {
        object IDLE : LoadState()
        object LOADING : LoadState()
        object SUCCESS : LoadState()
        data class ERROR(val error: Throwable) : LoadState()
        object NO_MORE : LoadState()
    }

    /** 当前加载状态（影响 footer 显示） */
    @Volatile
    private var currentLoadState: LoadState = LoadState.IDLE

    /** 当前图片 URL 快照（从 ImagePlay.allImageUrls 读取，避免双重数据源） */
    private val snapshot: List<ImageCanvasItem>
        get() = ImagePlay.allImageUrls.value

    /**
     * 更新加载状态并刷新 footer
     *
     * @param state 新的加载状态
     */
    fun setLoadState(state: LoadState) {
        val oldState = currentLoadState
        currentLoadState = state
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_CANVAS,
            "LoadState: ${state.javaClass.simpleName} <- ${oldState.javaClass.simpleName}",
            level = AppLog.Level.INFO
        )
        // 刷新 footer 区域（最后一个 position）
        val footerPos = snapshot.size
        if (footerPos >= 0) {
            notifyItemChanged(footerPos)
        }
    }

    override fun getItemCount(): Int {
        // footer 始终占位（SUCCESS 状态下隐藏内容，避免布局抖动）
        return snapshot.size + 1
    }

    override fun getItemViewType(position: Int): Int {
        // footer 区域
        if (position >= snapshot.size) {
            return when (currentLoadState) {
                is LoadState.LOADING -> TYPE_LOADING
                is LoadState.ERROR -> TYPE_ERROR
                is LoadState.NO_MORE -> TYPE_NO_MORE
                // IDLE / SUCCESS 状态下 footer 显示空 ProgressBar（避免空白闪烁）
                else -> TYPE_LOADING
            }
        }
        // 数据区域
        return when (snapshot[position]) {
            is ImageCanvasItem.ImageItem -> TYPE_IMAGE
            is ImageCanvasItem.ArticleDivider -> TYPE_ARTICLE_DIVIDER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_IMAGE -> ImageViewHolder(
                ItemImageCanvasBinding.inflate(inflater, parent, false),
                onItemClick
            )
            TYPE_LOADING, TYPE_ERROR, TYPE_NO_MORE -> FooterViewHolder(
                ItemImageCanvasFooterBinding.inflate(inflater, parent, false),
                onRetryClick
            )
            TYPE_ARTICLE_DIVIDER -> ArticleDividerViewHolder(
                ItemImageCanvasDividerBinding.inflate(inflater, parent, false)
            )
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ImageViewHolder -> {
                val item = snapshot[position] as? ImageCanvasItem.ImageItem ?: return
                holder.bind(item, position)
            }
            is ArticleDividerViewHolder -> {
                val item = snapshot[position] as? ImageCanvasItem.ArticleDivider ?: return
                holder.bind(item)
            }
            is FooterViewHolder -> {
                holder.bind(currentLoadState)
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is ImageViewHolder) {
            holder.onRecycled()
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "onViewRecycled position=${holder.bindingAdapterPosition} hashCode=${holder.hashCode()}",
                level = AppLog.Level.INFO
            )
        }
    }

    // ==================== 列表 position ↔ 大图 imageIndex 双向映射（design.md AD-10） ====================

    /**
     * 列表 position → 图片索引（用于点击缩略图进入大图模式）
     *
     * @param listPos RecyclerView 中的位置（含 ArticleDivider）
     * @return 图片索引（0-based，纯 ImageItem 计数）；-1 表示该位置不是图片（如 ArticleDivider 或越界）
     */
    fun listPositionToImageIndex(listPos: Int): Int {
        if (listPos !in snapshot.indices) {
            return -1
        }
        if (snapshot[listPos] !is ImageCanvasItem.ImageItem) {
            return -1
        }
        var imageIdx = -1
        for (i in 0..listPos) {
            if (snapshot[i] is ImageCanvasItem.ImageItem) imageIdx++
        }
        return imageIdx
    }

    /**
     * 图片索引 → 列表 position（用于大图模式返回列表时滚动到正确位置）
     *
     * @param imageIdx 图片索引（纯 ImageItem 计数）
     * @return 列表 position（含 ArticleDivider）；-1 表示未找到
     */
    fun imageIndexToListPosition(imageIdx: Int): Int {
        var count = -1
        for (i in snapshot.indices) {
            if (snapshot[i] is ImageCanvasItem.ImageItem) count++
            if (count == imageIdx) {
                return i
            }
        }
        return -1
    }

    // ==================== 图片项 ViewHolder（design.md AD-03/AD-08/AD-13） ====================

    /**
     * 图片项 ViewHolder
     *
     * 设计参考：
     * - AD-03：图片高度自适应（默认 60% 屏幕高，加载后按宽高比调整）
     * - AD-08：缩略图模式（Glide.override 限制尺寸）
     * - AD-13：ViewHolder 复用闪烁修复（重置默认高度 + 清理 Glide）
     * - AD-06：错误降级链触发（onLoadFailed 调用 triggerFallbackChain）
     */
    inner class ImageViewHolder(
        val binding: ItemImageCanvasBinding,
        private val onItemClick: (listPosition: Int, sharedView: View) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        /** 当前绑定的图片 URL（用于 Glide 失败时触发降级链） */
        private var currentUrl: String? = null

        /** 当前绑定的列表 position（用于点击回调） */
        private var currentPosition: Int = -1

        /** E1 降级链：当前重试次数（0=首次加载，1=降级1重试，2=降级2重试，3+=降级链耗尽） */
        private var retryCount: Int = 0

        /** 当前绑定的 ImageItem（用于降级链重试时获取 articleIndex） */
        private var currentItem: ImageCanvasItem.ImageItem? = null

        /** W7: 从 ImagePlay.rssSource?.header 提取的 header Map（可能为 null） */
        private var sourceHeaderMap: Map<String, String>? = null

        /**
         * 绑定图片数据
         */
        fun bind(item: ImageCanvasItem.ImageItem, position: Int) {
            currentUrl = item.url
            currentPosition = position
            currentItem = item
            retryCount = 0  // 新绑定重置降级链

            // AD-13: 重置为默认高度（避免旧图片实际高度残留导致闪烁）
            val defaultHeight = (itemView.resources.displayMetrics.heightPixels * 0.6).toInt()
            val lp = itemView.layoutParams
            lp.height = defaultHeight
            itemView.layoutParams = lp

            // AD-13: 清空旧图片（避免 ViewHolder 复用时显示上一张图）
            Glide.with(itemView.context).clear(binding.photoView)

            // 共享元素动画 transitionName（与 ImageDetailActivity 接收端匹配）
            binding.photoView.transitionName = "shared_image_$position"

            val sourceOrigin = ImagePlay.rssSource?.sourceUrl
            val articleLink = ImagePlay.rssArticles?.getOrNull(item.articleIndex)?.link

            // W7: 从 ImagePlay.rssSource?.header 提取 header Map
            // 注：OkHttpStreamFetcher 已通过 sourceOriginOption → SourceHelp.getSource → AnalyzeUrl
            // 自动注入 source.header 全部字段（含 UA / Cookie 等）。
            // 这里提取主要用于 Referer 优先级：source.header 中的 Referer 优先于文章页 URL 兜底。
            sourceHeaderMap = ImagePlay.rssSource?.getHeaderMap()
            val effectiveReferer = extractReferer(sourceHeaderMap) ?: articleLink

            // AD-08: Glide 加载缩略图（override 限制尺寸，避免内存压力）
            // 使用 OkHttpModelLoader.sourceOriginOption/refererOption 注入防盗链头
            val requestOptions = buildRequestOptions(sourceOrigin, effectiveReferer, skipMemory = false)
            loadImage(item.url, requestOptions, defaultHeight, lp, position)

            // W3: 预热相邻图片（前后各1张，共3张含当前），用户滚动时直接命中缓存
            preloadAdjacentImages(position)

            // 点击进入大图模式（共享元素动画由 Activity 处理）
            itemView.setOnClickListener {
                onItemClick(position, binding.photoView)
            }
        }

        /**
         * W7: 从 header Map 提取 Referer（大小写不敏感）
         */
        private fun extractReferer(map: Map<String, String>?): String? {
            if (map.isNullOrEmpty()) return null
            return map["Referer"] ?: map["referer"] ?: map["REFERER"]
        }

        /**
         * W7: 构建 RequestOptions
         * - sourceOriginOption：触发 OkHttpStreamFetcher 通过 AnalyzeUrl 自动注入 source.header 全部字段
         * - refererOption：当 source.header 无 Referer 时，用文章页 URL 兜底防盗链
         * - skipMemory：降级1 重试时 true，跳过内存缓存强制重新拉取
         */
        private fun buildRequestOptions(
            sourceOrigin: String?,
            referer: String?,
            skipMemory: Boolean
        ): RequestOptions {
            return RequestOptions().apply {
                sourceOrigin?.let { set(OkHttpModelLoader.sourceOriginOption, it) }
                referer?.let { set(OkHttpModelLoader.refererOption, it) }
                if (skipMemory) {
                    skipMemoryCache(true)
                }
            }
        }

        /**
         * 执行 Glide 加载（封装以便降级链复用）
         */
        private fun loadImage(
            url: String,
            requestOptions: RequestOptions,
            defaultHeight: Int,
            lp: ViewGroup.LayoutParams,
            position: Int
        ) {
            ImageLoader.load(itemView.context, url)
                .apply(requestOptions)
                .override(itemView.resources.displayMetrics.widthPixels, defaultHeight * 2)
                .dontTransform()
                .downsample(DownsampleStrategy.NONE)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .listener(object : RequestListener<Drawable> {
                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        // AD-03: 动态计算实际高度（width * bitmap.height / bitmap.width）
                        val bitmap = (resource as? BitmapDrawable)?.bitmap ?: return false
                        val screenWidth = itemView.resources.displayMetrics.widthPixels
                        val actualHeight = screenWidth * bitmap.height / bitmap.width
                        // 限制最大高度为屏幕高度 4 倍（避免超长图导致单个 ViewHolder 占用过高）
                        val maxHeight = itemView.resources.displayMetrics.heightPixels * 4
                        val limitedHeight = actualHeight.coerceAtMost(maxHeight)
                        if (lp.height != limitedHeight) {
                            lp.height = limitedHeight
                            itemView.layoutParams = lp
                        }
                        // 加载成功，重置降级链计数 + 隐藏降级提示
                        retryCount = 0
                        hideFallbackHint()
                        return false
                    }

                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>?,
                        isFirstResource: Boolean
                    ): Boolean {
                        // AD-13: 加载失败时设置错误高度（屏幕高度 40%）
                        val errLp = itemView.layoutParams
                        errLp.height = (itemView.resources.displayMetrics.heightPixels * 0.4).toInt()
                        itemView.layoutParams = errLp
                        // AD-06 + E1: 触发四级降级链
                        triggerFallbackChain(e, position)
                        // 返回 true 表示已自行处理（降级链会重新加载或保持空白等待重试）
                        return true
                    }
                })
                .into(binding.photoView)
        }

        /**
         * E1 四级降级链（V4 6.1.3-6.1.5 完整实施）
         *
         * - 降级1 (retryCount==0): Glide 重试（skipMemoryCache(true) + 延迟 500ms 重试一次）
         * - 降级2 (retryCount==1): OkHttp + Cookie 兜底（通过 sourceOriginOption 注入 rssSource，
         *   OkHttpStreamFetcher 经 AnalyzeUrl 自动启用 enabledCookieJar；并跳过磁盘缓存强制重新拉取）
         * - 降级3 (retryCount==2): WebView 即时预热（V4 6.1.3：通过 onWebViewFallback 回调让 Activity
         *   启动隐藏 WebView 加载图片 URL，onPageFinished 后 CookieManager.flush() + 重试 Glide）
         * - 降级4 (retryCount>=3): 网页模式回退（V4 6.1.4：通过 onWebModeFallback 回调让 Activity
         *   弹出 alert {} DSL 询问用户是否切换到网页模式，确认后跳转 ReadRssActivity）
         *
         * V4 6.1.5 UI 提示：每级降级时显示 tv_fallback_hint "正在尝试备用方案 N/4..."
         *
         * @param e Glide 抛出的异常
         * @param position 当前列表 position
         */
        private fun triggerFallbackChain(e: GlideException?, position: Int) {
            val url = currentUrl ?: return
            val item = currentItem ?: return
            val sourceOrigin = ImagePlay.rssSource?.sourceUrl
            val articleLink = ImagePlay.rssArticles?.getOrNull(item.articleIndex)?.link
            val effectiveReferer = extractReferer(sourceHeaderMap) ?: articleLink
            val defaultHeight = (itemView.resources.displayMetrics.heightPixels * 0.6).toInt()

            when (retryCount) {
                0 -> {
                    // 降级1: Glide 重试（skipMemoryCache(true) + 延迟 500ms）
                    retryCount = 1
                    showFallbackHint(1)
                    AppLog.putDebugWithTag(
                        AppLog.TAG_IMAGE_CANVAS,
                        "E1 fallback-1 retryWithSkipMemory position=$position url=/path/${url.hashCode()} reason=${e?.message?.take(80)}",
                        level = AppLog.Level.WARN
                    )
                    val lp = itemView.layoutParams
                    binding.photoView.postDelayed({
                        // ViewHolder 已复用绑定其他图片，放弃重试
                        if (currentUrl != url) return@postDelayed
                        Glide.with(itemView.context).clear(binding.photoView)
                        val opts = buildRequestOptions(sourceOrigin, effectiveReferer, skipMemory = true)
                        loadImage(url, opts, defaultHeight, lp, position)
                    }, 500)
                }
                1 -> {
                    // 降级2: OkHttp + Cookie 兜底
                    // 通过 sourceOriginOption 注入 rssSource，OkHttpStreamFetcher 经 AnalyzeUrl
                    // 自动启用 enabledCookieJar（rssSource.enabledCookieJar 默认 true）
                    // 并跳过磁盘缓存强制走 OkHttp 全链路重新拉取
                    retryCount = 2
                    showFallbackHint(2)
                    AppLog.putDebugWithTag(
                        AppLog.TAG_IMAGE_CANVAS,
                        "E1 fallback-2 okHttpWithCookie position=$position url=/path/${url.hashCode()} headerSize=${sourceHeaderMap?.size ?: 0}",
                        level = AppLog.Level.WARN
                    )
                    val lp = itemView.layoutParams
                    binding.photoView.postDelayed({
                        if (currentUrl != url) return@postDelayed
                        Glide.with(itemView.context).clear(binding.photoView)
                        val opts = buildRequestOptions(sourceOrigin, effectiveReferer, skipMemory = true)
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                        loadImage(url, opts, defaultHeight, lp, position)
                    }, 500)
                }
                2 -> {
                    // 降级3: WebView 即时预热（V4 6.1.3）
                    // 通过回调让 Activity 启动隐藏 WebView 加载图片 URL
                    // onPageFinished 后 CookieManager.flush() 同步 cookies，然后重试 Glide
                    retryCount = 3
                    showFallbackHint(3)
                    AppLog.putDebugWithTag(
                        AppLog.TAG_IMAGE_CANVAS,
                        "E1 fallback-3 webviewPreheat position=$position url=/path/${url.hashCode()} reason=${e?.message?.take(80)}",
                        level = AppLog.Level.WARN
                    )
                    onWebViewFallback(url, position)
                }
                else -> {
                    // 降级4: 网页模式回退（V4 6.1.4）
                    // 通过回调让 Activity 弹出 alert DSL 询问用户是否切换到网页模式
                    retryCount = 4
                    showFallbackHint(4)
                    AppLog.putDebugWithTag(
                        AppLog.TAG_IMAGE_CANVAS,
                        "E1 fallback-4 webModeFallback position=$position articleIndex=${item.articleIndex} url=/path/${url.hashCode()} reason=${e?.message?.take(80)}",
                        level = AppLog.Level.ERROR
                    )
                    onWebModeFallback(item.articleIndex)
                }
            }
        }

        /**
         * 显示降级链级别提示（V4 6.1.5）
         *
         * @param level 当前降级级别（1-4）
         */
        private fun showFallbackHint(level: Int) {
            val hint = when (level) {
                1 -> "正在尝试备用方案 1/4：刷新缓存重试..."
                2 -> "正在尝试备用方案 2/4：Cookie 兜底加载..."
                3 -> "正在尝试备用方案 3/4：WebView 预热中..."
                4 -> "正在尝试备用方案 4/4：建议切换网页模式"
                else -> return
            }
            binding.tvFallbackHint.text = hint
            binding.tvFallbackHint.visibility = View.VISIBLE
        }

        /**
         * 隐藏降级链级别提示（加载成功时调用）
         */
        private fun hideFallbackHint() {
            binding.tvFallbackHint.visibility = View.GONE
        }

        /**
         * W3: 预热相邻图片（前后各1张，共3张含当前）
         *
         * 利用 Glide.preload 提前下载并缓存相邻图片，用户滚动时可直接命中缓存。
         * 数据源：从 ImagePlay.allImageUrls.value 获取相邻 ImageItem 的 url
         */
        private fun preloadAdjacentImages(currentPosition: Int) {
            val snapshot = ImagePlay.allImageUrls.value
            val context = itemView.context
            val sourceOrigin = ImagePlay.rssSource?.sourceUrl
            val preloadWidth = itemView.resources.displayMetrics.widthPixels
            val preloadHeight = (itemView.resources.displayMetrics.heightPixels * 0.6).toInt() * 2
            // 预热 position-1 和 position+1（仅 ImageItem 类型，跳过 ArticleDivider）
            listOf(currentPosition - 1, currentPosition + 1).forEach { idx ->
                if (idx !in snapshot.indices) return@forEach
                val adjItem = snapshot[idx] as? ImageCanvasItem.ImageItem ?: return@forEach
                val articleLink = ImagePlay.rssArticles?.getOrNull(adjItem.articleIndex)?.link
                val effectiveReferer = extractReferer(sourceHeaderMap) ?: articleLink
                val opts = RequestOptions().apply {
                    sourceOrigin?.let { set(OkHttpModelLoader.sourceOriginOption, it) }
                    effectiveReferer?.let { set(OkHttpModelLoader.refererOption, it) }
                }
                kotlin.runCatching {
                    ImageLoader.load(context, adjItem.url)
                        .apply(opts)
                        .override(preloadWidth, preloadHeight)
                        .preload()
                }.onFailure { t ->
                    AppLog.putDebugWithTag(
                        AppLog.TAG_IMAGE_CANVAS,
                        "preload fail idx=$idx err=${t.message?.take(50)}",
                        level = AppLog.Level.WARN
                    )
                }
            }
        }

        /**
         * ViewHolder 被回收时清理 Glide 资源（AD-13）
         */
        fun onRecycled() {
            Glide.with(itemView.context).clear(binding.photoView)
            currentUrl = null
            currentPosition = -1
            currentItem = null
            sourceHeaderMap = null
            retryCount = 0
        }
    }

    // ==================== Footer ViewHolder（加载状态指示器） ====================

    /**
     * Footer ViewHolder（加载中 / 加载失败 / 没有更多了）
     *
     * 根据 LoadState 切换子 View 可见性
     */
    class FooterViewHolder(
        val binding: ItemImageCanvasFooterBinding,
        private val onRetryClick: () -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.btnRetry.setOnClickListener { onRetryClick() }
        }

        fun bind(state: LoadState) {
            // 重置所有子 View 可见性
            binding.progressLoading.visibility = View.GONE
            binding.tvError.visibility = View.GONE
            binding.btnRetry.visibility = View.GONE
            binding.tvNoMore.visibility = View.GONE

            when (state) {
                is LoadState.LOADING -> {
                    binding.progressLoading.visibility = View.VISIBLE
                }
                is LoadState.ERROR -> {
                    binding.tvError.visibility = View.VISIBLE
                    binding.tvError.text = state.error.message ?: "加载失败"
                    binding.btnRetry.visibility = View.VISIBLE
                }
                is LoadState.NO_MORE -> {
                    binding.tvNoMore.visibility = View.VISIBLE
                }
                // IDLE / SUCCESS 状态下 footer 隐藏所有内容
                else -> {
                    // 全部 GONE（已在上方重置）
                }
            }
        }
    }

    // ==================== 文章分隔符 ViewHolder ====================

    /**
     * 文章分隔符 ViewHolder（R2.7 文章边界分隔符）
     *
     * 显示 "—— 下一篇 ——" + 文章标题
     */
    class ArticleDividerViewHolder(
        val binding: ItemImageCanvasDividerBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ImageCanvasItem.ArticleDivider) {
            val title = item.articleTitle
            binding.tvDividerTitle.text = if (title.isNullOrBlank()) {
                "—— 下一篇 ——"
            } else {
                "—— $title ——"
            }
        }
    }
}
