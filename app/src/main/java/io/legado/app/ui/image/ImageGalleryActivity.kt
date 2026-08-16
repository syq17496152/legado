package io.legado.app.ui.image

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ActivityImageGalleryBinding
import io.legado.app.R
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.image.adapter.ImageCanvasAdapter
import io.legado.app.ui.image.adapter.ImageDetailAdapter
import io.legado.app.ui.image.adapter.ImageDetailViewPagerAdapter
import io.legado.app.ui.image.ImageCanvasItem
import io.legado.app.ui.image.ImagePlay
import io.legado.app.ui.rss.favorites.RssFavoritesDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction

/**
 * 图片浏览 Activity（V4 重写：垂直画布架构）
 *
 * 设计参考：design.md §1.1 架构图 + §3.1 数据流 1
 *
 * 架构演进（V4）：
 * - 旧架构：ImageArticlePagerAdapter（外层 ViewPager2 vertical）+ ImagePageAdapter（内层 ViewPager2 horizontal）
 * - 新架构：单 RecyclerView（垂直长画布）+ ImageCanvasAdapter + ImageCanvasViewModel
 *
 * 核心能力：
 * 1. 单 RecyclerView 垂直长画布（所有文章图片扁平化展示，design.md AD-01）
 * 2. 多线程并行加载图片（Glide 异步，AD-08 缩略图模式 override）
 * 3. 图片高度自适应（AD-03：默认 60% 屏幕高，加载后按宽高比调整）
 * 4. 分页加载（AD-04：滚动到底部自动加载下一篇）
 * 5. 文章分隔符（R2.7：跨文章图片混排时显示分隔条）
 * 6. WebView 串行预热（AD-05：复用 pendingPreheatDomains，多域名 CDN 逐个预热）
 * 7. 点击缩略图进入横向浏览（Phase 3.1 AD-05：Activity 内嵌全屏 ViewPager2 层，无 Activity 切换断感）
 * 8. 退出横向模式同步索引回垂直列表滚动位置（exitHorizontalMode）
 * 9. 沉浸式全屏（横向模式下点击图片切换显隐）
 * 10. 位置记忆（onDestroy 记录 lastPlayedArticleLink）
 *
 * 数据流（design.md §3.1）：
 * - 入口：ReadRss.readNoHtml 启动，设置 ImagePlay 单例字段
 * - 加载：ImageCanvasViewModel.loadInitialArticle / loadNextArticle 协程加载
 * - 状态：loadState LiveData 通知 footer 切换（LOADING/SUCCESS/ERROR/NO_MORE）
 * - 大图：点击缩略图 → enterHorizontalMode 显示 ViewPager2 并定位 → 退出时 scrollToPosition
 */
class ImageGalleryActivity : VMBaseActivity<ActivityImageGalleryBinding, ImageCanvasViewModel>() {

    override val binding by viewBinding(ActivityImageGalleryBinding::inflate)
    override val viewModel by viewModels<ImageCanvasViewModel>()

    private var canvasAdapter: ImageCanvasAdapter? = null

    /** 全屏横向浏览适配器（AD-05：每次进入横向模式时重建，保证数据源最新） */
    private var detailViewPagerAdapter: ImageDetailViewPagerAdapter? = null
    /** 横向浏览模式状态（true=ViewPager2 全屏显示中） */
    private var isHorizontalMode = false

    /** 当前长按的图片URL（用于选择保存目录后回调） */
    private var currentImageUrl: String? = null
    /** 沉浸式状态（true=隐藏状态栏/导航栏/工具栏） */
    private var isImmersive = false

    /** Compose 顶栏「更多」菜单展开态（L-C15 S5 改造） */
    private var menuExpanded by mutableStateOf(false)

    // ==================== Phase 3.4: 智能预加载（滚动速度判断） ====================
    /** 上次滚动时间戳（用于计算滚动速度） */
    private var lastScrollTime = 0L
    /** 上次滚动 Y 偏移（用于计算滚动速度） */
    private var lastScrollDy = 0
    /** 滚动速度阈值（px/ms，超过此值视为快速滚动，跳过预加载） */
    private val scrollSpeedThreshold = 2.0f
    /** 预加载去抖延迟（ms，停止滚动后延迟触发预加载） */
    private val preloadDebounceMs = 150L
    /** 预加载待执行任务（用于去抖） */
    private var preloadRunnable: Runnable? = null

    // ==================== 方案A：WebView 预热（Cloudflare 防护） ====================
    /** 待预热的图片 CDN 域名队列（V4 AD-05：复用源码现有字段，串行预热） */
    private val pendingPreheatDomains = mutableSetOf<String>()
    /** 已预热完成的域名集合（避免重复预热） */
    private val preheatedDomains = mutableSetOf<String>()
    /** 是否已完成首次预热 */
    private var isFirstPreheatCompleted = false

    /**
     * 修复（image-canvas-scroll-fix-20260728）：首次插入图片项后是否已滚动到顶部
     *
     * 根因：item_image_canvas.xml root 是 wrap_content，图片未加载时高度为 0，
     * notifyItemRangeInserted 插入 26 项后所有项都布局在一屏内，RecyclerView 滚动到最后。
     * 即使 onCreateViewHolder 已设置默认高度，bind 方法重置高度仍可能触发 requestLayout
     * 导致重新布局。此处兜底：首次插入后强制滚动到第一张图片。
     */
    private var isInitialScrollDone = false

    /**
     * I-P0-2: 降级预热待重载映射（域名 → 待重载 position 集合）
     *
     * 流程：onWebViewFallback 登记 position → 预热完成(onPageFinished)/5s超时 →
     * triggerFallbackReload 取出集合并 remove → canvasAdapter.markPreheatReload 触发
     * re-bind（bypass failUrl + skipMemory 强制重新拉取，此时 CookieManager 已 flush，
     * enabledCookieJar 可携 WebView cookies）。
     *
     * 一个域名可能有多张 403 图，故 value 为 Set；remove 语义保证只触发一次（幂等）。
     */
    private val pendingFallbackReload = mutableMapOf<String, MutableSet<Int>>()

    /** 选择图片保存目录 */
    private val selectImageDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(AppConst.imagePathKey, uri.toString())
            currentImageUrl?.let { url ->
                viewModel.saveImage(url, ImagePlay.rssSource?.sourceUrl, uri)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (isHorizontalMode) {
            exitHorizontalMode()
            return true
        }
        finish()
        return true
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_CANVAS,
            "ImageGalleryActivity: onCreate hashCode=${this.hashCode()} sourceId=${ImagePlay.rssSource?.sourceUrl?.hashCode()} articleCount=${ImagePlay.rssArticles?.size ?: 0}",
            level = AppLog.Level.INFO
        )
        initImmersion()
        initComposeTopBar()
        initRecyclerView()
        initFullscreenViewPager()
        initRotateToolbar()
        initBackPressedHandler()
        initPreheatWebView()
        observeLoadState()
        observeNewItems()  // 修复 regression：精准 notifyItemRangeInserted 替代 notifyDataSetChanged
        // 启动首篇文章加载
        viewModel.loadInitialArticle()
    }

    /**
     * 初始化沉浸式全屏
     */
    private fun initImmersion() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
    }

    /**
     * Compose 顶栏（L-C15 S5 改造）：GlassTopAppBar + 收藏/刷新图标按钮 + MoreVert 下拉菜单
     */
    private fun initComposeTopBar() {
        val title = intent.getStringExtra("title") ?: getString(R.string.image_browse)
        binding.composeTopBar.setContent {
            LegadoTheme {
                GlassTopAppBar(
                    title = title,
                    navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    onNavClick = {
                        if (isHorizontalMode) {
                            exitHorizontalMode()
                        } else {
                            finish()
                        }
                    },
                    actions = {
                        IconButton(onClick = { starCurrentArticle() }) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = getString(R.string.favorite)
                            )
                        }
                        IconButton(onClick = { refreshImages() }) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = getString(R.string.refresh)
                            )
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = getString(R.string.more)
                                )
                            }
                            AppDropdownMenu(
                                expanded = menuExpanded,
                                onDismiss = { menuExpanded = false },
                                actions = buildMenuActions()
                            )
                        }
                    }
                )
            }
        }
    }

    // ==================== B3-5.1: 工具栏菜单（收藏/刷新/浏览器打开/日志） ====================

    private fun buildMenuActions(): List<MenuAction> {
        val actions = mutableListOf<MenuAction>()
        // 浏览器打开原始详情页
        actions += MenuAction(
            icon = Icons.Filled.OpenInBrowser,
            title = getString(R.string.open_in_browser),
            onClick = {
                val article = ImagePlay.rssArticles?.getOrNull(ImagePlay.rssArticleIndex)
                val link = article?.link
                if (!link.isNullOrBlank()) {
                    openUrl(link)
                } else {
                    toastOnUi("无文章链接")
                }
            }
        )
        // 查看日志
        actions += MenuAction(
            icon = Icons.Filled.Info,
            title = getString(R.string.log),
            onClick = {
                io.legado.app.ui.about.AppLogDialog().show(supportFragmentManager, "appLogDialog")
            }
        )
        return actions
    }

    private fun starCurrentArticle() {
        val article = ImagePlay.rssArticles?.getOrNull(ImagePlay.rssArticleIndex)
        if (article != null) {
            showDialogFragment(RssFavoritesDialog(article))
        } else {
            toastOnUi("无当前文章")
        }
    }

    private fun refreshImages() {
        // 刷新：清 Glide 内存缓存 + 重新加载
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_CANVAS,
            "menu_refresh: clear cache and reload",
            level = AppLog.Level.INFO
        )
        com.bumptech.glide.Glide.get(this).clearMemory()
        canvasAdapter?.notifyDataSetChanged()
        viewModel.loadInitialArticle()
        toastOnUi("已刷新")
    }

    /**
     * 初始化 RecyclerView + ImageCanvasAdapter（V4 重构核心）
     *
     * - LinearLayoutManager 垂直布局
     * - setItemViewCacheSize(2)：离屏缓存 2 个 ViewHolder
     * - setHasFixedSize(false)：允许动态高度（AD-13）
     * - OnScrollListener：触发分页加载（AD-04）
     * - OnScrollListener：快速滚动暂停 Glide（AD-08）
     */
    private fun initRecyclerView() {
        // V3 B-7：onItemClick 回调中传递 listPosition + sharedView，由 Activity 启动 ImageDetailActivity
        // V4 6.1.3-6.1.5：添加 onWebViewFallback + onWebModeFallback 降级3/4 回调
        canvasAdapter = ImageCanvasAdapter(
            onItemClick = { listPosition, sharedView ->
                onCanvasItemClick(listPosition, sharedView)
            },
            onRetryClick = {
                // V-004-P0-ImageRetry: 修复重试按钮无效（铁证：004 日志 19:01:14 进入 16 秒退出，重试无效）
                // 根因：onRetryClick 原调用 loadNextArticle，但首次加载失败时 loadedArticleIndices 空，
                // loadNextArticle 检查 maxOrNull()=null 直接 return，重试按钮无效
                // 方案：检查 loadedArticleIndices 是否空，空则调用 loadInitialArticle（重试首次加载），非空调用 loadNextArticle
                AppLog.putDebugWithTag(AppLog.TAG_IMAGE_CANVAS, "onRetryClick: retry, loadedArticleIndicesEmpty=${ImagePlay.loadedArticleIndices.isEmpty()}", level = AppLog.Level.INFO)
                if (ImagePlay.loadedArticleIndices.isEmpty()) {
                    viewModel.loadInitialArticle()
                } else {
                    viewModel.loadNextArticle()
                }
            },
            onWebViewFallback = { url, position ->
                // 降级3: WebView 即时预热（V4 6.1.3）
                // I-P0-2 修复：本回调源自 Glide RequestListener（工作线程），
                // WebView.loadUrl 必须 UI 线程——原实现直接调用，异常被 runCatching 吞掉，
                // 预热从未执行（86 张 403 降级链断裂的直接根因）。
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_CANVAS,
                    "ImageFallback: webview preheat request position=$position url=/path/${url.hashCode()}",
                    level = AppLog.Level.INFO
                )
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    kotlin.runCatching {
                        // 登记 position → 域名映射，预热完成/超时后统一触发重载
                        val domain = kotlin.runCatching { NetworkUtils.getSubDomain(url) }.getOrNull()
                        if (domain != null) {
                            pendingFallbackReload[domain]?.add(position)
                                ?: pendingFallbackReload.put(domain, mutableSetOf(position))
                        }
                        binding.webviewPreheat.loadUrl(url)
                        AppLog.putDebugWithTag(
                            AppLog.TAG_IMAGE_CANVAS,
                            "ImageFallback: webview preheat start, domain=***, position=$position",
                            level = AppLog.Level.INFO
                        )
                        // 5s 超时兜底：onPageFinished 未触发也执行重载（弱网/WebView 卡住场景）
                        binding.webviewPreheat.postDelayed({
                            if (!isDestroyed && !isFinishing) {
                                triggerFallbackReload(domain)
                            }
                        }, 5000)
                    }.onFailure { e ->
                        AppLog.putDebugWithTag(
                            AppLog.TAG_IMAGE_CANVAS,
                            "onWebViewFallback failed: ${e.message}",
                            level = AppLog.Level.ERROR
                        )
                    }
                }
            },
            onWebModeFallback = { articleIndex ->
                // 降级4: 网页模式回退（V4 6.1.4 + 7.2.2 改为 alert DSL）
                // 弹出 alert DSL 询问用户是否切换到网页模式
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_CANVAS,
                    "onWebModeFallback: prompt user articleIndex=$articleIndex",
                    level = AppLog.Level.INFO
                )
                alert("图片加载失败", "已尝试所有自动恢复方案仍无法加载图片，是否切换到网页模式查看？") {
                    positiveButton("切换网页模式") {
                        AppLog.putDebugWithTag(
                            AppLog.TAG_IMAGE_CANVAS,
                            "onWebModeFallback: user confirm switch to ReadRssActivity articleIndex=$articleIndex",
                            level = AppLog.Level.INFO
                        )
                        // 跳转 ReadRssActivity（网页模式）
                        val article = ImagePlay.rssArticles?.getOrNull(articleIndex)
                        if (article != null) {
                            val intent = Intent(this@ImageGalleryActivity, io.legado.app.ui.rss.read.ReadRssActivity::class.java).apply {
                                putExtra("articleIndex", articleIndex)
                            }
                            startActivity(intent)
                            finish()
                        }
                    }
                    negativeButton("取消")
                }
            }
        )
        binding.recyclerView.apply {
            // BUG1 fix V2: 使用 OnGlobalLayoutListener 确保在布局完成后获取准确高度
            // V1 的 titleBar.post 在某些时机 titleBar.height=0（尚未完成 layout），导致 paddingTop 不够
            // V2 改用 OnGlobalLayoutListener 回调，此时所有 View 已完成 measure/layout
            binding.composeTopBar.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val topBarHeight = binding.composeTopBar.height
                    if (topBarHeight <= 0) return // 高度仍为0则等待下次回调
                    // L-C15 S5 改造：Compose GlassTopAppBar 已自带状态栏 padding，无需再加 statusBarHeight
                    val totalTopPadding = topBarHeight
                    if (totalTopPadding > 0 && paddingTop != totalTopPadding) {
                        setPadding(paddingLeft, totalTopPadding, paddingRight, paddingBottom)
                        AppLog.putDebugWithTag(
                            AppLog.TAG_IMAGE_CANVAS,
                            "BUG1 fix V2: set paddingTop=$totalTopPadding (topBar=$topBarHeight)",
                            level = AppLog.Level.INFO
                        )
                    }
                    // 只需执行一次，移除监听
                    binding.composeTopBar.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            })
            layoutManager = LinearLayoutManager(this@ImageGalleryActivity)
            adapter = canvasAdapter
            // AD-08: 离屏缓存 2 个 ViewHolder（默认 2，显式设置明确意图）
            setItemViewCacheSize(2)
            // AD-13: 允许动态高度（图片高度自适应）
            setHasFixedSize(false)

            // AD-04: 滚动监听触发分页加载 + 快速滚动暂停 Glide + Phase 3.4 智能预加载
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dy, dy)
                    // 修复（image-canvas-3fix-20260728 Q1修复3）：首次插入未完成时禁用 loadNextArticle
                    // 根因：首次插入 24 项后 80ms 触发 loadNextArticle（铁证：008 日志 L107），
                    // loadNextArticle 加载下一篇并插入，破坏初始滚动定位。
                    // 修复：isInitialScrollDone=true 后才允许触发 loadNextArticle，
                    // 确保初始滚动定位稳定。
                    if (!isInitialScrollDone) {
                        return
                    }
                    val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val totalItemCount = layoutManager.itemCount
                    val lastVisible = layoutManager.findLastVisibleItemPosition()
                    // 更新右下角画布页码悬浮（当前文章图片索引/总数）
                    updateCanvasPageIndex(layoutManager)
                    val remaining = totalItemCount - lastVisible
                    // AD-04: 剩余项数 ≤ 阈值时触发下一篇加载
                    if (remaining <= ImageCanvasAdapter.PAGINATION_THRESHOLD) {
                        AppLog.putDebugWithTag(
                            AppLog.TAG_IMAGE_CANVAS,
                            "Scroll: trigger loadNextArticle remaining=$remaining total=$totalItemCount lastVisible=$lastVisible",
                            level = AppLog.Level.INFO
                        )
                        viewModel.loadNextArticle()
                    }

                    // ==================== Phase 3.4: 智能预加载（滚动速度判断） ====================
                    val currentTime = System.currentTimeMillis()
                    val timeDelta = currentTime - lastScrollTime
                    // 计算滚动速度（px/ms）
                    val scrollSpeed = if (timeDelta > 0) {
                        kotlin.math.abs(dy).toFloat() / timeDelta.toFloat()
                    } else {
                        0f
                    }
                    lastScrollTime = currentTime
                    lastScrollDy = dy

                    // 快速滚动时跳过预加载（避免浪费带宽）
                    if (scrollSpeed > scrollSpeedThreshold) {
                        // 取消待执行的预加载任务
                        preloadRunnable?.let { recyclerView.removeCallbacks(it) }
                        preloadRunnable = null
                        AppLog.putDebugWithTag(
                            AppLog.TAG_IMAGE_CANVAS,
                            "Scroll: fast scroll skip preload speed=${"%.2f".format(scrollSpeed)}px/ms dy=$dy",
                            level = AppLog.Level.INFO
                        )
                        return
                    }

                    // 慢速/停止时：去抖延迟触发预加载
                    preloadRunnable?.let { recyclerView.removeCallbacks(it) }
                    preloadRunnable = Runnable {
                        // 预加载当前可见位置前后各 1 张图片
                        canvasAdapter?.preloadAround(lastVisible, range = 1)
                    }
                    recyclerView.postDelayed(preloadRunnable, preloadDebounceMs)
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    // AD-08: 快速滚动时暂停 Glide 加载，停止后恢复
                    // Bug-fix 2026-07-26: Activity 销毁过程中（onDetachedFromWindow→stopScroll→onScrollStateChanged）
                    // 调用 Glide.with(activity) 会抛 IllegalArgumentException: destroyed activity
                    // 必须检查 isDestroyed 跳过（铁证：crash-2026-07-26-21-52-34.log）
                    if (isDestroyed || isFinishing) return
                    when (newState) {
                        RecyclerView.SCROLL_STATE_DRAGGING -> {
                            com.bumptech.glide.Glide.with(this@ImageGalleryActivity).pauseRequests()
                        }
                        RecyclerView.SCROLL_STATE_IDLE -> {
                            com.bumptech.glide.Glide.with(this@ImageGalleryActivity).resumeRequests()
                            // Phase 3.4: 滚动停止时立即触发预加载（无需去抖）
                            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                            val lastVisible = layoutManager?.findLastVisibleItemPosition() ?: return
                            preloadRunnable?.let { recyclerView.removeCallbacks(it) }
                            canvasAdapter?.preloadAround(lastVisible, range = 1)
                        }
                    }
                }
            })
        }
        // I-P0-1: 显式传入防盗链头（与 ImagePlay 全局态解耦，ImagePlay 仅作兜底）
        // 文章 link 映射快照：articleIndex → link（link 为 null 的过滤）
        canvasAdapter?.setAntiLeechHeaders(
            sourceOrigin = ImagePlay.rssSource?.sourceUrl,
            articleLinks = ImagePlay.rssArticles
                ?.mapIndexedNotNull { idx, article -> article.link?.let { idx to it } }
                ?.toMap()
        )
        AppLog.putDebugWithTag(AppLog.TAG_IMAGE_CANVAS, "initRecyclerView done", level = AppLog.Level.INFO)
    }

    /**
     * 列表项点击回调：进入横向浏览模式（Phase 3.1 AD-05）
     *
     * 流程（design.md §3.2 数据流 2 修订）：
     * 1. 列表 position 转换为图片索引（listPositionToImageIndex，V3 B-7）
     * 2. 显示全屏 ViewPager2 并定位到点击索引（替代启动 ImageDetailActivity）
     *
     * @param sharedView 保留参数（升级路径：后续手动实现共享元素过渡时使用）
     */
    private fun onCanvasItemClick(listPosition: Int, sharedView: View) {
        val imageIdx = canvasAdapter?.listPositionToImageIndex(listPosition) ?: -1
        if (imageIdx < 0) {
            AppLog.putDebugWithTag(AppLog.TAG_IMAGE_CANVAS, "onCanvasItemClick: invalid imageIdx for listPos=$listPosition", level = AppLog.Level.WARN)
            return
        }
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_CANVAS,
            "onCanvasItemClick: enter horizontal mode listPos=$listPosition imageIdx=$imageIdx",
            level = AppLog.Level.INFO
        )
        enterHorizontalMode(imageIdx)
    }

    // ==================== 横向浏览模式（Phase 3.1 AD-05：Activity 内嵌全屏 ViewPager2 层） ====================

    /**
     * 初始化全屏 ViewPager2（一次性配置：方向 + 页码监听）
     *
     * - adapter 在每次进入横向模式时重建（setupFullscreenViewPager），保证数据源最新
     * - OnPageChangeCallback 注册一次（重复注册会累积）
     */
    private fun initFullscreenViewPager() {
        binding.viewPagerFullscreen.orientation = ViewPager2.ORIENTATION_HORIZONTAL
        binding.viewPagerFullscreen.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (isHorizontalMode) {
                    updatePageIndex(position)
                }
            }
        })
    }

    /**
     * 初始化旋转工具栏按钮（顺时针/逆时针/重置，复用布局已有 layout_rotate_toolbar）
     */
    private fun initRotateToolbar() {
        binding.btnRotateRight.setOnClickListener {
            detailViewPagerAdapter?.rotateCurrentClockwise()
        }
        binding.btnRotateLeft.setOnClickListener {
            detailViewPagerAdapter?.rotateCurrentCounterClockwise()
        }
        binding.btnReset.setOnClickListener {
            detailViewPagerAdapter?.resetCurrentView()
        }
    }

    /**
     * 初始化返回键拦截（横向模式时优先退出横向模式，而非 finish Activity）
     */
    private fun initBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isHorizontalMode) {
                    exitHorizontalMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /**
     * 进入横向浏览模式：重建 adapter + 显示 ViewPager2 并定位
     *
     * 简化说明：同 Activity 内无法复用跨 Activity 共享元素动画（ActivityOptionsCompat），改用淡入淡出过渡
     * 已知上限：失去从缩略图位置平滑放大到全屏的视觉效果
     * 升级路径：后续可手动实现共享元素过渡（临时 ImageView 从点击位置 ChangeBounds 动画到全屏）
     */
    private fun enterHorizontalMode(imageIdx: Int) {
        isHorizontalMode = true
        setupFullscreenViewPager()
        binding.viewPagerFullscreen.setCurrentItem(imageIdx, false)

        // 淡入显示 ViewPager2
        binding.viewPagerFullscreen.alpha = 0f
        binding.viewPagerFullscreen.visibility = View.VISIBLE
        binding.viewPagerFullscreen.animate().alpha(1f).setDuration(200).start()

        // 显示旋转工具栏 + 页码
        binding.layoutRotateToolbar.visibility = View.VISIBLE
        updatePageIndex(imageIdx)
        // 进入大图模式时隐藏画布页码（避免与 tvPageIndex 重叠）
        binding.tvCanvasPageIndex.visibility = View.GONE
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_CANVAS,
            "enterHorizontalMode: imageIdx=$imageIdx totalImages=${detailViewPagerAdapter?.getDataSize() ?: 0}",
            level = AppLog.Level.INFO
        )
    }

    /**
     * 退出横向浏览模式：隐藏 ViewPager2 + 同步索引回垂直列表滚动位置
     */
    private fun exitHorizontalMode() {
        val currentIdx = binding.viewPagerFullscreen.currentItem
        isHorizontalMode = false

        // 退出前恢复非沉浸式（确保 TitleBar 可见）
        if (isImmersive) {
            toggleImmersive()
        }

        // 淡出隐藏 ViewPager2
        binding.viewPagerFullscreen.animate().alpha(0f).setDuration(150).withEndAction {
            binding.viewPagerFullscreen.visibility = View.GONE
        }.start()

        // 隐藏旋转工具栏与页码（垂直模式不显示）
        binding.layoutRotateToolbar.visibility = View.GONE
        binding.tvPageIndex.visibility = View.GONE

        // 同步索引回垂直列表滚动位置（imageIndexToListPosition，V3 B-7）
        val listPos = canvasAdapter?.imageIndexToListPosition(currentIdx) ?: -1
        if (listPos >= 0) {
            binding.recyclerView.post {
                binding.recyclerView.smoothScrollToPosition(listPos)
            }
        }
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_CANVAS,
            "exitHorizontalMode: imageIdx=$currentIdx listPos=$listPos",
            level = AppLog.Level.INFO
        )
    }

    /**
     * 重建全屏 ViewPager2 adapter（每次进入时调用，保证 ImagePlay.allImageUrls 快照最新）
     *
     * 回调：
     * - 长按：保存/分享/复制URL菜单（复用 SAF 保存流程）
     * - 单击：切换沉浸式
     * - 页码变化：更新 tvPageIndex
     */
    private fun setupFullscreenViewPager() {
        val sourceOrigin = ImagePlay.rssSource?.sourceUrl
        val referer = ImagePlay.rssArticles?.getOrNull(ImagePlay.rssArticleIndex)?.link
        val adapter = ImageDetailViewPagerAdapter(this, sourceOrigin, referer)
        adapter.setCallback(object : ImageDetailAdapter.OnImageDetailCallback {
            override fun onImageLongClick(imageUrl: String, view: View) {
                showImageActionMenu(imageUrl)
            }

            override fun onImageClick() {
                toggleImmersive()
            }

            override fun onPageChanged(position: Int, total: Int) {
                if (isHorizontalMode) {
                    updatePageIndex(position)
                }
            }
        })
        detailViewPagerAdapter = adapter
        binding.viewPagerFullscreen.adapter = adapter
    }

    /**
     * 更新页码显示（"当前 / 总数"，单图或非横向模式时隐藏）
     */
    private fun updatePageIndex(position: Int) {
        val total = detailViewPagerAdapter?.getDataSize() ?: 0
        if (total > 1 && isHorizontalMode && !isImmersive) {
            binding.tvPageIndex.visibility = View.VISIBLE
            binding.tvPageIndex.text = "${position + 1} / $total"
        } else {
            binding.tvPageIndex.visibility = View.GONE
        }
    }

    /**
     * 切换沉浸式全屏（横向模式下单击图片触发）
     *
     * - true：隐藏系统栏/TitleBar/旋转工具栏/页码，全屏看图
     * - false：恢复显示
     */
    private fun toggleImmersive() {
        isImmersive = !isImmersive
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (isImmersive) {
            controller.hide(android.view.WindowInsets.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            binding.composeTopBar.visibility = View.GONE
            binding.layoutRotateToolbar.visibility = View.GONE
            binding.tvPageIndex.visibility = View.GONE
        } else {
            controller.show(android.view.WindowInsets.Type.systemBars())
            binding.composeTopBar.visibility = View.VISIBLE
            if (isHorizontalMode) {
                binding.layoutRotateToolbar.visibility = View.VISIBLE
                updatePageIndex(binding.viewPagerFullscreen.currentItem)
            }
        }
        AppLog.putDebugWithTag(AppLog.TAG_IMAGE_CANVAS, "toggleImmersive isImmersive=$isImmersive", level = AppLog.Level.INFO)
    }

    /**
     * 长按图片菜单：保存/分享/复制URL（复用 SAF 保存流程，与 ImageDetailActivity 一致）
     */
    private fun showImageActionMenu(imageUrl: String) {
        currentImageUrl = imageUrl
        alert("图片操作") {
            items(listOf("保存图片", "分享图片", "复制URL")) { _, which ->
                when (which) {
                    0 -> saveImage(imageUrl)
                    1 -> shareImage(imageUrl)
                    2 -> {
                        sendToClip(imageUrl)
                        toastOnUi("图片链接已复制")
                    }
                }
            }
        }
    }

    /**
     * 观察 ViewModel 加载状态（更新 Adapter footer）
     *
     * W11: 首次加载成功后提取图片 URL 域名调用 startPreheat（Cloudflare 防护预热）
     *
     * 修复 regression：移除原 notifyDataSetChanged，改由 observeNewItems 精准 notifyItemRangeInserted。
     * notifyDataSetChanged 会触发所有可见 ViewHolder 重新 bind → Glide.clear 取消正在进行的
     * downloadOnly 请求 → 图片永远加载不完的死循环。
     */
    private fun observeLoadState() {
        viewModel.loadState.observe(this) { state ->
            canvasAdapter?.setLoadState(state)
            // W11: 首次加载成功后触发 WebView 预热（提取图片 URL 域名）
            if (state is ImageCanvasAdapter.LoadState.SUCCESS && !isFirstPreheatCompleted) {
                val imageUrls = ImagePlay.allImageUrls.value
                    .mapNotNull { (it as? ImageCanvasItem.ImageItem)?.url }
                if (imageUrls.isNotEmpty()) {
                    AppLog.putDebugWithTag(
                        AppLog.TAG_IMAGE_CANVAS,
                        "observeLoadState: trigger startPreheat urlCount=${imageUrls.size}",
                        level = AppLog.Level.INFO
                    )
                    startPreheat(imageUrls)
                }
            }
        }
    }

    /**
     * 观察新增项事件，精准 notifyItemRangeInserted（修复 regression）
     *
     * 替代原 observeLoadState 中的 notifyDataSetChanged：
     * - notifyDataSetChanged 触发所有可见 ViewHolder 重新 bind → Glide.clear 取消正在进行的 downloadOnly
     * - notifyItemRangeInserted 只通知新增项的范围，RecyclerView 只 bind 新可见的 ViewHolder
     * - 已可见的 ViewHolder 不受影响，正在进行的 Glide 请求不会被取消
     */
    private fun observeNewItems() {
        viewModel.newItemsEvent.observe(this) { (startPos, itemCount) ->
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "observeNewItems: notifyItemRangeInserted startPos=$startPos itemCount=$itemCount",
                level = AppLog.Level.INFO
            )
            canvasAdapter?.notifyItemRangeInserted(startPos, itemCount)
            // 修复（image-canvas-3fix-20260728 Q1修复2）：首次插入后滚动到第一张图片（布局完成后执行）
            // 根因：原 post 在布局完成前执行被后续布局覆盖（铁证：008 日志 L107 lastVisible=24，
            // 80ms 后触发 loadNextArticle，scrollToPosition(0) 未生效或被覆盖）。
            // 修复：使用 OnGlobalLayoutListener 等待 RecyclerView 完成布局后执行 scrollToPosition(0)，
            // 此时高度已测量，滚动定位准确且不会被布局过程覆盖。
            if (!isInitialScrollDone && startPos == 0 && itemCount > 0) {
                isInitialScrollDone = true
                binding.recyclerView.viewTreeObserver.addOnGlobalLayoutListener(
                    object : ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            binding.recyclerView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            binding.recyclerView.scrollToPosition(0)
                            AppLog.putDebugWithTag(
                                AppLog.TAG_IMAGE_CANVAS,
                                "observeNewItems: initial scroll to position 0 (after layout)",
                                level = AppLog.Level.INFO
                            )
                            // 初始滚动完成后更新页码
                            val lm = binding.recyclerView.layoutManager as? LinearLayoutManager
                            if (lm != null) updateCanvasPageIndex(lm)
                        }
                    }
                )
            }
        }
    }

    /**
     * 更新右下角画布页码悬浮（当前文章图片索引/总数）
     *
     * 需求：图片播放器右下角悬浮展示当前正文图片总个数 + 当前下拉查看的第几张。
     * 实现：
     * - 获取第一个可见的 ImageItem 的 position
     * - 从 ImagePlay.allImageUrls 获取该 ImageItem 的 articleIndex
     * - 计算该 articleIndex 的图片总数（筛选同 articleIndex 的 ImageItem 数量）
     * - 计算当前图片在该 articleIndex 内的索引（当前 position 之前同 articleIndex 的 ImageItem 数量）
     * - 显示 "{当前文章内索引+1} / {当前文章图片总数}"
     */
    private fun updateCanvasPageIndex(layoutManager: LinearLayoutManager) {
        val snapshot = ImagePlay.allImageUrls.value
        if (snapshot.isEmpty()) {
            binding.tvCanvasPageIndex.visibility = View.GONE
            return
        }
        val firstVisiblePos = layoutManager.findFirstVisibleItemPosition()
        if (firstVisiblePos == RecyclerView.NO_POSITION || firstVisiblePos >= snapshot.size) {
            binding.tvCanvasPageIndex.visibility = View.GONE
            return
        }
        // 找到第一个可见的 ImageItem（跳过 ArticleDivider）
        var currentPos = firstVisiblePos
        var currentItem: ImageCanvasItem.ImageItem? = null
        while (currentPos <= layoutManager.findLastVisibleItemPosition() && currentPos < snapshot.size) {
            val item = snapshot[currentPos]
            if (item is ImageCanvasItem.ImageItem) {
                currentItem = item
                break
            }
            currentPos++
        }
        if (currentItem == null) {
            binding.tvCanvasPageIndex.visibility = View.GONE
            return
        }
        val articleIndex = currentItem.articleIndex
        // 计算该 articleIndex 的图片总数和当前图片在该 articleIndex 内的索引
        var articleTotal = 0
        var currentIndexInArticle = 0
        for (i in 0..currentPos) {
            val item = snapshot[i]
            if (item is ImageCanvasItem.ImageItem && item.articleIndex == articleIndex) {
                articleTotal++
                if (i < currentPos) {
                    currentIndexInArticle++
                }
            }
        }
        // 补全后续同 articleIndex 的图片数（currentPos 之后可能还有同 articleIndex 的图片）
        for (i in (currentPos + 1) until snapshot.size) {
            val item = snapshot[i]
            if (item is ImageCanvasItem.ImageItem && item.articleIndex == articleIndex) {
                articleTotal++
            } else if (item is ImageCanvasItem.ArticleDivider) {
                // 遇到下一个文章的分隔符，停止计数
                break
            }
        }
        if (articleTotal <= 1) {
            // 单图时隐藏页码
            binding.tvCanvasPageIndex.visibility = View.GONE
        } else {
            binding.tvCanvasPageIndex.visibility = View.VISIBLE
            binding.tvCanvasPageIndex.text = "${currentIndexInArticle + 1} / $articleTotal"
        }
    }

    // ==================== WebView 预热串行化（V4 AD-05，复用源码现有字段） ====================

    /**
     * 初始化预热 WebView（方案A：Cloudflare 防护）
     *
     * V4 AD-05 修订：串行队列预热（forEach loadUrl 循环覆盖仅最后一个域名预热）
     * - 配置 WebView JavaScript 启用（Cloudflare JS 挑战需要）
     * - WebViewClient.onPageFinished 串行触发下一个域名 loadUrl
     * - CookieManager.flush() 同步 cookies 到 CookieStore 供 Glide 复用
     */
    private fun initPreheatWebView() {
        binding.webviewPreheat.settings.javaScriptEnabled = true
        binding.webviewPreheat.settings.domStorageEnabled = true
        binding.webviewPreheat.settings.databaseEnabled = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webviewPreheat, true)
        }
        binding.webviewPreheat.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url.isNullOrBlank()) return
                AppLog.putDebugWithTag(AppLog.TAG_IMAGE_CANVAS, "preheat onPageFinished: urlLen=${url.length}", level = AppLog.Level.INFO)
                // 同步 cookies 到 CookieStore
                android.webkit.CookieManager.getInstance().flush()
                // 标记当前域名预热完成
                val domain = NetworkUtils.getSubDomain(url)
                preheatedDomains.add(domain)
                pendingPreheatDomains.remove(domain)
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_CANVAS,
                    "Preheat: domain completed preheatedCount=${preheatedDomains.size} remaining=${pendingPreheatDomains.size}",
                    level = AppLog.Level.INFO
                )
                // I-P0-2: 降级预热完成触发待重载 position（cookies 已 flush，可携 WebView cookies 重新拉取）
                // 与 onWebViewFallback 的 5s 超时兜底幂等（remove 语义，先触发者生效）
                triggerFallbackReload(domain)
                // V4 AD-05: 串行触发下一个域名
                processNextPreheat()
            }
        }
        AppLog.putDebugWithTag(AppLog.TAG_IMAGE_CANVAS, "initPreheatWebView done", level = AppLog.Level.INFO)
    }

    /**
     * 启动域名预热（V4 AD-05：串行队列）
     *
     * - 提取所有图片 URL 的根域名（去重）
     * - 过滤已预热的域名
     * - 加入 pendingPreheatDomains 队列
     * - 调用 processNextPreheat() 串行触发第一个
     */
    private fun startPreheat(urls: List<String>) {
        if (isFirstPreheatCompleted) return
        val domains = urls.mapNotNull { url ->
            try { NetworkUtils.getSubDomain(url) } catch (e: Exception) { null }
        }.toSet()
        val needPreheat = domains.filter { it !in preheatedDomains }
        if (needPreheat.isEmpty()) {
            isFirstPreheatCompleted = true
            AppLog.putDebugWithTag(AppLog.TAG_IMAGE_CANVAS, "startPreheat: all domains already preheated", level = AppLog.Level.INFO)
            return
        }
        pendingPreheatDomains.clear()
        pendingPreheatDomains.addAll(needPreheat)
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_CANVAS,
            "Preheat: start queueSize=${pendingPreheatDomains.size} urlCount=${urls.size}",
            level = AppLog.Level.INFO
        )
        processNextPreheat()
    }

    /**
     * 串行触发下一个域名预热（V4 AD-05）
     *
     * - 取 pendingPreheatDomains.first() 加载
     * - onPageFinished 回调中 remove + 递归调用本方法
     * - 全部完成时发送 PREHEAT_COMPLETED 日志
     */
    private fun processNextPreheat() {
        if (pendingPreheatDomains.isEmpty()) {
            if (!isFirstPreheatCompleted) {
                isFirstPreheatCompleted = true
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_CANVAS,
                    "Preheat: all completed totalCount=${preheatedDomains.size}",
                    level = AppLog.Level.INFO
                )
            }
            return
        }
        val nextDomain = pendingPreheatDomains.first()
        val preheatUrl = "https://$nextDomain/"
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_CANVAS,
            "Preheat: start domain=${nextDomain.hashCode()} remaining=${pendingPreheatDomains.size}",
            level = AppLog.Level.INFO
        )
        binding.webviewPreheat.loadUrl(preheatUrl)
    }

    /**
     * I-P0-2: 触发降级预热完成后的图片重载
     *
     * 调用时机（两处，幂等——remove 语义先触发者生效，后到者取 null 直接 return）：
     * 1. onPageFinished 正常完成（cookies 已 flush）
     * 2. onWebViewFallback 的 5s 超时兜底（弱网/WebView 卡住，onPageFinished 未触发）
     *
     * @param domain 预热完成的域名（null/blank 时防御性跳过）
     */
    private fun triggerFallbackReload(domain: String?) {
        if (domain.isNullOrBlank()) return
        val positions = pendingFallbackReload.remove(domain) ?: return
        if (positions.isEmpty()) return
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_CANVAS,
            "ImageFallback: trigger reload positionCount=${positions.size} positions=${positions.sorted()}",
            level = AppLog.Level.INFO
        )
        canvasAdapter?.markPreheatReload(positions)
    }

    // ==================== 私有方法 ====================

    /**
     * 保存图片到本地（参考 ReadRssActivity.saveImage 逻辑）
     */
    private fun saveImage(imageUrl: String) {
        val path = ACache.get().getAsString(AppConst.imagePathKey)
        if (path.isNullOrEmpty()) {
            selectImageDir.launch(null)
        } else {
            viewModel.saveImage(imageUrl, ImagePlay.rssSource?.sourceUrl, android.net.Uri.parse(path))
        }
    }

    /**
     * 分享图片（简化实现：复制 URL 到剪贴板）
     */
    private fun shareImage(imageUrl: String) {
        sendToClip(imageUrl)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Phase 3.4: 清理预加载任务（避免 Activity 销毁后执行）
        preloadRunnable?.let { binding.recyclerView.removeCallbacks(it) }
        preloadRunnable = null
        // V2 O-3: 清理垂直画布状态（避免 Activity 销毁后再次进入继承上次 allImageUrls）
        val clearedSize = ImagePlay.allImageUrls.value.size
        ImagePlay.clearImageCanvasState()
        // 位置记忆：记录当前正在看的文章 link
        ImagePlay.rssArticles?.getOrNull(ImagePlay.rssArticleIndex)?.link?.let {
            ImagePlay.lastPlayedArticleLink = it
        }
        // E5: 销毁预热 WebView 释放内存（每个 WebView 30-50MB，未销毁导致内存泄漏）
        kotlin.runCatching {
            binding.webviewPreheat.apply {
                stopLoading()
                webChromeClient = null
                webViewClient = WebViewClient()
                destroy()
            }
        }.onFailure { e ->
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "WebView.destroy failed: ${e.message}",
                level = AppLog.Level.WARN
            )
        }
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_CANVAS,
            "ImageGalleryActivity: onDestroy hashCode=${this.hashCode()} clearedAllImageUrls=$clearedSize",
            level = AppLog.Level.INFO
        )
    }
}
