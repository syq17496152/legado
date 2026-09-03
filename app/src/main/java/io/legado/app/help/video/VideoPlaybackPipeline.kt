package io.legado.app.help.video

import com.shuyu.gsyvideoplayer.video.base.GSYBaseVideoPlayer
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssEpisode
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.getDanmaku
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.video.engine.HeaderResolver
import io.legado.app.help.video.engine.SniffEngine
import io.legado.app.model.VideoPlay
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleDataInterface
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.externalCache
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

/**
 * 视频播放公共采集链组件（video-booksource-align-rss AD-03）
 *
 * 书源章节链（[VideoPlay.startPlayBookChapter] / startPlay 书源分支）与订阅源集链
 * （[VideoPlay.playRssEpisode]）共用唯一实现：
 * 直链判定 → 解析（getContent/三层嗅探）→ MPD 落盘 → HeaderResolver 三层头合并
 * → AnalyzeUrl → setUp → startPlayLogic。
 * 消除双链漂移（直链地址不正确/播放信息不匹配的架构根因）。
 *
 * token 守卫契约：调用方发起前递增 VideoPlay.switchTokenCounter 并写入 currentSwitchToken，
 * 新值传入 [PipelineContext.token]；异步回调 setUp 前经 [VideoPlay.currentSwitchToken] 校验，
 * 过期丢弃（防连续切换旧回调覆盖当前播放会话）。
 */
object VideoPlaybackPipeline {

    /**
     * 采集链上下文（书源章节 / 订阅源集 共用，按需填充）
     *
     * @param scope 异步作用域（VideoPlay.loadScope）
     * @param token 切换令牌（VideoPlay.switchTokenCounter 递增后的新值）
     * @param title setUp 标题（集名/文章名/章节名）
     * @param displayTitle VIDEO_SUB_TITLE 展示标题（订阅源链=文章名；null 时用 title）
     * @param refererFallback Referer 最终兜底（文章链接/章节 URL）
     * @param ruleData AnalyzeUrl 规则数据（Book / RssArticle）
     * @param onStarted 起播后 hook（订阅源 triggerPreload；书源无）
     */
    data class PipelineContext(
        val scope: CoroutineScope,
        val player: GSYBaseVideoPlayer,
        val source: BaseSource?,
        val token: Long,
        val title: String,
        val displayTitle: String? = null,
        val refererFallback: String?,
        val ruleData: RuleDataInterface?,
        val book: Book? = null,
        val chapter: BookChapter? = null,
        val article: RssArticle? = null,
        val episode: RssEpisode? = null,
        val onStarted: (() -> Unit)? = null
    )

    /**
     * 书源章节采集链：L0 直链快速路径 / getContent → MPD 落盘 → 三层嗅探 → 头合并 → setUp
     *
     * L0：章节 URL 已是视频流直链（m3u8/mp4 等）时跳过 getContent——ruleContent 对 m3u8
     * 清单发请求会产出整段清单文本当 URL 播放必然失败（铁证：hhm3u8 线路 index.m3u8）。
     */
    fun playBookChapter(ctx: PipelineContext): Coroutine<*> {
        val chapter = ctx.chapter ?: throw IllegalArgumentException("Pipeline.playBookChapter: chapter required")
        val book = ctx.book ?: throw IllegalArgumentException("Pipeline.playBookChapter: book required")
        val bookSource = ctx.source as? BookSource
            ?: throw IllegalArgumentException("Pipeline.playBookChapter: BookSource required")
        SniffEngine.invalidate()
        return Coroutine.async(ctx.scope, IO) {
            if (VideoUrlExtractor.isDirectVideoStreamUrl(chapter.url)) {
                AppLog.put("Pipeline: L0 直链快速路径, urlEnd=${chapter.url.takeLast(24)}")
                VideoPlay.videoUrl = chapter.url
                val analyzeUrl = AnalyzeUrl(
                    chapter.url,
                    source = ctx.source,
                    ruleData = book,
                    chapter = chapter
                )
                setUpAndPlay(ctx, analyzeUrl.url, analyzeUrl.headerMap, resolvePageUrl = false)
                VideoPlay.isLoadingFalse()
                return@async
            }
            WebBook.getContent(ctx.scope, bookSource, book, chapter)
                .onSuccess(IO) { content ->
                    val content = content.trim()
                    // 4.8c（Z10）：书源章节空正文统一错误提示（不裸抛）
                    if (content.isEmpty()) {
                        AppLog.putWarn("书源章节正文为空, 触发统一错误提示, chapter=${chapter.title}")
                        withContext(Main) {
                            postEvent(
                                EventBus.VIDEO_PLAY_ERROR,
                                "播放失败：书源章节正文为空，未获取到视频地址，请重试、切换书源/章节，或用系统浏览器打开"
                            )
                        }
                        return@onSuccess
                    }
                    val mUrl = if (content.startsWith("<")) { //当作mpd文本
                        val name = MD5Utils.md5Encode(content) + ".mpd"
                        val file = FileUtils.createFileIfNotExist(VideoPlay.videoTempFile, name)
                        file.writeText(content)
                        android.net.Uri.fromFile(file).toString()
                    } else {
                        // AD-08 R4-2：多行 content 统一解析（与订阅源 parseRssEpisodes 语义对齐）——
                        // ruleContent 产出多行地址时取首个合法 URL 行，避免整段清单文本被当 URL 播放
                        val lines = content.lines().map { it.trim() }.filter { it.isNotBlank() }
                        val first = lines.firstOrNull()
                        if (lines.size > 1 && first != null &&
                            (first.startsWith("http://") || first.startsWith("https://"))
                        ) {
                            first
                        } else {
                            content
                        }
                    }
                    VideoPlay.videoUrl = mUrl
                    // 播放页 URL 三层嗅探；MPD 文本/本地文件跳过嗅探，失败回退直连
                    val candidate = if (mUrl.startsWith("<") || mUrl.startsWith("file://")) {
                        null
                    } else {
                        VideoUrlExtractor.extractVideoUrlForEpisode(mUrl, ctx.source, chapter)
                    }
                    val sniffedUrl = candidate?.url ?: mUrl
                    VideoPlay.videoUrl = sniffedUrl
                    val analyzeUrl = AnalyzeUrl(
                        sniffedUrl,
                        source = ctx.source,
                        ruleData = book,
                        chapter = chapter
                    )
                    // AD-08：头合并统一为 HeaderResolver 三层（嗅探覆盖源配置 + Referer 兜底 + CookieManager 域内兜底）
                    val merged = HeaderResolver.merge(
                        candidate = candidate,
                        baseHeaders = analyzeUrl.headerMap,
                        refererFallback = ctx.refererFallback,
                        targetUrl = sniffedUrl
                    )
                    when (val danmaku = chapter.getDanmaku()) {
                        is String -> VideoPlay.danmakuStr = danmaku
                        is File -> VideoPlay.danmakuFile = danmaku
                    }
                    setUpAndPlay(ctx, analyzeUrl.url, merged, resolvePageUrl = true)
                    VideoPlay.isLoadingFalse()
                }.onError {
                    AppLog.put("获取资源链接出错\n$it", it, true)
                }
        }
    }

    /**
     * 订阅源集采集链：三层降级嗅探 → HeaderResolver 三层头合并 → setUp → VIDEO_SUB_TITLE → preload hook
     *
     * 采集失败（三层降级均失败）发 VIDEO_PLAY_ERROR 统一错误提示，不回退非视频流 URL。
     */
    fun playEpisode(ctx: PipelineContext): Coroutine<*> {
        val episode = ctx.episode ?: throw IllegalArgumentException("Pipeline.playEpisode: episode required")
        val article = ctx.article ?: throw IllegalArgumentException("Pipeline.playEpisode: article required")
        SniffEngine.invalidate()
        return Coroutine.async(ctx.scope, IO) {
            // 三层降级采集：MacCMS 播放页解析 → DOM 解析 → 网络抓包（URL 已是视频流时内部快速返回）
            val resolvedCandidate = VideoUrlExtractor.extractVideoUrlForEpisode(episode.url, ctx.source, article)
            val resolvedUrl = resolvedCandidate?.url
            if (resolvedUrl == null) {
                AppLog.putWarn("extractVideoUrlForEpisode 返回null, 触发统一错误提示, ${VideoUrlExtractor.sanitizeUrl(episode.url)}")
                withContext(Main) {
                    postEvent(
                        EventBus.VIDEO_PLAY_ERROR,
                        "播放失败：视频地址采集失败（三层降级均失败），请重试、切换线路/集数，或用系统浏览器打开"
                    )
                }
                return@async
            }
            val analyzeUrl = AnalyzeUrl(episode.url, source = ctx.source, ruleData = article)
            val merged = HeaderResolver.merge(
                candidate = resolvedCandidate,
                baseHeaders = analyzeUrl.headerMap,
                refererFallback = article.link,
                targetUrl = resolvedUrl
            )
            withContext(Main) {
                if (VideoPlay.currentSwitchToken != ctx.token) {
                    AppLog.put("Pipeline.playEpisode: token expired (${ctx.token} < ${VideoPlay.currentSwitchToken}), drop late callback")
                    return@withContext
                }
                // AD-08：setUp 前统一发一次 VIDEO_SUB_TITLE（标题单一权威，观察者经 displayEpisodeTitle 归一）
                // R3 title 修复语义保留：TitleBar 显示文章标题（title 为 GSY setUp 内部标题=集名）
                postEvent(EventBus.VIDEO_SUB_TITLE, ctx.displayTitle ?: ctx.title)
                ctx.player.mapHeadData = merged
                VideoPlay.currentPlayHeaders = merged
                ctx.player.setUp(resolvedUrl, VideoPlay.cachePlay, File(appCtx.externalCache, "exoplayer"), ctx.title)
                if (VideoPlay.autoPlay) {
                    ctx.player.startPlayLogic()
                }
                ctx.onStarted?.invoke()
            }
        }.onError {
            AppLog.put("加载订阅源视频集失败: ${episode.title}", it, true)
        }
    }

    /**
     * 统一 setUp 起播（主线程，token 过期丢弃）
     *
     * @param resolvePageUrl true=经 resolvePlayerPageUrl 解析播放器页面 URL（防 3003）；
     *        L0 直链路径 false（直链无需解析，保持旧行为）
     */
    private suspend fun setUpAndPlay(
        ctx: PipelineContext,
        url: String,
        headers: Map<String, String>,
        resolvePageUrl: Boolean
    ) {
        withContext(Main) {
            if (VideoPlay.currentSwitchToken != ctx.token) {
                AppLog.put("Pipeline: token expired (${ctx.token} < ${VideoPlay.currentSwitchToken}), drop late callback")
                return@withContext
            }
            // AD-08：setUp 前统一发一次 VIDEO_SUB_TITLE（书源链从 0 到 1 是行为增强）
            postEvent(EventBus.VIDEO_SUB_TITLE, ctx.title)
            ctx.player.mapHeadData = headers
            VideoPlay.currentPlayHeaders = headers
            val playUrl = if (resolvePageUrl) {
                VideoUrlExtractor.resolvePlayerPageUrl(url)
            } else {
                url
            }
            ctx.player.setUp(playUrl, VideoPlay.cachePlay, File(appCtx.externalCache, "exoplayer"), ctx.title)
            if (VideoPlay.autoPlay) {
                ctx.player.startPlayLogic()
            }
        }
    }
}
