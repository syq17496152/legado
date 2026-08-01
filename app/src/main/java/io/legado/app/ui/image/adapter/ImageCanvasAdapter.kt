package io.legado.app.ui.image.adapter

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.FutureTarget
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ItemImageCanvasBinding
import io.legado.app.databinding.ItemImageCanvasDividerBinding
import io.legado.app.databinding.ItemImageCanvasFooterBinding
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.ui.image.ImageCanvasItem
import io.legado.app.ui.image.ImagePlay
import io.legado.app.ui.image.ImagePyramidLoader
import java.io.File

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

    // ==================== I-P0-1：显式防盗链头（与 ImagePlay 全局态解耦） ====================

    /**
     * I-P0-1: 显式持有的订阅源 URL（Activity 启动时传入）
     *
     * 根因：原实现 bind/preload/降级链全部实时读 ImagePlay 全局态，全局态被
     * 其他入口（视频播放/二次进入/clear）污染或时序错位时头注入静默缺失 → 图床 403。
     * 显式传参后取值来源确定，ImagePlay 仅作兜底。
     */
    private var explicitSourceOrigin: String? = null

    /** I-P0-1: 显式持有的文章索引 → 文章 link 映射（referer 兜底用） */
    private var explicitArticleLinks: Map<Int, String>? = null

    /** I-P0-1: 403 计数（同 URL 去重，避免刷屏；key=url hash 脱敏） */
    private val http403UrlHashes = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /** I-P0-2: WebView 预热完成后待重载的 position 集合
     *
     * 流程：降级3 触发预热（Activity 登记 position）→ 预热完成/超时（Activity 调
     * [markPreheatReload]）→ bind 时命中集合 → bypass failUrl + skipMemory 强制重新拉取
     * （此时 CookieManager 已 flush，enabledCookieJar 可携 WebView cookies）。
     */
    private val preheatReloadPositions = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /**
     * 修复（image-canvas-3fix-20260728 Q3循环修复）：已预热 URL hash 集合
     *
     * 根因：onRecycled 重置 retryCount=0，markPreheatReload 触发 notifyItemChanged →
     * ViewHolder 被回收再创建时 retryCount=0 → 预热重载后从 fallback-1 重新开始 →
     * fallback-1→2→3→预热→重载→fallback-1→2→3→预热...无限循环。
     * 铁证：009 日志 L2114-2491 position=3 在 17 秒内触发 4 次 fallback-3 webviewPreheat，
     * L2219 "retryCount preserved=0" 证明 onRecycled 重置了 retryCount。
     *
     * 修复：preheatedUrlHashes 独立于 ViewHolder 生命周期（url hash 为 key），
     * 同一 URL 只能预热一次，已预热则跳过 fallback-3 直接进入 fallback-4（网页模式回退）。
     */
    private val preheatedUrlHashes = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /**
     * I-P0-2: 预热完成后登记待重载 position 并触发 re-bind
     *
     * @param positions 预热域名下此前加载失败的列表 position
     */
    fun markPreheatReload(positions: Collection<Int>) {
        if (positions.isEmpty()) return
        preheatReloadPositions.addAll(positions)
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_CANVAS,
            "ImageFallback: preheat reload scheduled positions=${positions.sorted()}",
            level = AppLog.Level.INFO
        )
        positions.forEach { notifyItemChanged(it) }
    }

    /**
     * I-P0-1: Activity 启动时显式传入防盗链头数据
     *
     * @param sourceOrigin 订阅源 URL（触发 OkHttpStreamFetcher 经 AnalyzeUrl 注入 source.header）
     * @param articleLinks 文章索引 → 文章 link 映射（Referer 兜底）
     */
    fun setAntiLeechHeaders(sourceOrigin: String?, articleLinks: Map<Int, String>?) {
        explicitSourceOrigin = sourceOrigin
        explicitArticleLinks = articleLinks
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_CANVAS,
            "I-P0-1 setAntiLeechHeaders: hasOrigin=${sourceOrigin != null} linkCount=${articleLinks?.size ?: 0}",
            level = AppLog.Level.INFO
        )
    }

    /** 取值：显式优先，ImagePlay 全局态兜底 */
    private fun resolveSourceOrigin(): String? =
        explicitSourceOrigin ?: ImagePlay.rssSource?.sourceUrl

    /** 取值：显式映射优先，ImagePlay 全局态兜底 */
    private fun resolveArticleLink(articleIndex: Int): String? =
        explicitArticleLinks?.get(articleIndex)
            ?: ImagePlay.rssArticles?.getOrNull(articleIndex)?.link

    /**
     * I-P0-1: 头缺失 WARN 日志（bind/降级时调用一次）
     *
     * 输出格式对齐 design.md：headers missing, pos=N, articleIndex=M, hasSource=X, hasArticles=Y
     */
    private fun logHeadersMissing(tagPos: Int, articleIndex: Int) {
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_CANVAS,
            "ImageLoad: headers missing, pos=$tagPos, articleIndex=$articleIndex, " +
                "hasSource=${resolveSourceOrigin() != null}, " +
                "hasArticles=${ImagePlay.rssArticles?.isNotEmpty() == true}",
            level = AppLog.Level.WARN
        )
    }

    /**
     * I-P0-1: 403 计数日志（同 URL 去重）
     *
     * @return true=本次为 403 且首次记录（已打日志）
     */
    private fun log403Once(e: GlideException?, position: Int, url: String): Boolean {
        val is403 = e?.rootCauses?.any {
            it is com.bumptech.glide.load.HttpException && it.statusCode == 403
        } == true
        if (!is403) return false
        val urlHash = url.hashCode()
        if (http403UrlHashes.add(urlHash)) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "ImageLoad: 403 count=${http403UrlHashes.size}, pos=$position, url=/path/$urlHash",
                level = AppLog.Level.ERROR
            )
        }
        return true
    }

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
            TYPE_IMAGE -> {
                val binding = ItemImageCanvasBinding.inflate(inflater, parent, false)
                // 修复（image-canvas-3fix-20260728 Q1）：强制创建新 LayoutParams
                // 根因：原 apply { height = defaultHeight } 修改原 layoutParams 对象，
                // 但 RecyclerView 的 LayoutManager 在后续布局中可能重新创建 layoutParams 覆盖此设置，
                // 导致 item 高度仍为 0，24 项全布局在一屏内（铁证：008 日志 L107 lastVisible=24）。
                // 修复：使用 ViewGroup.LayoutParams(MATCH_PARENT, defaultHeight) 创建新对象，
                // 确保高度设置不被 LayoutManager 覆盖。
                val defaultHeight = (parent.resources.displayMetrics.heightPixels * 0.6).toInt()
                binding.root.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    defaultHeight
                )
                ImageViewHolder(binding, onItemClick)
            }
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
     * Phase 3.4: 智能预加载（公共方法，供 Activity onScrolled 调用）
     *
     * 根据滚动位置预加载前后图片，提升滚动流畅度。
     * - 快速滚动时跳过（避免浪费带宽）
     * - 慢速/停止时预加载前后各 [range] 张
     *
     * @param centerPosition 中心位置（通常为当前可见的最后一项）
     * @param range 预加载范围（前后各 N 张，默认 1）
     */
    fun preloadAround(centerPosition: Int, range: Int = 1) {
        val snapshot = ImagePlay.allImageUrls.value
        if (snapshot.isEmpty()) return
        // FR-1: preloadAround 节流——距离上次预加载 < 300ms 跳过，避免"取消 5 个 + 新发 5 个"同时发生
        // 根因：快速滑动时 onScrollStateChanged 频繁触发 preloadAround，每次发起 ±range 个下载请求，
        // 与 cancelPendingDownload 节流配合，避免"取消旧下载+发起新下载"的震荡循环
        val now = System.currentTimeMillis()
        if (now - lastPreloadTimeMs < 300L) {
            return
        }
        lastPreloadTimeMs = now
        // I-003-P0-1: Activity 销毁后不再触发 Glide 加载（铁证：crash-2026-07-26-21-52-34.log）
        // 根因：Activity onDestroy → RecyclerView.dispatchDetachedFromWindow → stopScroll →
        // onScrollStateChanged → preloadAround → Glide.with(context) 抛 IllegalArgumentException
        val recyclerView = recyclerViewRef?.get() ?: return
        val context = recyclerView.context
        if (context is android.app.Activity) {
            if (context.isDestroyed || context.isFinishing) {
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_CANVAS,
                    "preloadAround skip: activity destroyed",
                    level = AppLog.Level.WARN
                )
                return
            }
        }
        val sourceOrigin = resolveSourceOrigin()  // I-P0-1: 显式优先
        // 预加载中心位置前后各 range 张（仅 ImageItem 类型，跳过 ArticleDivider）
        for (offset in -range..range) {
            if (offset == 0) continue  // 当前项由 bind 加载，无需预加载
            val idx = centerPosition + offset
            if (idx !in snapshot.indices) continue
            val item = snapshot[idx] as? ImageCanvasItem.ImageItem ?: continue
            val articleLink = resolveArticleLink(item.articleIndex)  // I-P0-1: 显式优先
            val opts = RequestOptions().apply {
                sourceOrigin?.let { set(OkHttpModelLoader.sourceOriginOption, it) }
                articleLink?.let { set(OkHttpModelLoader.refererOption, it) }
            }
            // 使用 preload 下载到磁盘缓存（不加载到内存，避免 OOM）
            kotlin.runCatching {
                val context = recyclerViewRef?.get()?.context ?: return@runCatching
                Glide.with(context)
                    .downloadOnly()
                    .load(item.url)
                    .apply(opts)
                    .submit()
            }.onFailure { t ->
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_CANVAS,
                    "preloadAround fail idx=$idx err=${t.message?.take(50)}",
                    level = AppLog.Level.WARN
                )
            }
        }
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_CANVAS,
            "preloadAround: center=$centerPosition range=$range",
            level = AppLog.Level.INFO
        )
    }

    /** RecyclerView 弱引用（用于 preloadAround 获取 Context，避免内存泄漏） */
    private var recyclerViewRef: java.lang.ref.WeakReference<RecyclerView>? = null

    /** FR-1: preloadAround 节流时间戳（避免快速滑动时频繁触发预加载，< 300ms 跳过） */
    private var lastPreloadTimeMs: Long = 0L

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerViewRef = java.lang.ref.WeakReference(recyclerView)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        recyclerViewRef?.clear()
        recyclerViewRef = null
    }

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
     * - AD-03：图片高度自适应（默认 60% 屏幕高，文件就绪后按原始宽高比调整）
     * - AD-08：缩略图模式（Glide.override 限制尺寸）
     * - AD-13：ViewHolder 复用闪烁修复（重置默认高度 + 清理 Glide + 复位双视图）
     * - AD-06：错误降级链触发（onLoadFailed 调用 triggerFallbackChain）
     * - Phase 3.2：图片金字塔（downloadOnly 落地磁盘缓存 → 探测尺寸 → 长图路由 SSIV）
     * - Phase 3.5：渐进式加载（thumbnail(0.1f) 先模糊后清晰）
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

        /** Phase 3.2: downloadOnly 下载句柄（ViewHolder 复用/回收时取消，释放带宽） */
        private var downloadTarget: FutureTarget<File>? = null

        /** FR-1: 取消节流时间戳（避免快速滑动时频繁取消下载，< 100ms 跳过取消让旧下载完成写入磁盘缓存） */
        private var lastCancelTimeMs: Long = 0L

        /**
         * 绑定图片数据
         */
        fun bind(item: ImageCanvasItem.ImageItem, position: Int) {
            currentUrl = item.url
            currentPosition = position
            currentItem = item

            // 修复（image-canvas-3fix-20260728 Q3）：isPreheatReload 计算前移，根据 isPreheatReload 决定是否重置 retryCount
            // 根因：原 L456 无条件 retryCount=0，导致 markPreheatReload 触发 notifyItemChanged → 重新 bind →
            // retryCount 重置 → 降级链从头开始（fallback-1→2→3→预热→重新bind→retryCount=0→fallback-1...）无限循环。
            // 修复：预热重载场景保留 retryCount，降级链续接（降级3后仍失败则进入降级4，而非重置到降级1）。
            // 铁证：008 日志 L2584-2696 同一 URL 反复 fallback-1→2→3→1→2→3→1 循环。
            val isPreheatReload = preheatReloadPositions.remove(position)
            if (isPreheatReload) {
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_CANVAS,
                    "bind: preheat reload, retryCount preserved=$retryCount position=$position",
                    level = AppLog.Level.INFO
                )
            } else {
                retryCount = 0  // 正常绑定/复用：重置降级链计数器
            }

            // AD-13: 重置为默认高度（避免旧图片实际高度残留导致闪烁）
            val defaultHeight = (itemView.resources.displayMetrics.heightPixels * 0.6).toInt()
            val lp = itemView.layoutParams
            lp.height = defaultHeight
            itemView.layoutParams = lp

            // AD-13 + Phase 3.2: 清空旧图片 + 取消未完成下载 + 复位双视图（PhotoView 默认，SSIV 长图专用）
            if (isGlideUsable()) Glide.with(itemView.context).clear(binding.photoView)
            cancelPendingDownload()
            binding.ssivView.visibility = View.GONE
            binding.ssivView.recycle()
            binding.photoView.visibility = View.VISIBLE

            // 共享元素动画 transitionName（与 ImageDetailActivity 接收端匹配）
            binding.photoView.transitionName = "shared_image_$position"

            val sourceOrigin = resolveSourceOrigin()  // I-P0-1: 显式优先
            val articleLink = resolveArticleLink(item.articleIndex)  // I-P0-1: 显式优先
            // I-P0-1: 头缺失 WARN（两者均缺时请求必无防盗链头，提前暴露）
            if (sourceOrigin == null && articleLink == null) {
                logHeadersMissing(position, item.articleIndex)
            }

            // W7: 从 ImagePlay.rssSource?.header 提取 header Map
            // 注：OkHttpStreamFetcher 已通过 sourceOriginOption → SourceHelp.getSource → AnalyzeUrl
            // 自动注入 source.header 全部字段（含 UA / Cookie 等）。
            // 这里提取主要用于 Referer 优先级：source.header 中的 Referer 优先于文章页 URL 兜底。
            sourceHeaderMap = ImagePlay.rssSource?.getHeaderMap()
            val effectiveReferer = extractReferer(sourceHeaderMap) ?: articleLink

            // Phase 3.2: downloadOnly 落地磁盘缓存 → 探测尺寸 → 路由 SSIV/PhotoView
            // 使用 OkHttpModelLoader.sourceOriginOption/refererOption 注入防盗链头
            // I-P0-2: 预热后重载登记命中 → bypass failUrl + skipMemory 强制重新拉取（cookies 已同步）
            // isPreheatReload 已在前方计算（Q3 修复前移）
            val requestOptions = buildRequestOptions(
                sourceOrigin, effectiveReferer,
                skipMemory = isPreheatReload,
                bypassFailCache = isPreheatReload
            )
            loadImage(item.url, requestOptions, position)

            // 点击进入大图模式（共享元素动画由 Activity 处理）
            itemView.setOnClickListener {
                onItemClick(position, binding.photoView)
            }
        }

        /**
         * Glide 上下文可用性守卫（铁证：crash-2026-07-26，Activity 销毁过程中
         * Glide.with(activity) 抛 IllegalArgumentException: destroyed activity）
         *
         * downloadOnly 异步回调 / postDelayed 重试 / ViewHolder 回收都可能在
         * Activity 销毁后触发，此处统一兜底：销毁后的一切 Glide 调用直接跳过。
         */
        private fun isGlideUsable(): Boolean {
            val ctx = itemView.context
            return ctx !is android.app.Activity || (!ctx.isDestroyed && !ctx.isFinishing)
        }

        /**
         * 取消未完成的 downloadOnly 下载（ViewHolder 复用/回收时释放带宽）
         *
         * FR-1: 取消节流——距离上次取消 < 100ms 跳过取消，让正在进行的下载完成写入磁盘缓存。
         * 根因：快速滑动时 bind 频繁触发 cancelPendingDownload（bind L495 + loadImage L600 两处），
         * 新下载刚发起就被下一次 bind 取消，导致下载永远无法完成，图片显示空白。
         * 节流让旧下载有足够时间完成（100ms 内的连续取消视为同一波次）。
         *
         * 安全性：
         * - 节流跳过取消时，downloadTarget 保持旧值，旧下载继续在 Glide 队列中执行
         * - 新 loadImage 调用会覆盖 downloadTarget 为新值，旧下载引用丢失但仍继续执行
         * - 旧下载完成回调 onResourceReady 时，currentUrl != url 守卫会拦截（return false）
         * - 旧下载的字节会写入磁盘缓存，下次滚动回来命中缓存直接显示
         */
        private fun cancelPendingDownload() {
            // FR-1: 取消节流检查
            val now = System.currentTimeMillis()
            if (now - lastCancelTimeMs < 100L) {
                // 节流跳过：保留旧 downloadTarget，让旧下载继续完成写入磁盘缓存
                return
            }
            lastCancelTimeMs = now
            downloadTarget?.let { target ->
                kotlin.runCatching { Glide.with(itemView.context).clear(target) }
            }
            downloadTarget = null
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
         * - bypassFailCache：I-P0-2 降级链主动重试/预热后重载时 true，绕过 fetcher failUrl 短路
         */
        private fun buildRequestOptions(
            sourceOrigin: String?,
            referer: String?,
            skipMemory: Boolean,
            bypassFailCache: Boolean = false
        ): RequestOptions {
            return RequestOptions().apply {
                sourceOrigin?.let { set(OkHttpModelLoader.sourceOriginOption, it) }
                referer?.let { set(OkHttpModelLoader.refererOption, it) }
                if (skipMemory) {
                    skipMemoryCache(true)
                }
                if (bypassFailCache) {
                    set(OkHttpModelLoader.bypassFailCacheOption, true)
                }
            }
        }

        /**
         * Phase 3.2 图片金字塔加载入口（封装以便降级链复用）
         *
         * 流程：Glide downloadOnly 落地磁盘缓存（原始字节，不经内存解码，长图无 OOM 风险）
         * → decodeBounds 探测原始尺寸（仅读文件头）→ 按 isLongImage 路由 SSIV/PhotoView
         *
         * @param url 图片 URL
         * @param requestOptions 防盗链头选项（sourceOriginOption/refererOption）
         * @param position 当前列表 position
         */
        private fun loadImage(url: String, requestOptions: RequestOptions, position: Int) {
            cancelPendingDownload()
            // Activity 销毁后禁止发起 Glide 加载（异步回调路径兜底，防 destroyed activity 崩溃）
            if (!isGlideUsable()) return
            downloadTarget = Glide.with(itemView.context)
                .downloadOnly()
                .load(url)
                .apply(requestOptions)
                .priority(Priority.NORMAL)
                .listener(object : RequestListener<File> {
                    override fun onResourceReady(
                        resource: File,
                        model: Any,
                        target: Target<File>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        // ViewHolder 已复用绑定其他图片，放弃路由
                        if (currentUrl != url) return false
                        // 修复（image-canvas-thread-fix-20260728）：downloadOnly 回调在
                        // glide-disk-cache-thread 线程触发，onImageFileReady 会调用 SSIV.recycle()
                        // 创建 GestureDetector 抛 Handler 异常（Can't create handler inside thread），
                        // 被 Glide 包装为 CallbackException 吞掉不触发 onLoadFailed。必须切主线程。
                        itemView.post {
                            // ViewHolder 复用守卫：post 异步期间可能已绑定其他 URL
                            if (currentUrl != url) return@post
                            onImageFileReady(resource, url, position)
                        }
                        return true
                    }

                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<File>?,
                        isFirstResource: Boolean
                    ): Boolean {
                        if (currentUrl != url) return true
                        // 修复（image-canvas-thread-fix-20260728）：同 onResourceReady，回调在
                        // glide-disk-cache-thread，triggerFallbackChain 含 UI 操作（postDelayed/
                        // showFallbackHint），必须切主线程
                        itemView.post {
                            if (currentUrl != url) return@post
                            // AD-13: 加载失败时设置错误高度（屏幕高度 40%）
                            val errLp = itemView.layoutParams
                            errLp.height = (itemView.resources.displayMetrics.heightPixels * 0.4).toInt()
                            itemView.layoutParams = errLp
                            // AD-06 + E1: 触发四级降级链
                            triggerFallbackChain(e, position)
                        }
                        return true
                    }
                })
                .submit()
        }

        /**
         * 图片文件下载完成：探测尺寸并路由 SSIV/PhotoView（Phase 3.2 AD-06）
         */
        private fun onImageFileReady(file: File, url: String, position: Int) {
            val dm = itemView.resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            // decodeBounds 探测原始尺寸（仅读文件头，不全量解码）
            val bounds = ImagePyramidLoader.decodeBounds(file)
            if (bounds == null) {
                // 文件损坏/非图片：等同加载失败，走降级链
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_CANVAS,
                    "Pyramid: decodeBounds null position=$position url=/path/${url.hashCode()}",
                    level = AppLog.Level.WARN
                )
                val errLp = itemView.layoutParams
                errLp.height = (screenH * 0.4).toInt()
                itemView.layoutParams = errLp
                triggerFallbackChain(null, position)
                return
            }
            val imgW = bounds[0]
            val imgH = bounds[1]
            if (ImagePyramidLoader.isLongImage(imgW, imgH, screenH)) {
                // Phase 3.2: 长图 → SSIV 金字塔（BitmapRegionDecoder 区域解码）
                showSsivImage(file, imgW, imgH, screenW, screenH, position)
            } else {
                // 普通图 → PhotoView（Phase 3.5 渐进式 thumbnail(0.1f)）
                loadIntoPhotoView(file, url, imgW, imgH, screenW, screenH, position)
            }
        }

        /**
         * 长图 SSIV 展示（Phase 3.2 + 3.3）
         *
         * - 高度按宽高比全量展开（上限 20 倍屏高兜底，防极端尺寸撑爆布局）
         * - SSIV 区域解码，内存占用与图片尺寸无关
         * - 单击进入横向浏览（SSIV 消费触摸事件，需单独挂监听）
         */
        private fun showSsivImage(
            file: File,
            imgW: Int,
            imgH: Int,
            screenW: Int,
            screenH: Int,
            position: Int
        ) {
            // downloadOnly 异步回调路径：Activity 销毁后跳过（含 Glide.clear）
            if (!isGlideUsable()) return
            // Phase 3.3: 高度自适应（按宽高比展开，不裁剪不变形）
            val viewH = ImagePyramidLoader.ssivDisplayHeight(imgW, imgH, screenW, screenH)
            val lp = itemView.layoutParams
            if (lp.height != viewH) {
                lp.height = viewH
                itemView.layoutParams = lp
            }
            Glide.with(itemView.context).clear(binding.photoView)
            binding.photoView.visibility = View.GONE
            binding.ssivView.visibility = View.VISIBLE
            ImagePyramidLoader.bindLongImage(binding.ssivView, file, imgW, imgH, screenW, viewH)
            hideFallbackHint()
            binding.ssivView.setOnClickListener {
                onItemClick(currentPosition, binding.photoView)
            }
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "Pyramid: long image routed to SSIV position=$position imgW=$imgW imgH=$imgH viewH=$viewH",
                level = AppLog.Level.INFO
            )
        }

        /**
         * 普通图 PhotoView 展示（Phase 3.3 + 3.5）
         *
         * - 3.3 高度自适应：按原始宽高比折算（比解码后 bitmap 更早更准，无布局二次跳动）
         * - 3.5 渐进式：thumbnail(0.1f) 先加载 10% 分辨率模糊图，再加载清晰原图
         * - override(screenW, targetH) + 默认降采样：超宽图也不会全量解码，内存安全
         *
         * 注：retryCount 不在此处重置——单次绑定只有一次逻辑加载，
         * 缩略图就绪时重置会导致"清晰图失败"时降级链从头再来（潜在循环）。
         */
        private fun loadIntoPhotoView(
            file: File,
            url: String,
            imgW: Int,
            imgH: Int,
            screenW: Int,
            screenH: Int,
            position: Int
        ) {
            // downloadOnly 异步回调路径：Activity 销毁后跳过（含 Glide.load）
            if (!isGlideUsable()) return
            // Phase 3.3: 高度自适应（按宽高比折算，上限 4 倍屏高，fitCenter 不变形不裁剪）
            val targetH = ImagePyramidLoader.normalDisplayHeight(imgW, imgH, screenW, screenH)
            val lp = itemView.layoutParams
            if (lp.height != targetH) {
                lp.height = targetH
                itemView.layoutParams = lp
            }
            binding.ssivView.visibility = View.GONE
            binding.ssivView.recycle()
            binding.photoView.visibility = View.VISIBLE
            Glide.with(itemView.context)
                .load(file)
                .override(screenW, targetH)
                .dontTransform()
                .thumbnail(0.1f)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .listener(object : RequestListener<Drawable> {
                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        if (currentUrl != url) return false
                        hideFallbackHint()
                        return false
                    }

                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>?,
                        isFirstResource: Boolean
                    ): Boolean {
                        if (currentUrl != url) return true
                        // 本地解码失败（文件损坏等）：走降级链（降级2会换签名重新下载）
                        triggerFallbackChain(e, position)
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
            // I-P0-1: 403 计数（同 URL 去重）
            log403Once(e, position, url)
            val sourceOrigin = resolveSourceOrigin()  // I-P0-1: 显式优先
            val articleLink = resolveArticleLink(item.articleIndex)  // I-P0-1: 显式优先
            if (sourceOrigin == null && articleLink == null) {
                logHeadersMissing(position, item.articleIndex)
            }
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
                    binding.photoView.postDelayed({
                        // ViewHolder 已复用绑定其他图片，放弃重试
                        if (currentUrl != url) return@postDelayed
                        // Activity 销毁后放弃重试（Glide.with 崩溃兜底）
                        if (!isGlideUsable()) return@postDelayed
                        Glide.with(itemView.context).clear(binding.photoView)
                        // I-P0-2: bypassFailCache=true 绕过 failUrl 短路（否则重试不发请求）
                        val opts = buildRequestOptions(sourceOrigin, effectiveReferer, skipMemory = true, bypassFailCache = true)
                        loadImage(url, opts, position)
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
                    binding.photoView.postDelayed({
                        if (currentUrl != url) return@postDelayed
                        // Activity 销毁后放弃重试（Glide.with 崩溃兜底）
                        if (!isGlideUsable()) return@postDelayed
                        Glide.with(itemView.context).clear(binding.photoView)
                        // I-P0-2: bypassFailCache=true 绕过 failUrl 短路（否则重试不发请求）
                        val opts = buildRequestOptions(sourceOrigin, effectiveReferer, skipMemory = true, bypassFailCache = true)
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                        loadImage(url, opts, position)
                    }, 500)
                }
                2 -> {
                    // 降级3: WebView 即时预热（V4 6.1.3）
                    // 通过回调让 Activity 启动隐藏 WebView 加载图片 URL
                    // onPageFinished 后 CookieManager.flush() 同步 cookies，然后重试 Glide
                    // 修复（image-canvas-3fix-20260728 Q3循环修复）：同一 URL 只能预热一次
                    // 根因：onRecycled 重置 retryCount=0 导致预热重载后从 fallback-1 重新开始，无限循环。
                    // 修复：preheatedUrlHashes 记录已预热 URL，已预热则跳过 fallback-3 直接进入 fallback-4。
                    val urlHash = url.hashCode()
                    if (preheatedUrlHashes.contains(urlHash)) {
                        // 已预热过，直接进入降级4（网页模式回退）
                        retryCount = 4
                        showFallbackHint(4)
                        AppLog.putDebugWithTag(
                            AppLog.TAG_IMAGE_CANVAS,
                            "E1 fallback-4 webModeFallback (preheated skip) position=$position articleIndex=${item.articleIndex} url=/path/$urlHash reason=${e?.message?.take(80)}",
                            level = AppLog.Level.ERROR
                        )
                        onWebModeFallback(item.articleIndex)
                    } else {
                        preheatedUrlHashes.add(urlHash)
                        retryCount = 3
                        showFallbackHint(3)
                        AppLog.putDebugWithTag(
                            AppLog.TAG_IMAGE_CANVAS,
                            "E1 fallback-3 webviewPreheat position=$position url=/path/$urlHash reason=${e?.message?.take(80)}",
                            level = AppLog.Level.WARN
                        )
                        onWebViewFallback(url, position)
                    }
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
         * ViewHolder 被回收时清理 Glide 资源（AD-13）
         */
        fun onRecycled() {
            // Activity 销毁过程中回收回调跳过 Glide 调用（destroyed activity 崩溃铁证）
            if (isGlideUsable()) Glide.with(itemView.context).clear(binding.photoView)
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
