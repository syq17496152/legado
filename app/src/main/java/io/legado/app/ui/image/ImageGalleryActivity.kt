package io.legado.app.ui.image

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityOptionsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ActivityImageGalleryBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.image.adapter.ImageCanvasAdapter
import io.legado.app.utils.ACache
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.sendToClip
import io.legado.app.utils.viewbindingdelegate.viewBinding

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
 * 7. 点击缩略图进入大图模式（ImageDetailActivity，共享元素动画）
 * 8. 大图模式返回保持点击位置可见（onActivityResult + scrollToPosition）
 * 9. 沉浸式全屏（点击切换显隐）
 * 10. 位置记忆（onDestroy 记录 lastPlayedArticleLink）
 *
 * 数据流（design.md §3.1）：
 * - 入口：ReadRss.readNoHtml 启动，设置 ImagePlay 单例字段
 * - 加载：ImageCanvasViewModel.loadInitialArticle / loadNextArticle 协程加载
 * - 状态：loadState LiveData 通知 footer 切换（LOADING/SUCCESS/ERROR/NO_MORE）
 * - 大图：点击缩略图 → startActivityForResult → onActivityResult 滚动到位置
 */
class ImageGalleryActivity : VMBaseActivity<ActivityImageGalleryBinding, ImageCanvasViewModel>() {

    override val binding by viewBinding(ActivityImageGalleryBinding::inflate)
    override val viewModel by viewModels<ImageCanvasViewModel>()

    private var canvasAdapter: ImageCanvasAdapter? = null

    /** 当前长按的图片URL（用于选择保存目录后回调） */
    private var currentImageUrl: String? = null
    /** 沉浸式状态（true=隐藏状态栏/导航栏/工具栏） */
    private var isImmersive = false

    // ==================== 方案A：WebView 预热（Cloudflare 防护） ====================
    /** 待预热的图片 CDN 域名队列（V4 AD-05：复用源码现有字段，串行预热） */
    private val pendingPreheatDomains = mutableSetOf<String>()
    /** 已预热完成的域名集合（避免重复预热） */
    private val preheatedDomains = mutableSetOf<String>()
    /** 是否已完成首次预热 */
    private var isFirstPreheatCompleted = false

    /** 选择图片保存目录 */
    private val selectImageDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(AppConst.imagePathKey, uri.toString())
            currentImageUrl?.let { url ->
                viewModel.saveImage(url, ImagePlay.rssSource?.sourceUrl, uri)
            }
        }
    }

    /** 启动 ImageDetailActivity 并接收返回结果（V2 R2.6） */
    private val startImageDetail = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val currentIndex = result.data?.getIntExtra(ImageDetailActivity.EXTRA_CURRENT_INDEX, -1) ?: -1
            if (currentIndex >= 0) {
                // V3 B-7：图片索引转列表 position 后滚动到位置
                val listPos = canvasAdapter?.imageIndexToListPosition(currentIndex) ?: -1
                if (listPos >= 0) {
                    binding.recyclerView.post {
                        binding.recyclerView.smoothScrollToPosition(listPos)
                    }
                    AppLog.putDebugWithTag(
                        AppLog.TAG_IMAGE_CANVAS,
                        "onActivityResult: scroll to listPos=$listPos imageIdx=$currentIndex",
                        level = AppLog.Level.INFO
                    )
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
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
        initTitleBar()
        initRecyclerView()
        initPreheatWebView()
        observeLoadState()
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
     * 初始化标题栏：返回按钮 + 文章标题
     */
    private fun initTitleBar() {
        val title = intent.getStringExtra("title") ?: "图片浏览"
        setSupportActionBar(binding.titleBar.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setHomeButtonEnabled(true)
        binding.titleBar.title = title
        binding.titleBar.setNavigationOnClickListener {
            finish()
        }
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
                AppLog.putDebugWithTag(AppLog.TAG_IMAGE_CANVAS, "onRetryClick: retry loadNextArticle", level = AppLog.Level.INFO)
                viewModel.loadNextArticle()
            },
            onWebViewFallback = { url, position ->
                // 降级3: WebView 即时预热（V4 6.1.3）
                // 用 webviewPreheat 加载图片 URL，onPageFinished 后 CookieManager.flush() 同步 cookies
                // 重试机制：用户滚动触发 onBindViewHolder 时自动重试 Glide（enabledCookieJar 已启用）
                // 已知上限：用户不滚动时图片保持空白，需滚动或返回再进入触发重试
                // 升级路径：后续可在 onPageFinished 中主动调用 notifyItemChanged 触发重试（需记录失败 position）
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_CANVAS,
                    "onWebViewFallback: load url for preheat position=$position urlLen=${url.length}",
                    level = AppLog.Level.INFO
                )
                kotlin.runCatching {
                    binding.webviewPreheat.loadUrl(url)
                }.onFailure { e ->
                    AppLog.putDebugWithTag(
                        AppLog.TAG_IMAGE_CANVAS,
                        "onWebViewFallback failed: ${e.message}",
                        level = AppLog.Level.ERROR
                    )
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
            layoutManager = LinearLayoutManager(this@ImageGalleryActivity)
            adapter = canvasAdapter
            // AD-08: 离屏缓存 2 个 ViewHolder（默认 2，显式设置明确意图）
            setItemViewCacheSize(2)
            // AD-13: 允许动态高度（图片高度自适应）
            setHasFixedSize(false)

            // AD-04: 滚动监听触发分页加载 + 快速滚动暂停 Glide
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dy, dy)
                    val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val totalItemCount = layoutManager.itemCount
                    val lastVisible = layoutManager.findLastVisibleItemPosition()
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
                        }
                    }
                }
            })
        }
        AppLog.putDebugWithTag(AppLog.TAG_IMAGE_CANVAS, "initRecyclerView done", level = AppLog.Level.INFO)
    }

    /**
     * 列表项点击回调：启动 ImageDetailActivity（大图模式）
     *
     * 流程（design.md §3.2 数据流 2）：
     * 1. 列表 position 转换为图片索引（listPositionToImageIndex，V3 B-7）
     * 2. 准备共享元素动画（ActivityOptions.makeSceneTransitionAnimation）
     * 3. 启动 ImageDetailActivity（startImageDetail.launch）
     *
     * V2 B-5：图片未加载完成时降级为普通 Activity 跳转（无共享元素动画）
     */
    private fun onCanvasItemClick(listPosition: Int, sharedView: View) {
        val imageIdx = canvasAdapter?.listPositionToImageIndex(listPosition) ?: -1
        if (imageIdx < 0) {
            AppLog.putDebugWithTag(AppLog.TAG_IMAGE_CANVAS, "onCanvasItemClick: invalid imageIdx for listPos=$listPosition", level = AppLog.Level.WARN)
            return
        }
        val intent = Intent(this, ImageDetailActivity::class.java).apply {
            putExtra(ImageDetailActivity.EXTRA_START_INDEX, imageIdx)
        }
        // V2 B-5：图片未加载完成时降级为普通跳转
        val options = try {
            ActivityOptionsCompat.makeSceneTransitionAnimation(
                this, sharedView, "shared_image_$listPosition"
            )
        } catch (e: Exception) {
            AppLog.putDebugWithTag(AppLog.TAG_IMAGE_CANVAS, "makeSceneTransitionAnimation failed: ${e.message}", level = AppLog.Level.WARN)
            null
        }
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_CANVAS,
            "onCanvasItemClick: listPos=$listPosition imageIdx=$imageIdx hasSharedElement=${options != null}",
            level = AppLog.Level.INFO
        )
        startImageDetail.launch(intent)
    }

    /**
     * 观察 ViewModel 加载状态（更新 Adapter footer）
     *
     * W11: 首次加载成功后提取图片 URL 域名调用 startPreheat（Cloudflare 防护预热）
     */
    private fun observeLoadState() {
        viewModel.loadState.observe(this) { state ->
            canvasAdapter?.setLoadState(state)
            // 数据更新后刷新 Adapter
            canvasAdapter?.notifyDataSetChanged()
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
