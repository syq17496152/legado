package io.legado.app.model

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.content.edit
import com.shuyu.gsyvideoplayer.listener.GSYMediaPlayerListener
import com.shuyu.gsyvideoplayer.utils.CommonUtil
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import com.shuyu.gsyvideoplayer.video.base.GSYBaseVideoPlayer
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.SourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssEpisode
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.entities.RssRoute
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.exception.ContentEmptyException
import io.legado.app.help.CacheManager
import io.legado.app.help.book.getDanmaku
import io.legado.app.help.book.update
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.gsyVideo.ExoVideoManager
import io.legado.app.help.gsyVideo.ExoVideoManager.Companion.FULLSCREEN_ID
import io.legado.app.help.gsyVideo.FloatingPlayer
import io.legado.app.help.gsyVideo.VideoPlayer
import io.legado.app.help.video.VideoUrlExtractor
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.rss.Rss
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.externalCache
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import org.json.JSONArray
import java.io.File

object VideoPlay : CoroutineScope by MainScope(){
    private const val VIDEO_POS_NAME = "video_pos_" //单链接播放进度
    private const val VIDEO_POS_SAVE_TIME = 60 * 60 * 24 * 20 //20天
    private var needClearTemp = true //需要清理缓存
    private const val VIDEO_TEMP_PATH = "video_temp"
    private val videoTempFile by lazy { File(FileUtils.getCachePath(), VIDEO_TEMP_PATH) }

    const val VIDEO_PREF_NAME = "video_config"

    private val videoPrefs: SharedPreferences by lazy { appCtx.getSharedPreferences(VIDEO_PREF_NAME, MODE_PRIVATE) }
    /**  是否自动播放  **/
    var autoPlay
        get() = videoPrefs.getBoolean("autoPlay", true)
        set(value) {
            videoPrefs.edit { putBoolean("autoPlay", value) }
        }
    /**  直接全屏，需先启用自动播放  **/
    var startFull
        get() = videoPrefs.getBoolean("startFull", false)
        set(value) {
            videoPrefs.edit { putBoolean("startFull", value) }
        }
    /**  长按倍速  **/
    var longPressSpeed
        get() = videoPrefs.getInt("longPressSpeed", 30)
        set(value) {
            videoPrefs.edit { putInt("longPressSpeed", value) }
        }
    /**  全屏底部进度条  **/
    var fullBottomProgressBar
        get() = videoPrefs.getBoolean("fullBottomProgressBar", true)
        set(value) {
            videoPrefs.edit { putBoolean("fullBottomProgressBar", value) }
        }
    /**  边下边播缓存（已废弃，保留字段仅为兼容旧配置数据）
     * P0-2 统一缓存机制：ExoPlayer SimpleCache 已默认接管所有视频缓存（见 ExoPlayerHelper.cacheDataSourceFactory），
     * 旧版 GSY ProxyCacheManager 代理缓存路径对带 header/m3u8/特殊 URL 不兼容会导致播放失败，故永久关闭。
     * getter 始终返回 false，setter 保留仅为避免旧 UI 调用崩溃。
     */
    @Deprecated("ExoPlayer SimpleCache 已默认接管缓存，此开关不再生效")
    var cachePlay
        get() = false
        set(value) {
            videoPrefs.edit { putBoolean("cachePlay", value) }
        }
    /**  视频缓存容量（MB），默认 100MB，可选 50/100/200/500
     * P0-3 缓存容量可配置：修改后需重启 App 生效（SimpleCache 单例在首次访问时初始化，不可动态修改大小）
     **/
    var videoCacheSize: Int
        get() = videoPrefs.getInt("videoCacheSize", 100)
        set(value) {
            videoPrefs.edit { putInt("videoCacheSize", value) }
        }
    /**  默认静音（播放时默认关闭声音，用户可手动开启）  **/
    var muteOnStart
        get() = videoPrefs.getBoolean("muteOnStart", true)
        set(value) {
            videoPrefs.edit { putBoolean("muteOnStart", value) }
        }
    /**  快进/快退时间（秒），默认 60 秒，右侧功能区快进快退按钮使用  **/
    var videoSkipTime: Int
        get() = videoPrefs.getInt("videoSkipTime", 60)
        set(value) {
            videoPrefs.edit { putInt("videoSkipTime", value) }
        }
    /**  弹幕滚动速度  **/
    var danmakuSpeed = 1.2f
    /**  锁屏  **/
    var lockCurScreen = false
    /**  竖屏视频  **/
    var isPortraitVideo = false

    val videoManager by lazy { ExoVideoManager() }
    private var isLoading = false
    private val loadScope = CoroutineScope(SupervisorJob() + IO)
    var videoUrl: String? = null //播放链接
    var singleUrl = false
    var videoTitle: String? = null
    /** P0: 当前播放使用的 Headers（供 WebView 降级复用，避免重新构造 AnalyzeUrl） */
    var currentPlayHeaders: Map<String, String>? = null
    /** P0: 播放器类型（0=AUTO 自动选择, 1=EXO_PLAYER 强制内置播放器, 2=WEB_VIEW 强制 WebView） */
    var playerType: Int
        get() = videoPrefs.getInt("playerType", 0)
        set(value) {
            videoPrefs.edit { putInt("playerType", value) }
        }
    var inBookshelf = true
    var isResumeFromFloat = false  // P0-1: 从悬浮窗恢复标志，Fragment.activatePlayer 据此决定 clonePlayState 还是 startPlay
    var source: BaseSource? = null
    var book: Book? = null
    var toc: List<BookChapter>? =  null
    var chapter: BookChapter? = null
    var volumes = arrayListOf<BookChapter>()
    var episodes: List<BookChapter>? =  null
    /**  在当前episodes中的位置  **/
    var chapterInVolumeIndex = 0
    /**  卷章节 -> 线路或者季数  **/
    var durVolumeIndex = 0
    /**  当前卷  **/
    var durVolume: BookChapter? = null
    /**  本集的进度  **/
    var durChapterPos = 0
    /**  订阅收藏  **/
    var rssStar: RssStar? = null
    /**  订阅历史记录,收藏优先  **/
    var rssRecord: RssReadRecord? = null
    /**  订阅源多集列表（R1 多集选择播放，ruleContent 返回 JSON 数组或多行 URL 时解析）  **/
    var rssEpisodes: List<RssEpisode>? = null
    /**  当前订阅源集索引（R1 多集选择播放）  **/
    var rssEpisodeIndex: Int = 0
    /**  订阅源多线路列表（R3 多线路支持，ruleContent 返回嵌套 JSON 时解析）  **/
    var rssRoutes: List<RssRoute>? = null
    /**  当前线路索引（R3 多线路支持）  **/
    var rssRouteIndex: Int = 0
    /**  订阅源文章列表（上下滑动切换文章，从 RssArticlesFragment 传入）  **/
    var rssArticles: List<RssArticle>? = null
    /**  当前订阅源文章索引（上下滑动切换文章）  **/
    var rssArticleIndex: Int = 0

    // ==================== 阶段8：分页加载 + 预缓冲 + 位置记忆 ====================

    /** 分页加载：分类名称（从 RssArticlesViewModel 传入） **/
    var rssSortName: String? = null
    /** 分页加载：分类URL（从 RssArticlesViewModel 传入） **/
    var rssSortUrl: String? = null
    /** 分页加载：下一页URL（Rss.getArticles 返回） **/
    var rssNextPageUrl: String? = null
    /** 分页加载：当前页码 **/
    var rssArticlePage: Int = 1
    /** 分页加载：是否还有更多文章 **/
    var rssArticlesHasMore: Boolean = true
    /** 分页加载：防重复加载标记 **/
    var isLoadingMoreArticles: Boolean = false
    /** 预缓冲：文章页面HTML缓存（key=article.link, value=page HTML），startPlay R5分支优先使用 **/
    val preloadedHtmls: MutableMap<String, String> = mutableMapOf()
    /** 预缓冲：已预加载的文章link集合（避免重复预加载） **/
    val preloadedArticles: MutableSet<String> = mutableSetOf()
    /** 位置记忆：退出播放器时正在看的文章link **/
    var lastPlayedArticleLink: String? = null
    /**  弹幕相关  **/
    var danmakuFile: File? = null
    var danmakuStr: String? = null
    var danmakuShow = true

    /**
     * 开始播放
     */
    fun startPlay(player: StandardGSYVideoPlayer) {
        if (source == null) return
        danmakuStr = null
        danmakuFile = null
        val player = player.getCurrentPlayer()
        if (singleUrl) {
            val mUrl = videoUrl ?: return
            Coroutine.async(loadScope, IO) {
                CacheManager.getLong(VIDEO_POS_NAME + mUrl)?.let {
                    player.seekOnStart = it
                }
                inBookshelf = true
                val analyzeUrl = AnalyzeUrl(
                    mUrl,
                    source = source,
                    ruleData = book,
                    chapter = null
                )
                withContext(Main) {
                    player.mapHeadData = analyzeUrl.headerMap
                    currentPlayHeaders = analyzeUrl.headerMap
                    // Bug8 修复：统一解析播放器页面 URL，避免 3003 错误
                    val url = VideoUrlExtractor.resolvePlayerPageUrl(analyzeUrl.url)
                    player.setUp(url, cachePlay, File(appCtx.externalCache, "exoplayer"), videoTitle)
                    if (autoPlay) {
                        player.startPlayLogic()
                    }
                }
            }.onError {
                AppLog.put("加载视频链接失败", it, true)
            }
            return
        }
        durChapterPos.takeIf { it > 0 }?.toLong()?.let { player.seekOnStart = it }
        (source as? RssSource)?.let { s ->
            val rssArticle = rssStar?.toRssArticle() ?: rssRecord?.toRssArticle() ?: rssArticles?.getOrNull(rssArticleIndex)
            if (rssArticle == null) {
                appCtx.toastOnUi("未找到订阅")
                return
            }
            val ruleContent = s.ruleContent
            if (ruleContent.isNullOrBlank()) {
                // R5 自动视频链接抓取 + R3 title 修复
                videoTitle = rssArticle.title
                postEvent(EventBus.VIDEO_SUB_TITLE, "正在抓取视频链接...")
                Coroutine.async(loadScope, IO) {
                    // 阶段8 F10：优先使用预缓冲的 HTML 缓存，跳过网络请求
                    val cachedHtml = preloadedHtmls[rssArticle.link]
                    val html = if (cachedHtml != null) {
                        cachedHtml
                    } else {
                        // 获取文章页面 HTML
                        val pageAnalyzeUrl = AnalyzeUrl(rssArticle.link, source = source, ruleData = rssArticle)
                        val res = pageAnalyzeUrl.getStrResponseAwait()
                        res.body ?: ""
                    }
                    // R5 综合提取视频 URL（五种方法去重）
                    val videoUrls = VideoUrlExtractor.extract(html, rssArticle.link)
                    when {
                        videoUrls.size == 1 -> {
                            // R5 单 URL 分支
                            val mUrl = videoUrls[0]
                            videoUrl = mUrl
                            val playAnalyzeUrl = AnalyzeUrl(mUrl, source = source, ruleData = rssArticle)
                            // R5 Header 修复：注入 Referer（模拟 WebView 行为，解决 CDN 防盗链 404）
                            if (!playAnalyzeUrl.headerMap.any { it.key.equals("Referer", ignoreCase = true) }) {
                                playAnalyzeUrl.headerMap["Referer"] = rssArticle.link
                            }
                            withContext(Main) {
                                player.mapHeadData = playAnalyzeUrl.headerMap
                                currentPlayHeaders = playAnalyzeUrl.headerMap
                                // Bug8 修复：统一解析播放器页面 URL
                                val resolvedUrl = VideoUrlExtractor.resolvePlayerPageUrl(playAnalyzeUrl.url)
                                player.setUp(resolvedUrl, cachePlay, File(appCtx.externalCache, "exoplayer"), rssArticle.title)
                                postEvent(EventBus.VIDEO_SUB_TITLE, rssArticle.title)
                                if (autoPlay) {
                                    player.startPlayLogic()
                                }
                            }
                        }
                        videoUrls.size > 1 -> {
                            // R5 多 URL 分支：构建 RssRoute（包装为单线路，保持数据层一致）
                            val episodes = videoUrls.mapIndexed { i, url ->
                                RssEpisode(title = "第${i + 1}集", url = url)
                            }
                            val route = RssRoute(name = "线路1", episodes = episodes)
                            rssRoutes = listOf(route)
                            rssRouteIndex = 0
                            rssEpisodes = episodes
                            rssEpisodeIndex = 0
                            withContext(Main) {
                                postEvent(EventBus.VIDEO_SUB_TITLE, rssArticle.title)
                                playRssEpisode(player, episodes[0])
                                postEvent(EventBus.UP_VIDEO_INFO, arrayListOf(1))
                            }
                        }
                        else -> {
                            // R5 第二层降级：网络抓包拦截（BackstageWebView shouldInterceptRequest + JS hook）
                            // 静态 HTML 解析未命中时，启动 WebView 加载页面，拦截 fetch/XHR/MediaSource 等动态请求
                            // 适用场景：JS 动态构造视频 URL、播放器运行时请求 m3u8、CDN 鉴权 URL 等
                            AppLog.putInfo("R5静态解析未命中, 启动网络抓包拦截, ${VideoUrlExtractor.sanitizeUrl(rssArticle.link)}")
                            val webViewUrl = VideoUrlExtractor.extractWithWebView(
                                url = rssArticle.link,
                                source = source,
                                delayTime = 3000L,
                                timeout = 15000L
                            )
                            if (webViewUrl != null) {
                                // R5 网络抓包命中：走单 URL 播放流程（复用单 URL 分支模式）
                                AppLog.putInfo("R5网络抓包命中, ${VideoUrlExtractor.sanitizeUrl(webViewUrl)}")
                                videoUrl = webViewUrl
                                val playAnalyzeUrl = AnalyzeUrl(webViewUrl, source = source, ruleData = rssArticle)
                                // R5 Header 修复：视频 URL 来源页面为文章链接，Referer 防盗链必需
                                if (!playAnalyzeUrl.headerMap.any { it.key.equals("Referer", ignoreCase = true) }) {
                                    playAnalyzeUrl.headerMap["Referer"] = rssArticle.link
                                }
                                withContext(Main) {
                                    player.mapHeadData = playAnalyzeUrl.headerMap
                                    currentPlayHeaders = playAnalyzeUrl.headerMap
                                    // Bug8 修复：统一解析播放器页面 URL
                                    val resolvedUrl = VideoUrlExtractor.resolvePlayerPageUrl(playAnalyzeUrl.url)
                                    player.setUp(resolvedUrl, cachePlay, File(appCtx.externalCache, "exoplayer"), rssArticle.title)
                                    postEvent(EventBus.VIDEO_SUB_TITLE, rssArticle.title)
                                    if (autoPlay) {
                                        player.startPlayLogic()
                                    }
                                }
                            } else {
                                // R5 第三层降级：网络抓包未命中，回退文章链接交给 ExoPlayer
                                AppLog.putWarn("R5网络抓包未命中, 回退文章链接, ${VideoUrlExtractor.sanitizeUrl(rssArticle.link)}")
                                val mUrl = rssArticle.link
                                videoUrl = mUrl
                                val fallbackUrl = AnalyzeUrl(mUrl, source = source, ruleData = rssArticle)
                                // R5 Header 修复：注入 Referer
                                if (!fallbackUrl.headerMap.any { it.key.equals("Referer", ignoreCase = true) }) {
                                    fallbackUrl.headerMap["Referer"] = rssArticle.link
                                }
                                withContext(Main) {
                                    player.mapHeadData = fallbackUrl.headerMap
                                    currentPlayHeaders = fallbackUrl.headerMap
                                    // Bug8 修复：统一解析播放器页面 URL
                                    val resolvedUrl = VideoUrlExtractor.resolvePlayerPageUrl(fallbackUrl.url)
                                    player.setUp(resolvedUrl, cachePlay, File(appCtx.externalCache, "exoplayer"), rssArticle.title)
                                    postEvent(EventBus.VIDEO_SUB_TITLE, rssArticle.title)
                                    if (autoPlay) {
                                        player.startPlayLogic()
                                    }
                                }
                            }
                        }
                    }
                }.onError {
                    AppLog.put("R5自动抓取视频链接失败", it, true)
                }
            } else {
                Rss.getContent(loadScope, rssArticle, ruleContent, s)
                    .onSuccess(IO) { content ->
                        val content = content.trim()
                        // R3 多线路支持：优先解析为多线路列表，兼容旧版扁平JSON/多行URL
                        val routes = parseRssRoutes(content, rssArticle.link)
                        if (routes != null && routes.isNotEmpty()) {
                            rssRoutes = routes
                            rssRouteIndex = 0
                            rssEpisodes = routes[0].episodes
                            rssEpisodeIndex = 0
                            postEvent(EventBus.VIDEO_SUB_TITLE, rssArticle.title) // R3 title 修复
                            playRssEpisode(player, routes[0].episodes[0])
                            postEvent(EventBus.UP_VIDEO_INFO, arrayListOf(1)) //通知 UI 更新多集列表
                            return@onSuccess
                        }
                        // 单 URL（现有逻辑）
                        val mUrl = if (content.isEmpty()) {
                            throw ContentEmptyException("正文为空")
                        } else if (content.contains("<MPD", ignoreCase = true)) { //当作mpd文本（P1-A修复：精确判断DASH清单，避免HTML/XML误判）
                            val name = MD5Utils.md5Encode(content) + ".mpd"
                            val file = FileUtils.createFileIfNotExist(videoTempFile,name)
                            file.writeText(content)
                            Uri.fromFile(file).toString()
                        } else {
                            NetworkUtils.getAbsoluteURL(rssArticle.link, content)
                        }
                        videoUrl = mUrl
                        val analyzeUrl = AnalyzeUrl(
                            mUrl,
                            source = source,
                            ruleData = rssArticle
                        )
                        // R5 Header 修复：注入 Referer（模拟 WebView 行为，解决 CDN 防盗链 404）
                        if (!analyzeUrl.headerMap.any { it.key.equals("Referer", ignoreCase = true) }) {
                            analyzeUrl.headerMap["Referer"] = rssArticle.link
                        }
                        val playUrl = analyzeUrl.url
                        withContext(Main) {
                            player.mapHeadData = analyzeUrl.headerMap
                            currentPlayHeaders = analyzeUrl.headerMap
                            // Bug8 修复：统一解析播放器页面 URL
                            val resolvedUrl = VideoUrlExtractor.resolvePlayerPageUrl(playUrl)
                            player.setUp(resolvedUrl, cachePlay, File(appCtx.externalCache, "exoplayer"), rssArticle.title)
                            postEvent(EventBus.VIDEO_SUB_TITLE, rssArticle.title) // R3 title 修复
                            if (autoPlay) {
                                player.startPlayLogic()
                            }
                        }
                    }.onError {
                        AppLog.put("加载订阅源为链接的正文失败", it, true)
                    }
            }
            return
        }
        val book = book
        if (book == null) {
            appCtx.toastOnUi("未找到书籍")
            return
        }
        chapter = if (episodes.isNullOrEmpty()) {
            //没有卷目录，那么卷就是播放的章节（适合电影类，没有剧集，全是线路卷章节，如果全是章节没有卷的写法，播放完后会继续下一个线路重复播放）
            val durVolume = durVolume
            when {
                durVolume == null -> null
                durVolume.url.startsWith(durVolume.title) -> null //卷章节没获取到链接（链接以标题开头）则返回null
                else -> durVolume
            }
        } else {
            // 优先获取当前索引的剧集，如果不存在则尝试获取第一个剧集
            episodes?.getOrNull(chapterInVolumeIndex) ?: run {
                chapterInVolumeIndex = 0
                episodes?.getOrNull(chapterInVolumeIndex)
            }
        }
        val chapter = chapter
        if (chapter == null) {
            appCtx.toastOnUi("未找到章节")
            return
        }
        WebBook.getContent(loadScope, source as BookSource, book, chapter)
            .onSuccess(IO) { content ->
                val content = content.trim()
                val mUrl = if (content.isEmpty()) {
                    throw ContentEmptyException("正文为空")
                } else if (content.startsWith("<")) { //当作mpd文本
                    val name = MD5Utils.md5Encode(content) + ".mpd"
                    val file = FileUtils.createFileIfNotExist(videoTempFile,name)
                    file.writeText(content)
                    Uri.fromFile(file).toString()
                } else {
                    content
                }
                videoUrl = mUrl
                val analyzeUrl = AnalyzeUrl(
                    mUrl,
                    source = source,
                    ruleData = book,
                    chapter = chapter
                )
                when (val danmaku = chapter.getDanmaku()) {
                    is String -> danmakuStr = danmaku
                    is File -> danmakuFile = danmaku
                }
                val playUrl = analyzeUrl.url
                withContext(Main) {
                    player.mapHeadData = analyzeUrl.headerMap
                    currentPlayHeaders = analyzeUrl.headerMap
                    // Bug8 修复：统一解析播放器页面 URL
                    val resolvedUrl = VideoUrlExtractor.resolvePlayerPageUrl(playUrl)
                    player.setUp(resolvedUrl, cachePlay, File(appCtx.externalCache, "exoplayer"), chapter.title)
                    if (autoPlay) {
                        player.startPlayLogic()
                    }
                }
            }.onError {
                AppLog.put("获取资源链接出错\n$it", it, true)
            }
        isLoading = false
    }

    /**
     * 退出全屏，主要用于返回键
     *
     * @return 返回是否全屏
     */
    fun backFromWindowFull(context: Context?): Boolean {
        var backFrom = false
        val vp =
            (CommonUtil.scanForActivity(context)).findViewById<View?>(Window.ID_ANDROID_CONTENT) as ViewGroup
        val oldF = vp.findViewById<View?>(FULLSCREEN_ID)
        if (oldF != null) {
            backFrom = true
            CommonUtil.hideNavKey(context)
            if (videoManager.lastListener() != null) {
                videoManager.lastListener().onBackFullscreen()
            }
        }
        return backFrom
    }
    /**
     * 页面销毁了记得调用是否所有的video
     */
    fun releaseAllVideos() {
        if (videoManager.listener() != null) {
            videoManager.listener().onCompletion()
        }
        videoManager.releaseMediaPlayer()
        if (!isLoading) {
            //还原所有状态
            videoUrl = null
            singleUrl = false
            videoTitle = null
            source = null
            book = null
            toc = null
            chapter = null
            volumes.clear()
            episodes = null
            chapterInVolumeIndex = 0
            durVolumeIndex = 0
            durVolume = null
            durChapterPos = 0
            inBookshelf = true
            rssStar = null
            rssRecord = null
            danmakuStr = null
            danmakuFile = null
            lockCurScreen = false
            isPortraitVideo = false
            rssEpisodes = null
            rssEpisodeIndex = 0
            rssRoutes = null
            rssRouteIndex = 0
            release()
            if (needClearTemp) {
                needClearTemp = false
                FileUtils.delete(videoTempFile)
            }
        }
    }
    /**
     * 暂停播放
     */
    fun onPause() {
        if (videoManager.listener() != null) {
            videoManager.listener().onVideoPause()
        }
    }

    /**
     * 恢复播放
     */
    fun onResume() {
        if (videoManager.listener() != null) {
            videoManager.listener().onVideoResume()
        }
    }


    /**
     * 恢复暂停状态
     * @param seek 是否产生seek动作,直播设置为false
     */
    fun onResume(seek: Boolean) {
        if (videoManager.listener() != null) {
            videoManager.listener().onVideoResume(seek)
        }
    }

    //播放器移植 - 辅助函数
    @SuppressLint("StaticFieldLeak")
    private var sSwitchVideo: StandardGSYVideoPlayer? = null
    private var sMediaPlayerListener: GSYMediaPlayerListener? = null
    fun savePlayState(switchVideo: StandardGSYVideoPlayer) {
        when (switchVideo) {
            is VideoPlayer -> sSwitchVideo = switchVideo.saveState()
            is FloatingPlayer -> sSwitchVideo = switchVideo.saveState()
        }
        sMediaPlayerListener = switchVideo
    }
    fun clonePlayState(switchVideo: StandardGSYVideoPlayer) {
        when (switchVideo) {
            is VideoPlayer -> sSwitchVideo?.let { switchVideo.cloneState(it) }
            is FloatingPlayer -> sSwitchVideo?.let { switchVideo.cloneState(it) }
        }
    }

    fun release() {
        sMediaPlayerListener?.onAutoCompletion()
        sMediaPlayerListener = null
        sSwitchVideo = null
    }

    fun stopLoading() {
        loadScope.coroutineContext.cancelChildren()
    }

    suspend fun initSource(sourceKey: String?, sourceType: Int?, bookUrl: String?, record:String?): Boolean = withContext(IO) {
        isLoading = true
        source = sourceKey?.let {
            when (sourceType) {
                SourceType.book -> appDb.bookSourceDao.getBookSource(it)
                SourceType.rss -> appDb.rssSourceDao.getByKey(it)
                else -> null
            }
        }
        book = bookUrl?.let {
            toc = appDb.bookChapterDao.getChapterList(it)
            volumes.clear()
            toc?.forEach { t ->
                if (t.isVolume) {
                    volumes.add(t)
                }
            }
            appDb.bookDao.getBook(it) ?: appDb.searchBookDao.getSearchBook(it)?.toBook()
        }?.also { b ->
            chapterInVolumeIndex = b.chapterInVolumeIndex
            durVolumeIndex = b.durVolumeIndex
            durChapterPos = b.durChapterPos
            source = appDb.bookSourceDao.getBookSource(b.origin)
            withContext(Main) {
                SourceCallBack.callBackBook(SourceCallBack.START_READ, source as BookSource?, b, chapter)
            }
        }
        upEpisodes()
        if (source == null) {
            withContext(Main) {
                appCtx.toastOnUi("未找到源")
            }
            return@withContext false
        }
        record?.let{ //订阅源
            val sourceKey = sourceKey ?: return@let
            rssStar =appDb.rssStarDao.get(sourceKey, it)?.also{ r ->
                durChapterPos = r.durPos
            }
            if (rssStar == null) {
                rssRecord = appDb.rssReadRecordDao.getRecord(it,sourceKey)?.also{ r ->
                    durChapterPos = r.durPos
                }
            }
        }
        return@withContext true
    }

    fun upEpisodes() {
        val volumes = volumes
        if (volumes.isEmpty()) {
            durVolume = null
            episodes = toc
            return
        }
        val toc = toc ?: return
        durVolume = volumes.getOrNull(durVolumeIndex)
        if (durVolume == null) {
            durVolumeIndex = 0
            durVolume = volumes.getOrNull(durVolumeIndex)
        }
        val startInt = durVolume?.index ?: 0
        val endInt = volumes.getOrNull(durVolumeIndex + 1)?.index ?: toc.size
        episodes = toc.subList(startInt + 1, endInt)
    }

    fun upDurIndex(offset: Int, player: StandardGSYVideoPlayer): Boolean {
        val episodes = episodes ?: return false
        val index = chapterInVolumeIndex + offset
        if (index < 0) {
            appCtx.toastOnUi("已到开头")
            return false
        }
        if (index >= episodes.size) {
            appCtx.toastOnUi("已播放完")
            return false
        }
        chapterInVolumeIndex = index
        saveRead(0)
        startPlay(player)
        postEvent(EventBus.UP_VIDEO_INFO, arrayListOf(1)) //更新选集视图
        return true
    }

    /**
     * R1 多集选择播放：解析 ruleContent 返回的内容为多集列表
     *
     * 支持三种模式（兼容性保证：现有单 URL 订阅源无需修改，自动走模式①）：
     * - 模式①单 URL：返回 null，交由现有逻辑处理（100% 向后兼容）
     * - 模式②多行 URL：每行合法 URL（http/https/绝对路径）才判定多集
     * - 模式③JSON 数组：[{"url":"...","title":"..."}]，url 必须，title 可选（缺省"第N集"）
     *
     * 详见 docs/specs/rss-video-player-enhancement/design.md 1.5 节"内容规则编写指南"
     */
    private fun parseRssEpisodes(content: String, baseUrl: String): List<RssEpisode>? {
        val trimmed = content.trim()
        // 模式③：JSON 数组（完整多集，支持 title 等可选字段）
        if (trimmed.startsWith("[")) {
            return try {
                val arr = JSONArray(trimmed)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    RssEpisode(
                        title = obj.optString("title", "第${i + 1}集"),
                        url = NetworkUtils.getAbsoluteURL(baseUrl, obj.optString("url"))
                    )
                }.filter { it.url.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }
        // 模式②：多行 URL（简写多集，每行必须是合法 URL）
        val lines = trimmed.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size > 1 && lines.all { isLikelyUrl(it) }) {
            return lines.mapIndexed { i, url ->
                RssEpisode(title = "第${i + 1}集", url = NetworkUtils.getAbsoluteURL(baseUrl, url))
            }
        }
        // 模式①：单 URL，交由现有逻辑处理
        return null
    }

    private fun isLikelyUrl(s: String): Boolean {
        return s.startsWith("http://") || s.startsWith("https://") || s.startsWith("/")
    }

    /**
     * R3 多线路支持：解析 ruleContent 返回的内容为多线路列表
     *
     * 支持三种格式（兼容性保证：现有单URL/扁平JSON/多行URL订阅源无需修改）：
     * - 格式①嵌套JSON：[{"name":"线路1","episodes":[{"title":"第1集","url":"..."}]}]
     *   name可选（缺省"线路N"），episodes必须，每个episode的url必须/title可选
     * - 格式②扁平JSON/多行URL：回退到 parseRssEpisodes，包装为单元素 List<RssRoute>
     * - 格式③单URL：返回null，交由现有逻辑处理
     *
     * 详见 docs/specs/douyin-style-video-player/design.md ruleContent JS 标准数据格式
     */
    fun parseRssRoutes(content: String, baseUrl: String): List<RssRoute>? {
        val trimmed = content.trim()
        // 格式①：嵌套 JSON 数组（含 episodes 字段判定为多线路格式）
        if (trimmed.startsWith("[")) {
            return try {
                val arr = JSONArray(trimmed)
                // 先检查是否是嵌套格式：第一个元素是否包含 episodes 字段
                if (arr.length() > 0) {
                    val firstObj = arr.getJSONObject(0)
                    if (firstObj.has("episodes")) {
                        // 嵌套 JSON 格式：解析为多线路
                        return (0 until arr.length()).map { i ->
                            val obj = arr.getJSONObject(i)
                            val epArr = obj.optJSONArray("episodes")
                            val episodes = if (epArr != null) {
                                (0 until epArr.length()).map { j ->
                                    val epObj = epArr.getJSONObject(j)
                                    RssEpisode(
                                        title = epObj.optString("title", "第${j + 1}集"),
                                        url = NetworkUtils.getAbsoluteURL(baseUrl, epObj.optString("url"))
                                    )
                                }.filter { it.url.isNotBlank() }
                            } else {
                                emptyList()
                            }
                            RssRoute(
                                name = obj.optString("name", "线路${i + 1}"),
                                episodes = episodes
                            )
                        }.filter { it.episodes.isNotEmpty() }
                    }
                }
                // 扁平 JSON 数组（无 episodes 字段）：回退到 parseRssEpisodes，包装为单线路
                val episodes = parseRssEpisodes(content, baseUrl)
                if (episodes != null && episodes.isNotEmpty()) {
                    listOf(RssRoute(name = "线路1", episodes = episodes))
                } else {
                    null
                }
            } catch (e: Exception) {
                // JSON 解析失败，回退到 parseRssEpisodes
                val episodes = parseRssEpisodes(content, baseUrl)
                if (episodes != null && episodes.isNotEmpty()) {
                    listOf(RssRoute(name = "线路1", episodes = episodes))
                } else {
                    null
                }
            }
        }
        // 多行 URL 格式：回退到 parseRssEpisodes，包装为单线路
        val episodes = parseRssEpisodes(content, baseUrl)
        return if (episodes != null && episodes.isNotEmpty()) {
            listOf(RssRoute(name = "线路1", episodes = episodes))
        } else {
            null
        }
    }

    /**
     * R3 多线路支持：切换线路
     *
     * 切换后自动更新 rssEpisodes + rssEpisodeIndex，并触发 UI 更新事件
     * 返回新线路的第一集 RssEpisode，由调用方执行播放
     */
    fun switchRssRoute(index: Int): RssEpisode? {
        val routes = rssRoutes ?: return null
        if (index < 0 || index >= routes.size) return null
        rssRouteIndex = index
        val route = routes[index]
        rssEpisodes = route.episodes
        rssEpisodeIndex = 0
        postEvent(EventBus.UP_VIDEO_INFO, arrayListOf(1))
        return route.episodes.firstOrNull()
    }

    /**
     * 上下滑动切换文章（video-article-swipe-switch spec）
     *
     * 切换到 rssArticles 中指定索引的文章，更新 rssStar/rssRecord 匹配新文章，
     * 重置集数/线路状态，复用 startPlay 加载该文章的视频信息。
     * 数据库查询在 IO 线程执行（Room 禁止主线程查询），startPlay 回到主线程执行。
     *
     * @param index 文章在 rssArticles 中的索引
     * @param player 播放器实例
     * @return true 切换成功，false 切换失败（无文章列表或索引越界）
     */
    fun switchToArticle(index: Int, player: StandardGSYVideoPlayer): Boolean {
        val articles = rssArticles ?: return false
        val article = articles.getOrNull(index) ?: return false
        rssArticleIndex = index
        // 重置集数/线路状态
        rssEpisodes = null
        rssRoutes = null
        rssEpisodeIndex = 0
        rssRouteIndex = 0
        videoTitle = article.title
        // 异步查询 rssStar/rssRecord（Room 禁止主线程查询）+ 加载视频信息
        Coroutine.async(loadScope, IO) {
            // 更新 rssStar/rssRecord 以匹配新文章（startPlay 依赖这些字段获取 rssArticle）
            rssStar = appDb.rssStarDao.get(article.origin, article.link)
            if (rssStar == null) {
                rssRecord = appDb.rssReadRecordDao.getRecord(article.link, article.origin)
            }
            withContext(Main) {
                // 重新加载该文章的视频信息（复用 startPlay 的 RssSource 分支）
                startPlay(player)
            }
        }.onError {
            AppLog.put("切换文章加载视频信息失败", it, true)
        }
        return true
    }

    /**
     * 阶段8 F9：分页加载下一页文章
     *
     * 当 ViewPager2 滑到最后一个文章时触发，异步请求下一页文章列表，
     * 追加到 rssArticles 并通过 EventBus.ARTICLES_LOADED 通知 adapter 刷新。
     *
     * 复用 Rss.getArticles 逻辑，分页上下文（sortName/sortUrl/nextPageUrl/page）保存在 VideoPlay 单例中。
     *
     * @return true 触发加载（已发起异步请求或正在加载），false 无需加载（无更多/无分页上下文）
     */
    fun loadMoreArticles(): Boolean {
        // 防重复加载
        if (isLoadingMoreArticles) {
            return false
        }
        // 无更多文章
        if (!rssArticlesHasMore) {
            return false
        }
        // 分页上下文缺失
        val rssSource = source as? RssSource ?: return false
        val sortName = rssSortName ?: return false
        val pageUrl = rssNextPageUrl ?: rssSortUrl
        if (pageUrl.isNullOrBlank()) {
            return false
        }

        isLoadingMoreArticles = true
        rssArticlePage++

        Rss.getArticles(loadScope, sortName, pageUrl, rssSource, rssArticlePage, null)
            .onSuccess(IO) { pair ->
                isLoadingMoreArticles = false
                val articles = pair.first
                val newNextPageUrl = pair.second
                if (articles.isEmpty()) {
                    rssArticlesHasMore = false
                    return@onSuccess
                }
                // 追加到内存列表
                val currentList = rssArticles?.toMutableList() ?: mutableListOf()
                currentList.addAll(articles)
                rssArticles = currentList
                // 更新下一页URL和是否有更多
                rssNextPageUrl = newNextPageUrl
                rssArticlesHasMore = !newNextPageUrl.isNullOrEmpty() && !rssSource.ruleNextPage.isNullOrEmpty()
                // 通知 adapter 刷新（传递新增文章数量）
                postEvent(EventBus.ARTICLES_LOADED, articles.size)
            }.onError {
                isLoadingMoreArticles = false
                rssArticlePage--  // 回退页码，允许下次重试
                AppLog.put("分页加载文章失败", it, true)
            }
        return true
    }

    /**
     * 阶段8 F10：预缓冲下一个文章的页面 HTML
     *
     * 当当前视频播放进度超过 80% 时触发，后台预加载下一个文章的页面 HTML，
     * 存入 preloadedHtmls 缓存。startPlay 的 R5 分支会优先使用缓存 HTML 跳过网络请求。
     *
     * 设计决策（ADR-8）：只预加载页面 HTML（轻量级），不预缓冲完整视频流。
     * 原因：预缓冲完整视频流需要创建额外播放器实例，管理复杂度高；
     * 而预加载 HTML 可跳过最大的延迟部分（网络请求），VideoUrlExtractor.extract 仍需执行但耗时极低。
     *
     * @param currentIndex 当前播放的文章索引
     */
    fun preloadNextArticleHtml(currentIndex: Int) {
        val articles = rssArticles ?: return
        val nextIndex = currentIndex + 1
        val nextArticle = articles.getOrNull(nextIndex) ?: return
        val link = nextArticle.link

        // 已预加载过则跳过
        if (preloadedArticles.contains(link) || preloadedHtmls.containsKey(link)) {
            return
        }
        val rssSource = source as? RssSource ?: return
        // ruleContent 不为空时走 Rss.getContent 而非 R5 抓取，无需预加载 HTML
        if (!rssSource.ruleContent.isNullOrBlank()) return

        preloadedArticles.add(link)

        Coroutine.async(loadScope, IO) {
            val pageAnalyzeUrl = AnalyzeUrl(link, source = source, ruleData = nextArticle)
            val res = pageAnalyzeUrl.getStrResponseAwait()
            val html = res.body ?: ""
            if (html.isNotEmpty()) {
                preloadedHtmls[link] = html
            }
        }.onError {
            AppLog.put("预缓冲下一文章HTML失败: ${nextArticle.title}", it)
        }
    }

    /**
     * 阶段8：清理预缓冲缓存
     *
     * 退出播放器时调用，释放内存。
     */
    fun clearPreloadCache() {
        preloadedHtmls.clear()
        preloadedArticles.clear()
    }

    /**
     * R1 多集选择播放：播放指定集
     *
     * 参考 startPlay RssSource 分支的 AnalyzeUrl + setUp + startPlayLogic 模式
     */
    fun playRssEpisode(player: GSYBaseVideoPlayer, episode: RssEpisode) {
        val rssArticle = rssStar?.toRssArticle() ?: rssRecord?.toRssArticle() ?: rssArticles?.getOrNull(rssArticleIndex)
        if (rssArticle == null) {
            appCtx.toastOnUi("未找到订阅")
            return
        }
        videoUrl = episode.url
        videoTitle = episode.title
        Coroutine.async(loadScope, IO) {
            val analyzeUrl = AnalyzeUrl(
                episode.url,
                source = source,
                ruleData = rssArticle
            )
            // R5 Header 修复：注入 Referer（模拟 WebView 行为，解决 CDN 防盗链 404）
            if (!analyzeUrl.headerMap.any { it.key.equals("Referer", ignoreCase = true) }) {
                analyzeUrl.headerMap["Referer"] = rssArticle.link
            }
            withContext(Main) {
                player.mapHeadData = analyzeUrl.headerMap
                currentPlayHeaders = analyzeUrl.headerMap
                // Bug8 修复：统一解析播放器页面 URL
                val resolvedUrl = VideoUrlExtractor.resolvePlayerPageUrl(analyzeUrl.url)
                player.setUp(resolvedUrl, cachePlay, File(appCtx.externalCache, "exoplayer"), episode.title)
                // R3 title 修复：TitleBar 统一显示文章标题（用户反馈：单URL/多行URL模式title用rssArticle.title）
                postEvent(EventBus.VIDEO_SUB_TITLE, rssArticle.title)
                if (autoPlay) {
                    player.startPlayLogic()
                }
            }
        }.onError {
            AppLog.put("加载订阅源视频集失败: ${episode.title}", it, true)
        }
    }

    /**
     * R1 多集选择播放：切换集数（上一集/下一集）
     *
     * 参考 upDurIndex 模式：检查边界→更新索引→播放→通知 UI
     */
    fun upRssEpisodeIndex(offset: Int, player: GSYBaseVideoPlayer): Boolean {
        val episodes = rssEpisodes ?: return false
        val index = rssEpisodeIndex + offset
        if (index < 0) {
            appCtx.toastOnUi("已到开头")
            return false
        }
        if (index >= episodes.size) {
            appCtx.toastOnUi("已播放完")
            return false
        }
        rssEpisodeIndex = index
        playRssEpisode(player, episodes[index])
        postEvent(EventBus.UP_VIDEO_INFO, arrayListOf(1)) //更新选集视图
        return true
    }

    fun saveRead(durPos: Int? = null) {
        val book = book
        val rssStar = rssStar
        val rssRecord = rssRecord
        val durPos = durPos ?: videoManager.currentPosition.toInt()
        durChapterPos = durPos
        if (book == null && rssStar == null && rssRecord == null) {
            videoUrl?.let { videoUrl ->
                CacheManager.put(VIDEO_POS_NAME + videoUrl, durPos, VIDEO_POS_SAVE_TIME)
            }
            return
        }
        val durVolumeIndex = durVolumeIndex
        val chapterInVolumeIndex = chapterInVolumeIndex
        val source = source
        val volumes = volumes.toList()
        val durVolume = durVolume
        val toc = toc
        Coroutine.async(executeContext = IO) {
            book?.let { book ->
                book.lastCheckCount = 0
                val durTime = System.currentTimeMillis()
                book.durChapterTime = durTime
                book.durVolumeIndex = durVolumeIndex
                book.chapterInVolumeIndex = chapterInVolumeIndex
                val durChapterIndex = if (volumes.isEmpty()) chapterInVolumeIndex else
                    (durVolume?.index ?: 0) + chapterInVolumeIndex + 1
                book.durChapterIndex = durChapterIndex
                book.durChapterPos = durPos
                val chapter = toc?.getOrNull(durChapterIndex)
                videoTitle = chapter?.title
                book.durChapterTitle = chapter?.title
                SourceCallBack.callBackBook(SourceCallBack.SAVE_READ, source as BookSource?, book, chapter, durTime.toString())
                book.update()
            }
            rssStar?.let {
                it.durPos = durPos
                videoTitle = it.title
                appDb.rssStarDao.update(it)
            }
            rssRecord?.let {
                it.durPos = durPos
                videoTitle = it.title
                appDb.rssReadRecordDao.update(it)
            }
            postEvent(EventBus.VIDEO_SUB_TITLE, videoTitle ?: appCtx.getString(R.string.data_loading))
        }
    }

    fun getDisplayCover(): String? {
        return book?.getDisplayCover() ?: rssStar?.image ?: rssRecord?.image
    }
}