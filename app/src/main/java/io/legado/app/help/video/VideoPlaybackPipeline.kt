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
import io.legado.app.exception.ContentEmptyException
import io.legado.app.help.book.getDanmaku
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.CencDrmSessionProvider
import io.legado.app.help.exoplayer.CencKeyStore
import io.legado.app.help.exoplayer.CencLocalFallback
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
import kotlinx.coroutines.delay
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
     * @param preResolved 预解析结果（§6.2：命中则跳过嗅探；null 时按缓存键自动查 [NextEpisodeResolver]）
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
        val onStarted: (() -> Unit)? = null,
        val preResolved: NextEpisodeResolver.PreResolved? = null
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
        // video-regression-fix-0906 日志增强：采集链入口固定标记（此前全链入口无日志，失败时无法确认链路是否启动）
        AppLog.put(
            "Pipeline: playBookChapter entry, token=${ctx.token}, " +
                "chapterUrlEnd=${chapter.url.takeLast(20)}, bookUrlHash=${(book.bookUrl ?: "").hashCode()}"
        )
        SniffEngine.invalidate()
        // CENC（AD-05）：新一次采集先清空上一集声明，避免 kid/encryption 跨集残留
        VideoPlay.clearCenc()
        // §6.2：书源同线路下一章的预解析结果（命中则跳过 getContent + 三层嗅探）
        val cacheKey = NextEpisodeResolver.key(chapter.url, ctx.source?.getKey(), null)
        return Coroutine.async(ctx.scope, IO) {
            val pre = ctx.preResolved ?: NextEpisodeResolver.get(cacheKey)
            if (pre != null) {
                VideoTrace.event(
                    stage = "sniff_resolved",
                    "chain" to "book",
                    "preResolved" to true,
                    "final" to VideoTrace.urlPath(pre.finalUrl)
                )
                VideoPlay.videoUrl = pre.finalUrl
                // 登记已解析章节（布局切换短路重采集依据）
                VideoPlay.resolvedChapterUrl = chapter.url
                setUpAndPlay(ctx, pre.finalUrl, pre.headers, resolvePageUrl = false)
                VideoPlay.isLoadingFalse()
                return@async
            }
            if (VideoUrlExtractor.isDirectVideoStreamUrl(chapter.url)) {
                AppLog.put("Pipeline: L0 直链快速路径, urlEnd=${chapter.url.takeLast(24)}")
                VideoPlay.videoUrl = chapter.url
                // video-regression-fix-0906 AD-03：登记已解析章节（布局切换短路重采集依据）
                VideoPlay.resolvedChapterUrl = chapter.url
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
                .onSuccess(IO) { rawContent ->
                    var content = rawContent.trim()
                    // CENC 契约（AD-05）：ruleContent 返回 {"url","kid","encryption"} 时拆出播放地址 + DRM 声明
                    parseCencContent(content)?.let { cenc ->
                        content = cenc.first
                        VideoPlay.cencKid = cenc.second
                        VideoPlay.cencEncryption = cenc.third
                        // 记录声明对应远端地址：播放器仅在该地址上启用 DRM（本地降级地址不匹配）
                        VideoPlay.cencUrl = cenc.first
                        AppLog.put(
                            "Pipeline: CENC 声明命中, kidLen=${cenc.second?.length ?: 0}, " +
                                "encryptionMatched=${CencDrmSessionProvider.isCencEncryption(cenc.third)}"
                        )
                    }
                    // 4.8c（Z10）：书源章节空正文统一错误提示（不裸抛）
                    if (content.isEmpty()) {
                        AppLog.putWarn("书源章节正文为空, 触发统一错误提示, chapter=${chapter.title}")
                        withContext(Main) {
                            // 2026-10-25：失败必须同时收起切集加载反馈（否则遮罩永久残留）
                            postEvent(EventBus.VIDEO_SWITCH_LOADING, false)
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
                    // CENC 软件降级（AD-04）：设备无 ClearKey 且已注入 key ⇒ 采集阶段先本地解密，
                    // 播放器只见明文本地文件（避免远端加密流先被装载导致提取器报加密轨）
                    val localCencUrl = prepareCencLocal(mUrl, ctx, book, chapter)
                    if (localCencUrl != null) {
                        VideoPlay.videoUrl = localCencUrl
                        VideoPlay.resolvedChapterUrl = chapter.url
                        val localAnalyzeUrl = AnalyzeUrl(
                            localCencUrl,
                            source = ctx.source,
                            ruleData = book,
                            chapter = chapter
                        )
                        setUpAndPlay(
                            ctx, localCencUrl, localAnalyzeUrl.headerMap, resolvePageUrl = false
                        )
                        VideoPlay.isLoadingFalse()
                        return@onSuccess
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
                    // video-regression-fix-0906 AD-03：登记已解析章节（布局切换短路重采集依据）
                    VideoPlay.resolvedChapterUrl = chapter.url
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
                    // video-regression-fix-0906 AD-03：采集失败清解析登记（防短路复用失效地址）
                    VideoPlay.resolvedChapterUrl = null
                    // 2026-10-25：异常路径也必须收起切集加载反馈并给出可读提示，
                    // 否则「内容为空/采集抛错」会留下永久遮罩（真机：第80集被判越权后长转圈）。
                    notifyPlayFailure(playFailureMessage(it))
                }
        }
    }

    /**
     * video-regression-fix-0906 AD-03：书源章节短路重放（布局切换复用已解析地址）
     *
     * 前置：startPlayBookChapter 已校验 VideoPlay.videoUrl 非空且 resolvedChapterUrl == chapter.url。
     * 不 invalidate SniffEngine、不 getContent、不嗅探——直接 AnalyzeUrl + 头合并（Referer 兜底）+ setUp。
     * 起播失败由 startPlayBookChapter 的 12s 看门狗回退全量采集链（本函数不做错误处理）。
     */
    fun replayBookChapter(ctx: PipelineContext): Coroutine<*> {
        val chapter = ctx.chapter ?: throw IllegalArgumentException("Pipeline.replayBookChapter: chapter required")
        val book = ctx.book ?: throw IllegalArgumentException("Pipeline.replayBookChapter: book required")
        val resolved = VideoPlay.videoUrl
            ?: throw IllegalArgumentException("Pipeline.replayBookChapter: videoUrl null")
        return Coroutine.async(ctx.scope, IO) {
            AppLog.put("Pipeline: replayBookChapter 短路重放, urlEnd=${resolved.takeLast(24)}")
            val analyzeUrl = AnalyzeUrl(
                resolved,
                source = ctx.source,
                ruleData = book,
                chapter = chapter
            )
            val merged = HeaderResolver.merge(
                candidate = null,
                baseHeaders = analyzeUrl.headerMap,
                refererFallback = ctx.refererFallback,
                targetUrl = resolved
            )
            setUpAndPlay(ctx, analyzeUrl.url, merged, resolvePageUrl = resolved.startsWith("file://"))
            VideoPlay.isLoadingFalse()
        }.onError {
            // 2026-10-25：短路重放失败也要收起加载反馈（12s 看门狗负责回退全量采集链）
            VideoPlay.resolvedChapterUrl = null
            withContext(Main) { postEvent(EventBus.VIDEO_SWITCH_LOADING, false) }
            AppLog.put("Pipeline: replayBookChapter 失败", it, true)
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
        // CENC（AD-05）：新一次采集先清空上一集声明，避免 kid/encryption 跨集残留
        VideoPlay.clearCenc()
        // §6.2：预解析缓存键（命中即跳过嗅探；失败时按同一键失效）
        val cacheKey = NextEpisodeResolver.key(episode.url, ctx.source?.getKey(), article.link)
        return Coroutine.async(ctx.scope, IO) {
            val pipeStart = android.os.SystemClock.uptimeMillis()
            VideoTrace.event(
                stage = "pipe_start",
                "token" to ctx.token,
                "episode" to ctx.title,
                "url" to VideoTrace.urlPath(episode.url),
                "cacheKey" to cacheKey
            )
            val pre = ctx.preResolved ?: NextEpisodeResolver.get(cacheKey)
            // 三层降级采集：MacCMS 播放页解析 → DOM 解析 → 网络抓包（URL 已是视频流时内部快速返回）
            val resolvedCandidate =
                if (pre == null) VideoUrlExtractor.extractVideoUrlForEpisode(episode.url, ctx.source, article)
                else null
            val resolvedUrl = pre?.finalUrl ?: resolvedCandidate?.url
            VideoTrace.event(
                stage = "sniff_resolved",
                "token" to ctx.token,
                "ok" to (resolvedUrl != null),
                "costMs" to (android.os.SystemClock.uptimeMillis() - pipeStart),
                "final" to VideoTrace.urlPath(resolvedUrl),
                "direct" to (resolvedUrl != null && resolvedUrl == episode.url),
                "preResolved" to (pre != null)
            )
            if (resolvedUrl == null) {
                NextEpisodeResolver.invalidate(cacheKey)
                AppLog.putWarn("extractVideoUrlForEpisode 返回null, 触发统一错误提示, ${VideoUrlExtractor.sanitizeUrl(episode.url)}")
                withContext(Main) {
                    postEvent(EventBus.VIDEO_SWITCH_LOADING, false)
                    postEvent(
                        EventBus.VIDEO_PLAY_ERROR,
                        "播放失败：视频地址采集失败（三层降级均失败），请重试、切换线路/集数，或用系统浏览器打开"
                    )
                }
                return@async
            }
            val analyzeUrl = AnalyzeUrl(episode.url, source = ctx.source, ruleData = article)
            val merged =
                if (pre != null && pre.headers.isNotEmpty()) pre.headers
                else HeaderResolver.merge(
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
                VideoTrace.event(
                    stage = "pipe_headers",
                    "token" to ctx.token,
                    "count" to merged.size,
                    "keys" to merged.keys.take(6).joinToString(",")
                )
                val setUpStart = android.os.SystemClock.uptimeMillis()
                VideoTrace.event(
                    stage = "setUp_call",
                    "token" to ctx.token,
                    "playerId" to System.identityHashCode(ctx.player),
                    "url" to VideoTrace.urlPath(resolvedUrl),
                    "chain" to "rss"
                )
                var setUpOk = ctx.player.setUp(
                    resolvedUrl,
                    VideoPlay.cachePlay,
                    File(appCtx.externalCache, "exoplayer"),
                    ctx.title
                )
                if (!setUpOk) {
                    // §14.1：GSY setUp 有 2 秒保护（刚 release 未就绪会静默返回 false），
                    // 快速连点/自动连播场景下重试一次，避免「点了没反应」
                    VideoTrace.event(
                        stage = "setUp_rejected",
                        "token" to ctx.token,
                        "playerId" to System.identityHashCode(ctx.player),
                        "chain" to "rss"
                    )
                    delay(300)
                    setUpOk = ctx.player.setUp(
                        resolvedUrl,
                        VideoPlay.cachePlay,
                        File(appCtx.externalCache, "exoplayer"),
                        ctx.title
                    )
                }
                VideoTrace.event(
                    stage = "setUp_ret",
                    "token" to ctx.token,
                    "playerId" to System.identityHashCode(ctx.player),
                    "ok" to setUpOk,
                    "costMs" to (android.os.SystemClock.uptimeMillis() - setUpStart)
                )
                if (!setUpOk) {
                    // §14.1：setUp 2 秒保护返回 false ⇒ 本次地址不可用，预解析缓存立即失效，避免下次继续命中
                    NextEpisodeResolver.invalidate(cacheKey)
                }
                if (VideoPlay.autoPlay) {
                    VideoTrace.event(
                        stage = "start_play",
                        "token" to ctx.token,
                        "playerId" to System.identityHashCode(ctx.player),
                        "chain" to "rss"
                    )
                    ctx.player.startPlayLogic()
                }
                // 2026-10-25：订阅源集同理——setUp 下发即收起切集加载反馈
                postEvent(EventBus.VIDEO_SWITCH_LOADING, false)
                ctx.onStarted?.invoke()
            }
        }.onError {
            NextEpisodeResolver.invalidate(cacheKey)
            AppLog.put("加载订阅源视频集失败: ${episode.title}", it, true)
            // 2026-10-25：订阅源集采集异常同样收起加载反馈并给出可读提示
            notifyPlayFailure(playFailureMessage(it))
        }
    }

    /**
     * 采集失败的用户可读提示（区分「内容为空/需登录」与其他异常）。
     *
     * 2026-10-25：书源侧对越权集返回空串（拒绝播错集）⇒ `ContentEmptyException`，
     * 必须给用户明确方向（登录/换源），而不是无限转圈。
     */
    private fun playFailureMessage(error: Throwable): String =
        if (error is ContentEmptyException) {
            "播放失败：该集未获取到播放地址（可能需登录书源或超出免费集数），请登录后重试或切换源"
        } else {
            "播放失败：视频地址获取失败（${error.javaClass.simpleName}），请重试或切换源"
        }

    /** 失败统一出口：收起切集加载反馈 + 发统一错误提示（主线程） */
    private suspend fun notifyPlayFailure(message: String) {
        withContext(Main) {
            postEvent(EventBus.VIDEO_SWITCH_LOADING, false)
            postEvent(EventBus.VIDEO_PLAY_ERROR, message)
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
            // video-regression-fix-0906 日志增强：setUp 定点标记（连接采集链与播放器实例，配合 playerId 追踪死实例）
            AppLog.put(
                "Pipeline: setUpAndPlay, token=${ctx.token}, urlEnd=${url.takeLast(20)}, " +
                    "playerId=${System.identityHashCode(ctx.player)}, autoPlay=${VideoPlay.autoPlay}"
            )
            // AD-08：setUp 前统一发一次 VIDEO_SUB_TITLE（书源链从 0 到 1 是行为增强）
            postEvent(EventBus.VIDEO_SUB_TITLE, ctx.title)
            ctx.player.mapHeadData = headers
            VideoPlay.currentPlayHeaders = headers
            VideoTrace.event(
                stage = "pipe_headers",
                "token" to ctx.token,
                "count" to headers.size,
                "keys" to headers.keys.take(6).joinToString(",")
            )
            val playUrl = if (resolvePageUrl) {
                VideoUrlExtractor.resolvePlayerPageUrl(url)
            } else {
                url
            }
            val setUpStart = android.os.SystemClock.uptimeMillis()
            VideoTrace.event(
                stage = "setUp_call",
                "token" to ctx.token,
                "playerId" to System.identityHashCode(ctx.player),
                "url" to VideoTrace.urlPath(playUrl),
                "resolvePageUrl" to resolvePageUrl
            )
            var setUpOk = ctx.player.setUp(
                playUrl,
                VideoPlay.cachePlay,
                File(appCtx.externalCache, "exoplayer"),
                ctx.title
            )
            if (!setUpOk) {
                // §14.1：2 秒保护返回 false ⇒ 重试一次（书源快速连点/自动连播同收益）
                VideoTrace.event(
                    stage = "setUp_rejected",
                    "token" to ctx.token,
                    "playerId" to System.identityHashCode(ctx.player),
                    "chain" to "book"
                )
                delay(300)
                setUpOk = ctx.player.setUp(
                    playUrl,
                    VideoPlay.cachePlay,
                    File(appCtx.externalCache, "exoplayer"),
                    ctx.title
                )
            }
            VideoTrace.event(
                stage = "setUp_ret",
                "token" to ctx.token,
                "playerId" to System.identityHashCode(ctx.player),
                "ok" to setUpOk,
                "costMs" to (android.os.SystemClock.uptimeMillis() - setUpStart)
            )
            if (!setUpOk) {
                // 预解析结果不可用 ⇒ 失效，避免下次继续命中死地址
                NextEpisodeResolver.invalidate(
                    NextEpisodeResolver.key(ctx.chapter?.url ?: "", ctx.source?.getKey(), null)
                )
            }
            if (VideoPlay.autoPlay) {
                VideoTrace.event(
                    stage = "start_play",
                    "token" to ctx.token,
                    "playerId" to System.identityHashCode(ctx.player)
                )
                ctx.player.startPlayLogic()
            }
            // 2026-10-25：setUp 已下发 ⇒ 采集/嗅探空窗结束，收起切集加载反馈。
            // 此后等待首帧期间的缓冲由播放器内置 loading 圈承担，避免双重提示。
            postEvent(EventBus.VIDEO_SWITCH_LOADING, false)
            // 起播后 hook（§6.3 书源下一章预解析）
            ctx.onStarted?.invoke()
        }
    }

    /**
     * CENC 软件降级前置（AD-04）：设备不具备 ClearKey 且本地已注入 key 时，先把远端加密流下载并解密为
     * 本地明文 fMP4，返回 file:// 地址；否则返回 null（调用方保持原远程播放/失败路径）。
     */
    private suspend fun prepareCencLocal(
        url: String,
        ctx: PipelineContext,
        book: Book,
        chapter: BookChapter
    ): String? {
        if (!CencDrmSessionProvider.isCencEncryption(VideoPlay.cencEncryption)) return null
        if (!CencLocalFallback.isRequired(VideoPlay.cencKid, VideoPlay.cencEncryption)) return null
        val kid = CencDrmSessionProvider.normalizeKid(VideoPlay.cencKid)
            ?: return null
        val key = CencKeyStore.provider.key(kid)
        if (key == null) {
            AppLog.put("Pipeline: CENC 缺少解密密钥，无法本地解密, kidLen=${kid.length}")
            return null
        }
        val headers = kotlin.runCatching {
            AnalyzeUrl(url, source = ctx.source, ruleData = book, chapter = chapter).headerMap
        }.getOrDefault(emptyMap())
        val localFile = CencLocalFallback.prepare(url, headers, kid, key) ?: return null
        VideoPlay.clearCenc()
        AppLog.put("Pipeline: CENC 本地解密完成，改用本地文件播放, size=${localFile.length()}")
        return android.net.Uri.fromFile(localFile).toString()
    }

    /**
     * CENC 契约解析（AD-05）：`{"url":"...","kid":"32hex","encryption":"cenc-aes-ctr"}`。
     *
     * 非该契约（普通字符串 URL / MPD 文本 / 空串）一律返回 null，保持原解析路径不变。
     * 只接受 http(s) 地址，避免把任意 JSON 文本误当播放地址。
     */
    internal fun parseCencContent(content: String): Triple<String, String?, String?>? {
        val obj = kotlin.runCatching { org.json.JSONObject(content) }.getOrNull() ?: return null
        val url = obj.optString("url").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return Triple(
            url,
            obj.optString("kid").takeIf { it.isNotBlank() },
            obj.optString("encryption").takeIf { it.isNotBlank() }
        )
    }
}
