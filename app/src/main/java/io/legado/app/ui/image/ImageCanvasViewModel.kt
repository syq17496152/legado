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
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.rss.Rss
import io.legado.app.ui.image.adapter.ImageCanvasAdapter
import io.legado.app.utils.ACache
import io.legado.app.utils.NetworkUtils
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
    private var coroutineExecutor: ExecutorService =
        Executors.newFixedThreadPool(AppConfig.updateCacheThreadCount)

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
        // 步骤3: 创建新池
        coroutineExecutor = Executors.newFixedThreadPool(newSize)
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
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "loadInitialArticle: rssSource or rssArticles is null, cannot load",
                level = AppLog.Level.ERROR
            )
            _loadState.postValue(ImageCanvasAdapter.LoadState.ERROR(
                IllegalStateException("订阅源或文章列表为空")
            ))
            return
        }
        val initialIndex = ImagePlay.rssArticleIndex
        if (initialIndex !in rssArticles.indices) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "loadInitialArticle: initialIndex=$initialIndex outOfRange (0, ${rssArticles.size})",
                level = AppLog.Level.ERROR
            )
            _loadState.postValue(ImageCanvasAdapter.LoadState.ERROR(
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
            _loadState.postValue(ImageCanvasAdapter.LoadState.NO_MORE)
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
        _loadState.postValue(ImageCanvasAdapter.LoadState.LOADING)

        loadJob = execute {
            // AD-11: 协程取消检查（防止旧任务结果污染新数据）
            currentCoroutineContext().ensureActive()

            val rssSource = ImagePlay.rssSource ?: throw IllegalStateException("rssSource is null")
            val rssArticles = ImagePlay.rssArticles ?: throw IllegalStateException("rssArticles is null")
            val article = rssArticles[articleIndex]

            val ruleContent = rssSource.ruleContent
            val ruleImage = rssSource.ruleImage
            // ruleContent 为空时，用 body@html 获取文章详情页 HTML
            val effectiveRule = if (ruleContent.isNullOrBlank()) "body@html" else ruleContent

            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "RssFetch: start articleIndex=$articleIndex ruleContentLen=${ruleContent?.length ?: 0} ruleImageLen=${ruleImage?.length ?: 0}",
                level = AppLog.Level.INFO
            )
            val startTime = System.currentTimeMillis()

            val body = Rss.getContentAwait(article, effectiveRule, rssSource)
            // 协程取消检查（网络请求后）
            currentCoroutineContext().ensureActive()

            val costMs = System.currentTimeMillis() - startTime
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "RssFetch: end articleIndex=$articleIndex costMs=$costMs bodyLen=${body.length}",
                level = AppLog.Level.INFO
            )

            if (body.isBlank()) {
                throw IllegalStateException("body is blank")
            }

            val imageUrls = parseImageUrls(body, article.link ?: "", ruleImage, rssSource)
            // 协程取消检查（解析后）
            currentCoroutineContext().ensureActive()

            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "parseImageUrls: articleIndex=$articleIndex imageCount=${imageUrls.size}",
                level = AppLog.Level.INFO
            )

            if (imageUrls.isEmpty()) {
                throw IllegalStateException("parseImageUrls returned empty list")
            }

            // 转换为 ImageCanvasItem.ImageItem 列表
            val imageItems = imageUrls.mapIndexed { idx, url ->
                ImageCanvasItem.ImageItem(
                    url = url,
                    articleIndex = articleIndex,
                    imageIndex = idx
                )
            }

            // W5: 文章分隔符插入（首篇文章除外，isInitial=true 时由 Activity 直接展示无需分隔符）
            if (!isInitial) {
                val articleTitle = article.title.takeIf { it.isNotBlank() } ?: article.origin
                ImagePlay.appendItems(listOf(ImageCanvasItem.ArticleDivider(articleIndex, articleTitle)))
            }

            // 写入 ImagePlay 单例（StateFlow 封装，线程安全）
            ImagePlay.appendItems(imageItems)
            ImagePlay.loadedArticleIndices.add(articleIndex)

            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "loadArticleInternal: success articleIndex=$articleIndex loadedCount=${imageItems.size} totalImages=${ImagePlay.allImageUrls.value.size}",
                level = AppLog.Level.INFO
            )

            ImageCanvasAdapter.LoadState.SUCCESS
        }.onSuccess { state ->
            isLaunching = false
            _loadState.postValue(state)
        }.onError { e ->
            isLaunching = false
            // Coroutine 内部已守卫 CancellationException（重新抛出不触发 onError）
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "loadArticleInternal: failed articleIndex=$articleIndex e=${e::class.simpleName} msg=${e.message}",
                level = AppLog.Level.ERROR
            )
            _loadState.postValue(ImageCanvasAdapter.LoadState.ERROR(e))
        }
    }

    /**
     * 解析 body 为图片URL列表（复用 ImageGalleryViewModel 的多级兜底解析逻辑）
     *
     * 解析策略（4 级兜底）：
     * 1. body 不含 HTML 标签时：split("\n") 解析纯 URL 列表
     * 2. body 是 HTML 且 ruleImage 非空：用 AnalyzeRule + ruleImage 选择器提取
     * 3. body 含 <img> 标签：正则提取所有 src/data-src/data-original 等属性
     * 4. 最宽松兜底：提取所有 http/https URL
     *
     * @param body Rss.getContentAwait 返回的内容（URL列表 / HTML / JS结果）
     * @param baseUrl 用于相对URL转绝对URL（文章link）
     * @param ruleImage 图片选择器规则（CSS/XPath/JSONPath）
     * @param rssSource 订阅源（用于 AnalyzeRule 上下文 + header/cookie 复用）
     */
    private fun parseImageUrls(
        body: String,
        baseUrl: String,
        ruleImage: String?,
        rssSource: io.legado.app.data.entities.RssSource
    ): List<String> {
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_CANVAS,
            "parseImageUrls: bodyLen=${body.length} bodyHasHtml=${body.contains("<")} ruleImageLen=${ruleImage?.length ?: 0}",
            level = AppLog.Level.INFO
        )

        // 策略1：纯 URL 列表（body 不含 HTML 标签时 split("\n")）
        if (!body.contains("<")) {
            val urlList = body.split("\n")
                .map { it.trim() }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .map { NetworkUtils.getAbsoluteURL(baseUrl, it) }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
            if (urlList.isNotEmpty()) {
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_CANVAS,
                    "parse strategy 1 (newline split) success: count=${urlList.size}",
                    level = AppLog.Level.INFO
                )
                return urlList
            }
        }

        // 策略2：HTML + ruleImage 选择器
        if (body.contains("<") && !ruleImage.isNullOrBlank()) {
            try {
                val analyzeRule = AnalyzeRule(null, rssSource)
                analyzeRule.setContent(body)
                    .setBaseUrl(NetworkUtils.getAbsoluteURL(rssSource.sourceUrl, baseUrl))
                val imgUrls = (analyzeRule.getStringList(ruleImage) ?: emptyList())
                    .map { NetworkUtils.getAbsoluteURL(baseUrl, it.trim()) }
                    .filter { it.startsWith("http://") || it.startsWith("https://") }
                    .distinct()
                if (imgUrls.isNotEmpty()) {
                    AppLog.putDebugWithTag(
                        AppLog.TAG_IMAGE_CANVAS,
                        "parse strategy 2 (ruleImage selector) success: count=${imgUrls.size}",
                        level = AppLog.Level.INFO
                    )
                    return imgUrls
                }
            } catch (e: Exception) {
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_CANVAS,
                    "parse strategy 2 (ruleImage selector) failed: ${e::class.simpleName} ${e.message}",
                    level = AppLog.Level.ERROR
                )
            }
        }

        // 策略3：正则提取 <img> 标签的所有 src 属性
        if (body.contains("<img")) {
            val imgRegex = Regex(
                """<img[^>]+(?:src|data-src|data-original|data-lazy-src|data-lazy|realsrc|data-srcset|srcset)\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )
            val regexUrls = imgRegex.findAll(body)
                .map { matchResult ->
                    val url = matchResult.groupValues[1].trim()
                    val cleanUrl = if (url.contains(" ")) url.split(" ")[0] else url
                    NetworkUtils.getAbsoluteURL(baseUrl, cleanUrl)
                }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
                .toList()
            if (regexUrls.isNotEmpty()) {
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_CANVAS,
                    "parse strategy 3 (regex img tag) success: count=${regexUrls.size}",
                    level = AppLog.Level.INFO
                )
                return regexUrls
            }
        }

        // 策略4：提取 body 中所有 http/https URL（最宽松兜底）
        val allUrlRegex = Regex("""https?://[^\s"'<>\]\)]+""", RegexOption.IGNORE_CASE)
        val allUrls = allUrlRegex.findAll(body)
            .map { NetworkUtils.getAbsoluteURL(baseUrl, it.value.trim()) }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .toList()
        if (allUrls.isNotEmpty()) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_CANVAS,
                "parse strategy 4 (all url regex) success: count=${allUrls.size}",
                level = AppLog.Level.INFO
            )
            return allUrls
        }

        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_CANVAS,
            "parse all strategies failed, no urls found, bodyLen=${body.length}",
            level = AppLog.Level.ERROR
        )
        return emptyList()
    }

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
