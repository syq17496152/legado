package io.legado.app.ui.image

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.rss.Rss
import io.legado.app.utils.ACache
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeBytes
import com.bumptech.glide.request.RequestOptions
import java.util.Date

/**
 * 图片浏览 ViewModel
 *
 * 职责：
 * 1. 持有当前文章的图片URL列表（LiveData 供 Activity 观察）
 * 2. 持有加载状态（loading/error）
 * 3. 提供 loadArticleContent(article) 方法：调用 Rss.getContentAwait 获取 body 并解析为图片URL列表
 *
 * 多图URL解析规则（参考 design.md AD-03）：
 * - Rss.getContentAwait 返回 String，可能包含多图URL（换行分隔）
 * - 在 ViewModel 中 split("\n") 解析，Rss.getContentAwait 保持不变
 * - 每个 URL 用 NetworkUtils.getAbsoluteURL 转为绝对URL
 * - 过滤空URL和无效URL（非 http 开头）
 */
class ImageGalleryViewModel(application: Application) : BaseViewModel(application) {

    /** 当前文章的图片URL列表 */
    val imageUrlsLiveData = MutableLiveData<List<String>>()
    /** 加载状态（true=加载中） */
    val loadingLiveData = MutableLiveData(false)
    /** 错误信息（非空表示加载失败） */
    val errorLiveData = MutableLiveData<String?>()
    /** 当前文章索引（外层 ViewPager2 切换时更新） */
    val articleIndexLiveData = MutableLiveData(0)

    /**
     * 加载文章内容并解析为图片URL列表
     *
     * 解析策略（多级兜底）：
     * 1. ruleContent 非空：调用 Rss.getContentAwait 获取 body，body 可能是 URL 列表或 HTML
     * 2. ruleContent 为空：用 article.link 加载文章详情页 HTML
     * 3. body 解析：先尝试 split("\n") 解析纯 URL 列表；失败则用 ruleImage 选择器从 HTML 提取图片URL；再失败用正则提取 <img> 标签
     *
     * @param article 待加载的文章
     */
    fun loadArticleContent(article: RssArticle) {
        AppLog.put("[ImageGallery] loadArticleContent start: articleLinkLen=${article.link?.length ?: 0}, articleOriginLen=${article.origin?.length ?: 0}")
        val rssSource = ImagePlay.rssSource
        if (rssSource == null) {
            AppLog.put("[ImageGallery] rssSource is null, fallback to single image (article.link)")
            imageUrlsLiveData.postValue(listOfNotNull(article.link))
            return
        }
        val ruleContent = rssSource.ruleContent
        val ruleImage = rssSource.ruleImage
        AppLog.put("[ImageGallery] ruleContentLen=${ruleContent?.length ?: 0}, ruleImageLen=${ruleImage?.length ?: 0}")
        loadingLiveData.postValue(true)
        errorLiveData.postValue(null)
        execute {
            // ruleContent 为空时，用 body@html 获取文章详情页 HTML（CSS 选择器，返回 <body> 的 HTML 内容）
            val effectiveRule = if (ruleContent.isNullOrBlank()) {
                AppLog.put("[ImageGallery] ruleContent is blank, use body@html to load HTML")
                "body@html"
            } else {
                ruleContent
            }
            Rss.getContentAwait(article, effectiveRule, rssSource)
        }.onSuccess { body ->
            AppLog.put("[ImageGallery] getContentAwait success: bodyLen=${body.length}, bodyPrefix=${body.take(100)}")
            if (body.isBlank()) {
                AppLog.put("[ImageGallery] body is blank, fallback to single image (article.link)")
                imageUrlsLiveData.postValue(listOfNotNull(article.link))
            } else {
                val urls = parseImageUrls(body, article.link ?: "", ruleImage, rssSource)
                AppLog.put("[ImageGallery] parseImageUrls result: count=${urls.size}")
                if (urls.isEmpty()) {
                    AppLog.put("[ImageGallery] urls is empty, fallback to single image (article.link)")
                    imageUrlsLiveData.postValue(listOfNotNull(article.link))
                } else {
                    AppLog.put("[ImageGallery] imageUrlsLiveData postValue: count=${urls.size}, firstUrlLen=${urls.firstOrNull()?.length ?: 0}")
                    imageUrlsLiveData.postValue(urls)
                }
            }
            loadingLiveData.postValue(false)
        }.onError {
            AppLog.put("[ImageGallery] getContentAwait failed: ${it.javaClass.simpleName}: ${it.message}", it)
            // 兜底策略：ruleContent（JS规则）执行失败时，用 "body@html" 规则重新加载文章详情页 HTML
            AppLog.put("[ImageGallery] fallback: retry with body@html rule to load raw HTML")
            execute {
                Rss.getContentAwait(article, "body@html", rssSource)
            }.onSuccess { htmlBody ->
                AppLog.put("[ImageGallery] fallback getContentAwait(body@html) success: bodyLen=${htmlBody.length}")
                if (htmlBody.isBlank()) {
                    AppLog.put("[ImageGallery] fallback body is blank, fallback to single image (article.link)")
                    imageUrlsLiveData.postValue(listOfNotNull(article.link))
                } else {
                    val urls = parseImageUrls(htmlBody, article.link ?: "", ruleImage, rssSource)
                    AppLog.put("[ImageGallery] fallback parseImageUrls result: count=${urls.size}")
                    if (urls.isEmpty()) {
                        AppLog.put("[ImageGallery] fallback urls is empty, fallback to single image (article.link)")
                        imageUrlsLiveData.postValue(listOfNotNull(article.link))
                    } else {
                        AppLog.put("[ImageGallery] fallback imageUrlsLiveData postValue: count=${urls.size}")
                        imageUrlsLiveData.postValue(urls)
                    }
                }
                loadingLiveData.postValue(false)
            }.onError { fallbackErr ->
                AppLog.put("[ImageGallery] fallback getContentAwait(body@html) also failed: ${fallbackErr.javaClass.simpleName}: ${fallbackErr.message}", fallbackErr)
                // 最终兜底：用 article.link 作为单图URL
                AppLog.put("[ImageGallery] final fallback: use article.link as single image")
                imageUrlsLiveData.postValue(listOfNotNull(article.link))
                loadingLiveData.postValue(false)
            }
        }
    }

    /**
     * 解析 body 为图片URL列表（多级兜底解析，参考视频播放器成熟机制）
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
        rssSource: RssSource
    ): List<String> {
        AppLog.put("[ImageGallery] parseImageUrls start: bodyLen=${body.length}, bodyHasHtml=${body.contains("<")}, ruleImageLen=${ruleImage?.length ?: 0}, baseUrlLen=${baseUrl.length}")

        // 策略1：尝试 split("\n") 解析纯 URL 列表（ruleContent 返回换行分隔的URL）
        // 仅当 body 不含 HTML 标签时执行（避免把 HTML 按行分割收集到非图片 URL）
        // 修复：放宽过滤条件，不强制要求图片扩展名（CDN URL 可能无扩展名），只过滤 http/https 开头的URL
        if (!body.contains("<")) {
            val urlList = body.split("\n")
                .map { it.trim() }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .map { NetworkUtils.getAbsoluteURL(baseUrl, it) }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
            if (urlList.isNotEmpty()) {
                AppLog.put("[ImageGallery] parse strategy 1 (newline split) success: count=${urlList.size}, firstUrlLen=${urlList.firstOrNull()?.length ?: 0}")
                return urlList
            }
            AppLog.put("[ImageGallery] parse strategy 1 (newline split) no valid url found")
        }

        // 策略2：body 是 HTML，用 AnalyzeRule + ruleImage 选择器提取图片URL
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
                    AppLog.put("[ImageGallery] parse strategy 2 (ruleImage selector) success: count=${imgUrls.size}, firstUrlLen=${imgUrls.firstOrNull()?.length ?: 0}")
                    return imgUrls
                }
                AppLog.put("[ImageGallery] parse strategy 2 (ruleImage selector) no url found")
            } catch (e: Exception) {
                AppLog.put("[ImageGallery] parse strategy 2 (ruleImage selector) failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // 策略3：正则提取 <img> 标签的所有 src 属性（增强版，覆盖更多属性）
        if (body.contains("<img")) {
            val imgRegex = Regex(
                """<img[^>]+(?:src|data-src|data-original|data-lazy-src|data-lazy|realsrc|data-srcset|srcset)\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )
            val regexUrls = imgRegex.findAll(body)
                .map { matchResult ->
                    val url = matchResult.groupValues[1].trim()
                    // srcset 可能是 "url1 1x, url2 2x" 格式，取第一个
                    val cleanUrl = if (url.contains(" ")) url.split(" ")[0] else url
                    NetworkUtils.getAbsoluteURL(baseUrl, cleanUrl)
                }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
                .toList()
            if (regexUrls.isNotEmpty()) {
                AppLog.put("[ImageGallery] parse strategy 3 (regex img tag) success: count=${regexUrls.size}, firstUrlLen=${regexUrls.firstOrNull()?.length ?: 0}")
                return regexUrls
            }
            AppLog.put("[ImageGallery] parse strategy 3 (regex img tag) no url found")
        }

        // 策略4：提取 body 中所有 http/https URL（最宽松兜底，避免完全无图可显）
        val allUrlRegex = Regex("""https?://[^\s"'<>\]\)]+""", RegexOption.IGNORE_CASE)
        val allUrls = allUrlRegex.findAll(body)
            .map { NetworkUtils.getAbsoluteURL(baseUrl, it.value.trim()) }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .toList()
        if (allUrls.isNotEmpty()) {
            AppLog.put("[ImageGallery] parse strategy 4 (all url regex) success: count=${allUrls.size}, firstUrlLen=${allUrls.firstOrNull()?.length ?: 0}")
            return allUrls
        }

        AppLog.put("[ImageGallery] parse all strategies failed, no urls found, bodyLen=${body.length}")
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
     * 预加载下一篇文章的图片URL列表（跨文章预加载，修复问题2）
     *
     * 用户反馈："上下滑动切换阅读内容时，明显感觉到下一个图片内容无法加载"
     * 原因：当前仅预加载当前文章内的下一张图片，未预加载下一篇文章的第一张图片
     * 修复：当前文章加载完成后，主动预加载下一篇文章，用 Glide preload() 缓存第一张图片
     *
     * 流程：
     * 1. 调用 Rss.getContentAwait 获取下一篇文章 body
     * 2. parseImageUrls 解析为图片URL列表
     * 3. 用 Glide preload() 预加载第一张图片到磁盘缓存
     * 4. 用户上下滑动切换到下一篇文章时，图片能秒开
     *
     * 注意：不更新 LiveData（避免触发 UI 刷新），仅做磁盘缓存预加载
     *
     * @param nextArticle 下一篇文章
     * @param sourceOrigin 订阅源URL（用于 Referer/Cookie 注入）
     * @param referer 文章页URL（用于 Referer 注入）
     */
    fun preloadNextArticle(nextArticle: RssArticle, sourceOrigin: String?, referer: String?) {
        val rssSource = ImagePlay.rssSource ?: return
        val ruleContent = rssSource.ruleContent
        val ruleImage = rssSource.ruleImage
        AppLog.put("[ImageGallery] preloadNextArticle start: articleLinkLen=${nextArticle.link?.length ?: 0}")
        execute {
            val effectiveRule = if (ruleContent.isNullOrBlank()) "body@html" else ruleContent
            Rss.getContentAwait(nextArticle, effectiveRule, rssSource)
        }.onSuccess { body ->
            if (body.isBlank()) {
                AppLog.put("[ImageGallery] preloadNextArticle body is blank, skip")
                return@onSuccess
            }
            val urls = parseImageUrls(body, nextArticle.link ?: "", ruleImage, rssSource)
            AppLog.put("[ImageGallery] preloadNextArticle parseImageUrls: count=${urls.size}")
            if (urls.isEmpty()) return@onSuccess
            // 预加载第一张图片到磁盘缓存（用户滑动到下一篇文章时能秒开）
            val firstUrl = urls[0]
            AppLog.put("[ImageGallery] preloadNextArticle preload first image: urlLen=${firstUrl.length}")
            ImageLoader.load(context, firstUrl).apply {
                sourceOrigin?.let { origin ->
                    apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, origin))
                }
                referer?.let { ref ->
                    apply(RequestOptions().set(OkHttpModelLoader.refererOption, ref))
                }
            }.diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .preload()
        }.onError {
            AppLog.put("[ImageGallery] preloadNextArticle failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }
}
