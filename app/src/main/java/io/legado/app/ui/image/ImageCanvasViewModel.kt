package io.legado.app.ui.image

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.bumptech.glide.request.RequestOptions
import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.help.image.ImageUrlExtractor
import io.legado.app.ui.image.adapter.ImageCanvasAdapter
import io.legado.app.utils.ACache
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeBytes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 图片垂直画布 ViewModel（V4 实施 Phase 1.5）
 *
 * 设计参考：design.md §5.2 ImageCanvasViewModel 协程管理 + AD-04/AD-07/AD-11/AD-12
 *
 * 职责：
 * 1. 加载首篇文章图片 URL 列表（loadInitialArticle）
 * 2. 分页加载下一篇图片 URL 列表（loadNextArticle）
 * 3. 协程取消与去重（loadJob?.cancel / isLaunching / loadedArticleIndices）
 * 4. 状态通知（LoadState: LOADING/SUCCESS/ERROR/NO_MORE）
 * 5. 协程池配置变更响应（E2：监听 AppConfig.updateCacheThreadCount 重建 executor）
 *
 * 数据源：从 ImagePlay 单例读取 rssSource / rssArticles / rssArticleIndex
 * 写入：通过 ImagePlay.appendItems() 写入 allImageUrls（StateFlow 封装，线程安全）
 *
 * 协程取消机制（AD-07）：
 * - loadJob?.cancel() 在新加载请求前取消上一个
 * - loadNextArticle 内部 ensureActive() 检查协程是否已取消
 * - onCleared() 取消所有协程（ViewModel 生命周期绑定 Activity）
 *
 * 日志规范（tasks.md §AOAdapt 日志模板）：
 * - 永久日志：AppLog.putDebugWithTag + TAG_IMAGE_CANVAS（recordLog 守卫）
 * - 临时日志：Log.d + "ImageCanvasDebug"（验证后 Grep 一次性移除）
 */
class ImageCanvasViewModel(application: Application) : BaseViewModel(application) {

    /** 加载状态 LiveData（供 Activity 观察更新 footer） */
    private val _loadState = MutableLiveData<ImageCanvasAdapter.LoadState>(ImageCanvasAdapter.LoadState.IDLE)
    val loadState: MutableLiveData<ImageCanvasAdapter.LoadState> = _loadState

    /**
     * 新增项事件 LiveData（修复 regression：notifyDataSetChanged 导致 Glide 请求被取消）
     *
     * 触发时机：loadArticleInternal 成功后 setValue(startPos to itemCount)
     * Activity 观察后调用 notifyItemRangeInserted(startPos, itemCount) 精准插入，
     * 避免 notifyDataSetChanged 触发所有可见 ViewHolder 重新 bind → Glide.clear 取消
     * 正在进行的 downloadOnly 请求 → 图片永远加载不完的死循环。
     *
     * 修复（rss-image-load-optimization crash-fix）：postValue → setValue
     * postValue 异步排队 + 值合并会导致 notifyItemRangeInserted 晚于 RecyclerView 布局触发，
     * 数据源已追加而 adapter 未通知 → Inconsistency detected 崩溃。setValue 在主线程同步回调。
     */
    private val _newItemsEvent = MutableLiveData<Pair<Int, Int>>()
    val newItemsEvent: MutableLiveData<Pair<Int, Int>> = _newItemsEvent

    /** 当前加载协程（AD-07：新加载前取消上一个） */
    private var loadJob: Coroutine<*>? = null

    /** 防重复触发标志（AD-04：快速滚动时连续触发 loadNextArticle 的保护） */
    @Volatile
    private var isLaunching = false

    /**
     * 协程池（E2/W6：基于 AppConfig.updateCacheThreadCount 默认 16，配置变更时重建）
     *
     * 用于图片加载相关异步任务的线程池调度，复用全局"更新+缓存"类线程数配置。
     */
    // R3 钳制：平台线程池上限 128，防 256 配置下线程栈膨胀（AD-10）；配置变更重建路径同钳制
    private var coroutineExecutor: ExecutorService =
        Executors.newFixedThreadPool(minOf(AppConfig.updateCacheThreadCount, 128))

    /**
     * 协程池配置变更观察者（W6：监听 PreferKey.updateCacheThreadCount 事件）
     * 在 init 块注册 observeForever，onCleared 中 removeObserver 防止泄漏
     */
    private val threadCountObserver = Observer<String> {
        onCoroutinePoolConfigChanged(AppConfig.updateCacheThreadCount)
    }

    init {
        LiveEventBus.get<String>(PreferKey.updateCacheThreadCount)
            .observeForever(threadCountObserver)
    }

    /**
     * 协程池配置变更回调（E2：四步流程）
     *
     * 流程：
     * 1. 取消 loadJob（防止旧任务使用已关闭的池）
     * 2. shutdown 旧池
     * 3. 创建新池（基于最新 AppConfig.updateCacheThreadCount）
     * 4. 重新触发加载（仅当已有文章加载过时，避免误触发首次加载）
     *
     * @param newSize 新的线程池大小（来自 AppConfig.updateCacheThreadCount）
     */
    private fun onCoroutinePoolConfigChanged(newSize: Int) {
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_CANVAS,
            "onCoroutinePoolConfigChanged: newSize=$newSize, rebuild executor",
            level = AppLog.Level.INFO
        )
        // 步骤1: 取消当前加载协程（防止旧任务使用已关闭的池）
        loadJob?.cancel()
        // 重置 isLaunching 标志（cancel 后 onError 不会触发 CancellationException，需手动重置）
        isLaunching = false
        // 步骤2: shutdown 旧池
        coroutineExecutor.shutdownNow()
        // 步骤3: 创建新池（R3 钳制 128，与初始创建路径一致，AD-10）
        coroutineExecutor = Executors.newFixedThreadPool(minOf(newSize, 128))
        // 步骤4: 重新触发加载（仅当有已加载文章时，避免误触发首次加载）
        if (ImagePlay.loadedArticleIndices.isNotEmpty()) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "onCoroutinePoolConfigChanged: re-trigger loadNextArticle after pool rebuild",
                level = AppLog.Level.INFO
            )
            loadNextArticle()
        }
    }

    /**
     * 加载首篇文章图片（Activity onCreate 时调用）
     *
     * 流程（design.md §3.1 数据流 1）：
     * 1. 从 ImagePlay 单例读取 rssSource / rssArticles / rssArticleIndex
     * 2. 调用 Rss.getContentAwait 获取 body（String 类型）
     * 3. parseImageUrls 解析为 List<String>
     * 4. 转换为 List<ImageCanvasItem.ImageItem> 后调用 ImagePlay.appendItems
     * 5. loadedArticleIndices.add(articleIndex)
     *
     * W10: 入口检查 isLaunching，防止 Activity 因配置变更重建时重复加载
     */
    fun loadInitialArticle() {
        // W10: 防止 Activity 因配置变更重建时重复加载
        if (isLaunching) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "loadInitialArticle: skipped, isLaunching=true (config change rebuild guard)",
                level = AppLog.Level.INFO
            )
            return
        }
        val rssSource = ImagePlay.rssSource
        val rssArticles = ImagePlay.rssArticles
        if (rssSource == null || rssArticles.isNullOrEmpty()) {
            // V-004-P0-ImageLog: 关键失败点改用 putError（无条件输出，不受 recordLog 守卫）
            AppLog.putError(
                "ImageCanvas loadInitialArticle failed: rssSource=${rssSource == null} rssArticles=${rssArticles?.size ?: 0}",
                null
            )
            _loadState.setValue(ImageCanvasAdapter.LoadState.ERROR(
                IllegalStateException("订阅源或文章列表为空")
            ))
            return
        }
        val initialIndex = ImagePlay.rssArticleIndex
        if (initialIndex !in rssArticles.indices) {
            // V-004-P0-ImageLog: 关键失败点改用 putError
            AppLog.putError(
                "ImageCanvas loadInitialArticle failed: initialIndex=$initialIndex outOfRange (0, ${rssArticles.size})",
                null
            )
            _loadState.setValue(ImageCanvasAdapter.LoadState.ERROR(
                IndexOutOfBoundsException("初始文章索引越界")
            ))
            return
        }
        loadArticleInternal(initialIndex, isInitial = true)
    }

    /**
     * 分页加载下一篇图片（RecyclerView 滚动到底部时触发）
     *
     * 流程（design.md §3.3 数据流 3）：
     * 1. 检查 isLaunching 防止重复触发
     * 2. loadJob?.cancel() 取消上一个加载（AD-07）
     * 3. 计算下一篇文章索引：loadedArticleIndices.max() + 1
     * 4. 越界检查：nextIndex >= rssArticles.size 时返回 NO_MORE
     * 5. 去重：loadedArticleIndices.contains(nextIndex) 时跳过
     * 6. 调用 Rss.getContentAwait + parseImageUrls
     * 7. ImagePlay.appendItems + loadedArticleIndices.add
     *
     * 协程取消（AD-07 + AD-11）：
     * - ensureActive() 检查协程是否已取消（避免旧任务结果污染新数据）
     */
    fun loadNextArticle() {
        if (isLaunching) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "loadNextArticle: skipped, isLaunching=true",
                level = AppLog.Level.INFO
            )
            return
        }
        val rssSource = ImagePlay.rssSource
        val rssArticles = ImagePlay.rssArticles
        if (rssSource == null || rssArticles.isNullOrEmpty()) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "loadNextArticle: skipped, rssSource/rssArticles null",
                level = AppLog.Level.INFO
            )
            return
        }

        // 计算下一篇文章索引
        val nextIndex = ImagePlay.loadedArticleIndices.maxOrNull()?.plus(1) ?: run {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "loadNextArticle: no loaded articles, skip",
                level = AppLog.Level.INFO
            )
            return
        }
        if (nextIndex >= rssArticles.size) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "loadNextArticle: no more articles, nextIndex=$nextIndex total=${rssArticles.size}",
                level = AppLog.Level.INFO
            )
            _loadState.setValue(ImageCanvasAdapter.LoadState.NO_MORE)
            return
        }
        if (ImagePlay.loadedArticleIndices.contains(nextIndex)) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "loadNextArticle: nextIndex=$nextIndex already loaded, skip",
                level = AppLog.Level.INFO
            )
            return
        }
        loadArticleInternal(nextIndex, isInitial = false)
    }

    /**
     * 文章加载内部实现（复用 loadInitialArticle / loadNextArticle）
     *
     * @param articleIndex 待加载的文章索引
     * @param isInitial true=首篇文章（loading 状态显示在 footer），false=分页加载
     */
    private fun loadArticleInternal(articleIndex: Int, isInitial: Boolean) {
        // AD-07: 取消上一个加载协程
        loadJob?.cancel()
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_CANVAS,
            "loadArticleInternal: cancel previous job, articleIndex=$articleIndex isInitial=$isInitial",
            level = AppLog.Level.INFO
        )

        isLaunching = true
        _loadState.setValue(ImageCanvasAdapter.LoadState.LOADING)

        loadJob = execute {
            // AD-11: 协程取消检查（防止旧任务结果污染新数据）
            currentCoroutineContext().ensureActive()

            val rssSource = ImagePlay.rssSource ?: throw IllegalStateException("rssSource is null")
            val rssArticles = ImagePlay.rssArticles ?: throw IllegalStateException("rssArticles is null")
            val article = rssArticles[articleIndex]

            val ruleContent = rssSource.ruleContent
            val ruleImage = rssSource.ruleImage

            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "ImageUrlExtractor: start articleIndex=$articleIndex ruleContentLen=${ruleContent?.length ?: 0} ruleImageLen=${ruleImage?.length ?: 0}",
                level = AppLog.Level.INFO
            )
            val startTime = System.currentTimeMillis()

            // 调用 ImageUrlExtractor 三层降级链路（L1 静态解析 → L2 WebView 嗅探 → L3 合并）
            val imageUrls = ImageUrlExtractor.extractImageList(article, rssSource, ruleContent, ruleImage)
            // 协程取消检查（嗅探后）
            currentCoroutineContext().ensureActive()

            val costMs = System.currentTimeMillis() - startTime
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "ImageUrlExtractor: end articleIndex=$articleIndex costMs=$costMs imageCount=${imageUrls.size}",
                level = AppLog.Level.INFO
            )

            if (imageUrls.isEmpty()) {
                throw IllegalStateException("ImageUrlExtractor returned empty list")
            }

            // 转换为 ImageCanvasItem.ImageItem 列表
            val imageItems = imageUrls.mapIndexed { idx, url ->
                ImageCanvasItem.ImageItem(
                    url = url,
                    articleIndex = articleIndex,
                    imageIndex = idx
                )
            }

            // H7(sniff-regression-rss-image-crash) 真实崩溃根因修复（模拟器 2026-08-30 复现实锤：
            // FATAL IndexOutOfBoundsException Inconsistency detected，与用户真机崩溃同型）：
            // 原实现 appendItems 在 execute(IO 线程) 内同步更新 StateFlow 数据源，而
            // notifyItemRangeInserted 在主线程 onSuccess 才发生——窗口期内任何布局读到
            // "数据源已变大但 RecyclerView 未收到通知"的 itemCount → Invalid view holder
            // adapter position。旧注释"onSuccess 与 append 同一主线程消息"假设错误：append
            // 并不在主线程。修复：execute 只返回待追加数据（divider + items），
            // 数据源追加全部移入主线程 onSuccess，与 notify 同一主线程消息内完成。
            val divider = if (!isInitial) {
                // W5: 文章分隔符插入（首篇文章除外，isInitial=true 时由 Activity 直接展示无需分隔符）
                val articleTitle = article.title.takeIf { it.isNotBlank() } ?: article.origin
                ImageCanvasItem.ArticleDivider(articleIndex, articleTitle)
            } else {
                null
            }
            divider to imageItems
        }.onSuccess { (divider, imageItems) ->
            isLaunching = false
            // 主线程：先追加数据源，同一消息内立即 notify（setValue 同步回调观察者），
            // 布局永远不会观察到中间态
            val startPos = ImagePlay.allImageUrls.value.size
            divider?.let { ImagePlay.appendItems(listOf(it)) }
            ImagePlay.appendItems(imageItems)
            ImagePlay.loadedArticleIndices.add(articleIndex)
            val itemCount = (if (divider != null) 1 else 0) + imageItems.size

            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "loadArticleInternal: success articleIndex=$articleIndex loadedCount=${imageItems.size} totalImages=${ImagePlay.allImageUrls.value.size} newItemsStart=$startPos newItemsCount=$itemCount",
                level = AppLog.Level.INFO
            )

            // Activity 调用 notifyItemRangeInserted 精准插入，避免 notifyDataSetChanged
            // 触发所有可见 ViewHolder 重新 bind → Glide.clear 取消正在进行的 downloadOnly
            // 顺序：先插入新项（newItemsEvent）再更新 footer（loadState），避免 footer 位置越界。
            _newItemsEvent.setValue(startPos to itemCount)
            _loadState.setValue(ImageCanvasAdapter.LoadState.SUCCESS)
        }.onError { e ->
            isLaunching = false
            // Coroutine 内部已守卫 CancellationException（重新抛出不触发 onError）
            // V-004-P0-ImageLog: 关键失败点改用 putError（无条件输出，不受 recordLog 守卫）
            AppLog.putError(
                "ImageCanvas loadArticleInternal failed: articleIndex=$articleIndex e=${e::class.simpleName} msg=${e.message?.take(200)}",
                e
            )
            _loadState.setValue(ImageCanvasAdapter.LoadState.ERROR(e))
        }
    }

    // parseImageUrls 方法已迁移到 ImageUrlExtractor.enhancedParseImageUrls（三层降级链路 L1）
    // 包含 9 策略：纯URL列表/ruleImage选择器/<img>标签/<picture><source>/CSS background-image/
    // og:image Meta/Script JSON/JS变量/所有URL正则/单URL兜底
    // 详见：app/src/main/java/io/legado/app/help/image/ImageUrlExtractor.kt

    /**
     * 保存图片到指定目录（用 Glide asFile 加载，支持 Referer/Cookie 注入）
     *
     * @param imageUrl 图片URL
     * @param sourceOrigin 订阅源URL（用于 Referer 注入，解决 CDN 防盗链）
     * @param uri 目标目录 URI（用户选择的保存目录）
     */
    fun saveImage(imageUrl: String, sourceOrigin: String?, uri: Uri) {
        execute {
            val fileName = "${AppConst.fileNameFormat.format(Date(System.currentTimeMillis()))}.jpg"
            // 用 Glide asFile() 加载图片到缓存文件（支持 sourceOrigin 注入 Referer/Cookie）
            val file = ImageLoader.loadFile(context, imageUrl).apply {
                sourceOrigin?.let { origin ->
                    apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, origin))
                }
            }.submit().get()  // 同步加载（已在 IO 线程）
            val byteArray = file.readBytes()
            uri.writeBytes(context, fileName, byteArray)
        }.onError {
            ACache.get().remove(AppConst.imagePathKey)
            AppLog.put("保存图片失败", it, true)
            context.toastOnUi("保存图片失败:${it.localizedMessage}")
        }.onSuccess {
            context.toastOnUi("保存成功")
        }
    }

    /**
     * ViewModel 销毁时取消所有协程（AD-07）+ 释放协程池资源（E2）
     *
     * - loadJob?.cancel() 取消当前加载
     * - coroutineExecutor.shutdownNow() 关闭协程池
     * - removeObserver(threadCountObserver) 移除 LiveEventBus 监听防止泄漏
     * - BaseViewModel.onCleared 自动取消 viewModelScope 内所有协程
     */
    override fun onCleared() {
        super.onCleared()
        val jobActive = loadJob != null
        loadJob?.cancel()
        // E2: 关闭协程池
        coroutineExecutor.shutdownNow()
        // 移除 LiveEventBus 观察者（防止内存泄漏）
        LiveEventBus.get<String>(PreferKey.updateCacheThreadCount)
            .removeObserver(threadCountObserver)
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_CANVAS,
            "ImageCanvasViewModel: onCleared hasPendingJob=$jobActive",
            level = AppLog.Level.INFO
        )
    }
}
