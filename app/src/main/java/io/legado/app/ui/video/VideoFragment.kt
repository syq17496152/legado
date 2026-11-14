package io.legado.app.ui.video

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.shuyu.gsyvideoplayer.listener.GSYSampleCallBack
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssEpisode
import io.legado.app.help.book.isVideo
import io.legado.app.help.gsyVideo.ChoiceSpeedDialog
import io.legado.app.help.gsyVideo.VideoPlayer
import io.legado.app.help.source.isVideoSource
import io.legado.app.help.video.LocalMediaResolver
import io.legado.app.help.video.VideoPlaylistHolder
import io.legado.app.help.video.VideoTrace
import io.legado.app.model.Download
import io.legado.app.model.VideoPlay
import io.legado.app.service.DownloadTaskType
import io.legado.app.service.kernel.VideoCacheKernel
import io.legado.app.data.PlayHistoryStore
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.utils.gone
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import splitties.init.appCtx

/**
 * R3 沉浸竖滑风格视频播放 Fragment
 *
 * ViewPager2 中的单个视频播放单元。
 * 每个 Fragment 持有一个 VideoPlayer（GSY）+ 悬浮控件层。
 *
 * 三种播放状态：
 * - PURE：纯净播放态，所有控件隐藏，仅视频画面
 * - NORMAL：竖屏常态，显示标题+功能按钮+全屏按钮
 * - FULLSCREEN：横屏全屏态，Activity 旋转横屏，控件可单击显隐
 *
 * 交互：
 * - 单击切换控件显隐（PURE↔NORMAL / FULLSCREEN内显隐切换）
 * - 双指拉伸触发全屏（scaleFactor > 1.2）
 * - 全屏按钮常显（横竖屏一致），点击切换全屏
 */
/**
 * 位置指示的索引来源（纯策略，单测锁定）。
 *
 * 书源会话（含"书源视频映射为 rssEpisodes"）以 `chapterInVolumeIndex` 为准，
 * 与左下角标题同源；否则与本会话的 `rssEpisodeIndex` 错位一集（真机实证 2026-10-24）。
 */
internal fun episodeIndicatorIndex(
    bookSession: Boolean,
    chapterInVolumeIndex: Int,
    rssEpisodeIndex: Int,
): Int = if (bookSession) chapterInVolumeIndex else rssEpisodeIndex

/**
 * 位置指示的「篇 + 集」双维度结果（纯数据，便于单测锁定）。
 *
 * - `listIndex/listTotal`：来源列表位置（书源=播放列表，订阅源=文章列表）；null 表示不显示。
 * - `episodeIndex/episodeTotal`：剧集位置（书源=目录章节，订阅源=rssEpisodes）；null 表示不显示。
 * - 索引一律 0-based，且必须落在合法范围；越界按 null 处理（宁可不显示，不显示错误位置）。
 */
internal data class PlaybackPosition(
    val listIndex: Int?,
    val listTotal: Int,
    val episodeIndex: Int?,
    val episodeTotal: Int,
)

/**
 * 解析「第X篇 · 共Y篇」与「第X集 · 全Y集」两个维度。
 *
 * 语义边界（2026-10-27 用户反馈）：
 * - 篇是公共信息：书源读 [VideoPlaylistHolder] 的播放列表，订阅源读 rssArticles；
 * - 集独立展示：书源以 `chapterInVolumeIndex` 为权威，订阅源以 `rssEpisodeIndex` 为权威；
 * - 篇与集互不串线，只有集允许触发选集弹框。
 */
internal fun resolvePlaybackPosition(
    hasBook: Boolean,
    rssArticleIndex: Int,
    rssArticleCount: Int,
    bookPlaylistIndex: Int?,
    bookPlaylistTotal: Int,
    bookEpisodeIndex: Int,
    bookEpisodeCount: Int,
    rssEpisodeIndex: Int,
    rssEpisodeCount: Int,
): PlaybackPosition {
    val listIndex = if (hasBook) {
        bookPlaylistIndex?.takeIf { bookPlaylistTotal > 1 && it in 0 until bookPlaylistTotal }
    } else {
        rssArticleIndex.takeIf { rssArticleCount > 1 && it in 0 until rssArticleCount }
    }
    val listTotal = when {
        listIndex == null -> 0
        hasBook -> bookPlaylistTotal
        else -> rssArticleCount
    }
    val bookEpisodeSession = hasBook && bookEpisodeCount > 1
    val episodeIndex = if (bookEpisodeSession) {
        bookEpisodeIndex.takeIf { it in 0 until bookEpisodeCount }
    } else {
        rssEpisodeIndex.takeIf { rssEpisodeCount > 1 && it in 0 until rssEpisodeCount }
    }
    val episodeTotal = when {
        episodeIndex == null -> 0
        bookEpisodeSession -> bookEpisodeCount
        else -> rssEpisodeCount
    }
    return PlaybackPosition(listIndex, listTotal, episodeIndex, episodeTotal)
}

/**
 * 位置指示段类型（纯数据，单测锁定）。
 *
 * 语义：篇是公共信息，**永不**触发选集；集是选集入口（tasks §7.3）。
 */
internal enum class PositionSegmentKind { ARTICLE, EPISODE }

/**
 * 位置指示段：`index` 为 0-based 下标，`total` 为总数。
 */
internal data class PositionSegment(
    val kind: PositionSegmentKind,
    val index: Int,
    val total: Int,
) {
    /** 只有集段可点（打开选集面板） */
    val clickable: Boolean get() = kind == PositionSegmentKind.EPISODE
}

/**
 * 纯函数：把 [PlaybackPosition] 拆成展示段，顺序固定「篇在前、集在后」。
 *
 * 抽出的目的：把「显示哪些段 / 哪段可点」变成可 JVM 单测的决策，避免只有渲染代码可验证。
 */
internal fun positionSegments(position: PlaybackPosition): List<PositionSegment> = buildList {
    position.listIndex?.let {
        add(PositionSegment(PositionSegmentKind.ARTICLE, it, position.listTotal))
    }
    position.episodeIndex?.let {
        add(PositionSegment(PositionSegmentKind.EPISODE, it, position.episodeTotal))
    }
}

/**
 * 次要入口（倍速/收藏/下载）可见性（纯策略，单测锁定）。
 *
 * 右侧按钮增至 7 个后，横屏全屏可视高度（约 360dp）不足以完整容纳，
 * 故全屏时只保留「全屏 / 听视频 / 设置」三个核心入口。
 */
internal fun secondaryEntriesVisible(isFullscreen: Boolean): Boolean = !isFullscreen

/**
 * 灰条写值决策（AD-39，纯函数便于单测）。
 *
 * `-1`（播放器未就绪）或越界值不得写入：写 0 会让灰条倒退、造成「缓冲丢失」的错觉。
 */
internal fun readySecondaryProgress(percentage: Int): Int? =
    percentage.takeIf { it in 0..100 }

class VideoFragment : Fragment() {

    // ==================== 播放状态枚举 ====================

    enum class PlayState {
        /** 纯净播放态：所有悬浮控件隐藏，仅视频画面 */
        PURE,
        /** 竖屏常态：显示标题+功能按钮+全屏按钮 */
        NORMAL,
        /** 横屏全屏态：Activity 旋转横屏 */
        FULLSCREEN
    }

    private var _playerView: VideoPlayer? = null
    val playerView: VideoPlayer? get() = _playerView

    // video-sniff-403-and-rss-classic-fix Phase 2 (3.3)：WebView 降级播放器已删除
    // （webViewPlayer/btnSwitchBack/isWebViewMode 字段及 switchToWebViewMode/switchBackToExo 已清理，
    //   失败承接 = tryNextFallback 降级链 + retryExoPlayback 重试 + 错误对话框"重试/系统浏览器"）

    // 阶段8 F10：进度监听 Handler（80%进度触发预缓冲下一文章）
    private val progressMonitorHandler = Handler(Looper.getMainLooper())
    private var progressMonitorRunnable: Runnable? = null

    /** 当前 Fragment 在 ViewPager2 中的位置索引
     *  - 文章模式（rssArticles != null）：表示文章索引
     *  - 集数模式（rssEpisodes != null）：表示集数索引
     *  - 书源/单URL模式：固定为 0
     */
    private val episodeIndex: Int by lazy {
        arguments?.getInt(ARG_EPISODE_INDEX, 0) ?: 0
    }

    /** 防止重复激活（onPageSelected + onFragmentViewReady 可能触发两次） */
    private var isActivated = false

    /** 当前播放状态（用户需求：初始为 NORMAL 控件显示，左右滑动时隐藏） */
    private var currentState = PlayState.NORMAL

    /** 横屏全屏态下控件是否可见（单击切换） */
    private var controlsVisibleInFullscreen = true

    /** 触摸到达次数（诊断用：采样输出 `stage=gesture_touch`，避免每帧刷屏） */
    private var touchEventCount = 0

    // ==================== 悬浮控件视图引用 ====================

    private var controlsLayer: ConstraintLayout? = null
    private var leftBottomContainer: LinearLayout? = null
    private var tvVideoTitle: TextView? = null
    // video-player-ux-fixes P4: 全屏态标题（左上角返回按钮右侧），与 tvVideoTitle 由 setTitle() 统一同步
    private var tvTitleFullscreen: TextView? = null
    private var tvRouteSelector: TextView? = null
    private var routeMenuPopup: ModernActionPopup.Handle? = null
    private var rvEpisodes: RecyclerView? = null
    /** F181：沉浸式播放位置感知「第X集 · 共Y集」（与传统布局信息区同源同值） */
    private var tvPositionIndicator: TextView? = null
    /** F180：一次性手势引导卡（首次进入沉浸式播放时展示） */
    private var gestureGuideCard: LinearLayout? = null
    private var rightButtons: LinearLayout? = null
    private var btnStar: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private var btnFullscreen: ImageButton? = null
    private var btnDownload: ImageButton? = null
    /** video-cache-and-multiep R10 / V6：听视频模式入口（全屏下方耳机） */
    private var btnListen: ImageButton? = null
    /** video-cache-and-multiep R9 / V5：沉浸式倍速入口（底部上浮单排面板） */
    private var btnSpeed: ImageButton? = null
    /** 弹幕开关（沉浸式悬浮层入口，与传统模式 GSY 控制条 toggle_danmaku 同状态源） */
    private var btnDanmaku: ImageButton? = null
    // B1+ 修复：全屏模式下的悬浮返回按钮（F1 的 titleBarNew.gone() 隐藏了 TitleBar 返回按钮）
    private var btnBackOverlay: ImageButton? = null

    /** 手势检测器：单击切换控件显隐 */
    private var gestureDetector: GestureDetector? = null

    /** 4.5 双指缩放手势检测器：检测双指拉伸触发全屏 */
    private var scaleGestureDetector: ScaleGestureDetector? = null

    /** 双指左右滑动检测：用户需求——"同时左右滑动"时隐藏控件 */
    private var twoFingerStartX1 = 0f
    private var twoFingerStartX2 = 0f
    private var isTwoFingerSwipe = false

    /** 文章模式下单指垂直滑动检测：上下滑动切换文章 */
    private var singleFingerStartX = 0f
    private var singleFingerStartY = 0f
    private var isVerticalSwipe = false

    /** video-gesture-overhaul: 长按倍速状态标志（onLongPress 设置，ACTION_UP 恢复） */
    private var isLongPressSpeed = false

    /** video-gesture-overhaul: 左右滑动 seek 状态（R2: 替代快退/快进按钮） */
    private var slideSeekStartX = 0f
    private var isSeeking = false
    private var seekTarget = 0L
    /** 修复: ACTION_DOWN 时记录的播放位置，避免滑动过程中 currentPosition 持续变化导致 seekTarget 漂移 */
    private var slideSeekStartPos = 0L
    /** 修复: ACTION_DOWN 时记录的播放地址，切集后丢弃本次 seek（否则会把旧集进度套到新集，连锁触发播完） */
    private var slideSeekStartUrl: String? = null

    // FLING_MIN_VELOCITY 定义在文件尾部既有 companion object 中

    // ==================== F1: 缓冲进度条更新 ====================
    /** GSY 底部进度条引用（用于更新 secondaryProgress 显示缓冲进度） */
    private var bottomProgressbar: ProgressBar? = null
    /** 缓冲进度更新 Handler（每 500ms 轮询 bufferedPercentage 更新 secondaryProgress） */
    private val bufferUpdateHandler = Handler(Looper.getMainLooper())
    private var bufferUpdateRunnable: Runnable? = null
    /** IF-16 诊断：底部条灰条回退日志限流（防止刷屏） */
    private var bufferJumpLogCount = 0

    // ==================== F2: 控件自动隐藏（3秒无操作后隐藏） ====================
    /** 自动隐藏 Handler：控件显示 3 秒后自动切换到 PURE 态 */
    private val autoHideHandler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable {
        when (currentState) {
            PlayState.NORMAL -> switchState(PlayState.PURE)
            PlayState.FULLSCREEN -> {
                if (controlsVisibleInFullscreen) {
                    controlsVisibleInFullscreen = false
                    hideControlsAnimated()
                    _playerView?.setGsyControlVisibility(false)  // F2 修复：同步隐藏 GSY 原始控件
                }
            }
            else -> { /* PURE 态无需处理 */ }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_video, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _playerView = view.findViewById(R.id.playerView)
        // video-player-image-enhance A1.3: 注册播放器视图（设置面板实时刷新通道）+ 立即应用已保存的滤镜
        ImageEnhanceController.registerPlayerView(_playerView)
        // F1: 获取 GSY 底部进度条引用（用于更新 secondaryProgress 显示缓冲进度）
        // 诊断日志：确认 playerView 和 bottomProgressbar 初始化状态（排查进度条消失问题）
        bottomProgressbar = _playerView?.findViewById(R.id.bottom_progressbar)
        AppLog.put("VideoFragment onViewCreated: episode=$episodeIndex, playerView=${_playerView != null}, bottomProgressbar=${bottomProgressbar != null}")

        // video-sniff-403-and-rss-classic-fix Phase 2 (3.3)：WebView 降级播放器初始化已删除（布局同步清理）

        // 初始化悬浮控件
        initOverlayControls(view)

        // 初始化手势检测（含双指缩放）
        initGestureDetector(view)

        // R3: 通知 Activity 视图已就绪
        (activity as? VideoPlayerActivity)?.onFragmentViewReady(this, episodeIndex)
    }

    override fun onDestroyView() {
        // 诊断日志（永久保留）：区分「当前 Fragment 销毁」与「ViewPager2 邻页/复建销毁」——
        // 后者若释放全局嗅探资源，会把正在播放的集掐断（黑屏无反馈的真机故障根因候选）
        AppLog.put(
            "VideoFragment onDestroyView: episode=$episodeIndex, isActivated=$isActivated, " +
                "pagerCurrent=${(activity as? VideoPlayerActivity)?.currentEpisodeIndex() ?: -1}"
        )
        cancelAutoHide()  // F2: 清理自动隐藏 Handler
        stopBufferUpdate()  // F1: 停止缓冲进度更新
        stopProgressMonitor()  // 阶段8 F10：停止进度监听
        // T1.8: 先释放嗅探资源（取消嗅探协程 + isReleased 标志位），再 releasePlayer（释放 mInternalPlayer）
        // 顺序很重要：先取消嗅探协程，避免 releasePlayer 后嗅探协程回调 setMediaItem 操作已 release 的 mInternalPlayer
        VideoPlay.videoManager.releaseSniffResources()
        releasePlayer()
        _playerView = null
        bottomProgressbar = null  // F1: 清理引用
        controlsLayer = null
        leftBottomContainer = null
        tvVideoTitle = null
        tvTitleFullscreen = null
        tvRouteSelector = null
        tvPositionIndicator = null
        gestureGuideCard = null
        rvEpisodes = null
        rightButtons = null
        btnStar = null
        btnSettings = null
        btnFullscreen = null
        btnDownload = null
        btnListen = null
        btnSpeed = null
        btnBackOverlay = null
        gestureDetector = null
        scaleGestureDetector = null
        isActivated = false
        super.onDestroyView()
    }

    // ==================== 播放器生命周期控制 ====================

    fun activatePlayer() {
        AppLog.put("VideoFragment.activatePlayer called: isActivated=$isActivated, _playerView=${_playerView != null}, singleUrl=${VideoPlay.singleUrl}")
        if (isActivated) return
        val pv = _playerView ?: return
        // video-booksource-align-rss AD-01：占位页机制删除（书源单页化后 position 恒 0，
        // 跨影片切换走 Activity 手势层 switchToBookFromList + VIDEO_BOOK_UNIT_SWITCHED 显式激活链）
        isActivated = true

        // 4.1 横屏视频检测：注册 onPrepared 回调获取视频尺寸
        pv.setVideoAllCallBack(object : GSYSampleCallBack() {
            override fun onPrepared(url: String?, vararg objects: Any?) {
                super.onPrepared(url, *objects)
                pv.post {
                    val videoWidth = pv.currentVideoWidth
                    val videoHeight = pv.currentVideoHeight
                    if (videoWidth > 0 && videoHeight > 0) {
                        // 更新 VideoPlay.isPortraitVideo（供 Activity 的 onConfigurationChanged 判断）
                        VideoPlay.isPortraitVideo =
                            videoHeight.toFloat() / videoWidth.toFloat() > 1.2f
                        // 4.3 全屏按钮显示逻辑：横屏视频显示全屏按钮
                        updateFullscreenButtonVisibility(videoWidth, videoHeight)
                    }
                    // Bug修复：GSY 在 setUp 时可能覆盖我们设置的 OnTouchListener
                    // 在 onPrepared 后重新注册，确保手势检测正常工作
                    reRegisterTouchListener()
                    // IF-05 兜底（真机 21:21 铁证）：订阅源续播走的是 `VideoPlay.startPlay`
                    // 的 `player.seekOnStart = durChapterPos`，它在 prepare **之后**才生效，
                    // 所以本回调里 `currentPosition` 仍是 0、上面的 PlayHistory 归零也拦不住。
                    // 延迟一拍再检查：起播位置已在片尾（距末尾 <2s）⇒ 回到 0 重新播放，
                    // 否则用户一进播放页就是 ENDED 黑屏且封面层吞掉上滑手势。
                    pv.postDelayed({
                        val endDur = maxOf(pv.duration.toLong(), VideoPlay.videoManager.duration)
                        val curPos = VideoPlay.videoManager.currentPosition.toLong()
                        if (shouldSnapToStart(curPos, endDur)) {
                            VideoTrace.event("resume_snap", "pos" to curPos, "dur" to endDur)
                            pv.seekTo(0)
                            pv.startAfterPrepared()
                        }
                    }, 400)
                    // 弹幕视图已在 setUp → initDanmaku 内初始化完成，此时同步开关入口
                    updateDanmakuButtonState()
                }
                // 阶段8 F10：视频准备就绪后启动进度监听（80%触发预缓冲）
                startProgressMonitor()
                // F1: 启动缓冲进度更新（GSY 不更新 secondaryProgress，需手动更新）
                startBufferUpdate()
                // F2: 控件显示 3 秒后自动隐藏（与 GSY 播放条行为一致）
                if (currentState == PlayState.NORMAL) {
                    pv.setGsyControlVisibility(true)  // F2 修复：显示 GSY 原始控件 + 取消 GSY 的 dismissControlViewTimer，用我们的 scheduleAutoHide 统一管理
                    scheduleAutoHide()
                }
                // AD-04: 恢复播放历史（跨会话进度恢复，仅新播放时触发，悬浮窗恢复走 clonePlayState）
                if (!VideoPlay.isResumeFromFloat) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val articleUrl = VideoPlay.rssArticles?.getOrNull(VideoPlay.rssArticleIndex)?.link ?: ""
                        // 4.8b（Z9）：恢复键与保存键一致（嗅探前原始 URL）
                        val videoUrl = VideoPlay.historyKeyUrl ?: return@launch
                        val history = PlayHistoryStore.load(articleUrl, videoUrl)
                        if (history != null && history.position > 10000) {
                            // IF-05 读取侧兜底：旧记录/异常退出可能把片尾位置写进来，
                            // 直接 seek 会导致「一进就 READY→ENDED」黑屏停在播放键。
                            // 距片尾 <2s 视为已看完 ⇒ 从 0 起播且不弹续播提示。
                            // duration 取 GSY 与 VideoManager 的较大者：onPrepared 回调时
                            // `pv.duration` 可能仍为 0（GSY 内部赋值晚于本回调），
                            // 只读它会导致「时长未知 ⇒ 不归零」，真机上仍 seek 到片尾。
                            val durMs = maxOf(pv.duration.toLong(), VideoPlay.videoManager.duration)
                            val resumePos = io.legado.app.model.resumePositionForRestore(
                                history.position.toInt(),
                                durMs
                            )
                            VideoTrace.event(
                                "resume_progress",
                                "pos" to history.position,
                                "dur" to durMs,
                                "chosen" to resumePos
                            )
                            if (resumePos > 0) {
                                pv.seekTo(resumePos.toLong())
                                val minutes = resumePos / 60000
                                val seconds = (resumePos % 60000) / 1000
                                appCtx.toastOnUi(
                                    getString(R.string.video_resume_from_format, minutes, seconds)
                                )
                            }
                        }
                    }
                }
            }

            // §3.3：沉浸式续播入口——与播放器自身 onAutoCompletion 共用统一去重（design §1.5），
            // 同一次结束只会推进一次，不会双跳
            override fun onAutoComplete(url: String?, vararg objects: Any?) {
                super.onAutoComplete(url, *objects)
                VideoTrace.event(
                    stage = "ended",
                    "entry" to "fragment",
                    "isBook" to (VideoPlay.book != null),
                    "rssIndex" to VideoPlay.rssEpisodeIndex,
                    "chapterInVolumeIndex" to VideoPlay.chapterInVolumeIndex
                )
                VideoPlay.advanceEpisode(pv, "fragment")
            }
        })

        // Bug修复：确保 GSY 的底部进度条可见
        pv.findViewById<View>(R.id.bottom_progressbar)?.visibility = View.VISIBLE

        // P0-1: 判断是从悬浮窗恢复还是新播放
        if (VideoPlay.isResumeFromFloat) {
            // 从悬浮窗恢复：克隆播放状态
            VideoPlay.isResumeFromFloat = false
            VideoPlay.clonePlayState(pv)
            pv.setSurfaceToPlay()
            pv.startAfterPrepared()
        } else {
            // 新播放
            val book = VideoPlay.book
            when {
                book != null -> VideoPlay.startPlay(pv)
                // 文章列表模式：上下滑动切换文章（video-article-swipe-switch spec）
                !VideoPlay.rssArticles.isNullOrEmpty() -> {
                    VideoPlay.switchToArticle(episodeIndex, pv)
                }
                // 集数列表模式（旧逻辑兼容）：上下滑动切换集数
                else -> {
                    val episodes = VideoPlay.rssEpisodes
                    val episode = episodes?.getOrNull(episodeIndex)
                    if (episode != null) {
                        VideoPlay.playRssEpisode(pv, episode)
                    } else {
                        VideoPlay.startPlay(pv)
                    }
                }
            }
        }
    }

    fun deactivatePlayer() {
        if (!isActivated) return
        isActivated = false
        cancelAutoHide()  // F2: 停止自动隐藏
        stopBufferUpdate()  // F1: 停止缓冲进度更新
        stopProgressMonitor()  // 阶段8 F10: 停止进度监听
        _playerView?.onVideoPause()
    }

    fun releasePlayer() {
        _playerView?.currentPlayer?.release()
    }

    /**
     * video-sniff-403-and-rss-classic-fix Phase 2 (3.3)：重试 ExoPlayer 播放
     *
     * 由错误对话框"重试"按钮调用（switchToWebViewMode/switchBackToExo 已随 WebView 播放器删除）。
     * 重置 isActivated 后重新激活。
     */
    fun retryExoPlayback() {
        val pv = _playerView ?: return
        pv.visibility = View.VISIBLE
        controlsLayer?.visibility = View.VISIBLE
        rightButtons?.visibility = View.VISIBLE
        // 重置激活标志，让 activatePlayer 重新走完整 ExoPlayer 设置流程
        isActivated = false
        activatePlayer()
        // video-player-image-enhance A1.3: 重试后重新应用画质增强滤镜
        //（由 activatePlayer→onPrepared 链路恢复，此处兜底立即应用）
        _playerView?.let { ImageEnhanceController.apply(it) }
        AppLog.put("retryExoPlayback: episode=$episodeIndex")
    }

    // ==================== 阶段8 F10：进度监听（预缓冲触发） ====================

    /**
     * 启动进度监听：每5秒轮询播放进度，达到80%时触发预缓冲下一文章
     * 只在文章列表模式下启动（rssArticles 不为空）
     */
    private fun startProgressMonitor() {
        // 只在文章列表模式启动
        if (VideoPlay.rssArticles.isNullOrEmpty()) return
        stopProgressMonitor()
        progressMonitorRunnable = object : Runnable {
            override fun run() {
                val currentPosition = VideoPlay.videoManager.currentPosition
                val duration = VideoPlay.videoManager.duration
                if (duration > 0 && currentPosition.toFloat() / duration >= 0.8f) {
                    VideoPlay.preloadNextArticleHtml(VideoPlay.rssArticleIndex)
                    // 触发一次后停止
                    progressMonitorRunnable = null
                    return
                }
                // 每5秒轮询
                progressMonitorHandler.postDelayed(this, 5000)
            }
        }
        progressMonitorHandler.postDelayed(progressMonitorRunnable!!, 5000)
    }

    /**
     * 停止进度监听
     */
    private fun stopProgressMonitor() {
        progressMonitorRunnable?.let { progressMonitorHandler.removeCallbacks(it) }
        progressMonitorRunnable = null
    }

    // ==================== F2: 控件自动隐藏（3秒无操作后隐藏） ====================

    /**
     * 启动自动隐藏计时（默认 3 秒后切换到 PURE 态）
     *
     * 用户需求变更：控件显示后过段时间自动隐藏，与 GSY 播放条/倍速行为一致。
     * - NORMAL 态：3 秒后切换到 PURE（隐藏控件）
     * - FULLSCREEN 态：3 秒后隐藏控件（controlsVisibleInFullscreen = false）
     */
    private fun scheduleAutoHide(delay: Long = 3000L) {
        autoHideHandler.removeCallbacks(autoHideRunnable)
        autoHideHandler.postDelayed(autoHideRunnable, delay)
    }

    /**
     * 取消自动隐藏计时（手动隐藏或控件已隐藏时调用）
     */
    private fun cancelAutoHide() {
        autoHideHandler.removeCallbacks(autoHideRunnable)
    }

    // ==================== F1: 缓冲进度更新 ====================

    /**
     * 启动缓冲进度更新（每 500ms 轮询 bufferedPercentage 更新 secondaryProgress）
     *
     * GSY 框架只更新 progress（播放进度），不更新 secondaryProgress（缓冲进度），
     * 需手动调用 ExoPlayerManager.getBufferedPercentage() 更新 secondaryProgress。
     *
     * 健壮性处理（修复预缓存进度条消失问题）：
     * - bottomProgressbar 可能在 onViewCreated 时为 null（GSY 控制器布局延迟加载）
     * - 每次 run 时尝试从 _playerView 重新获取引用
     * - 添加诊断日志确认 bufferedPercentage 返回值
     */
    private fun startBufferUpdate() {
        stopBufferUpdate()
        // 健壮性：onPrepared 时 GSY 控制器布局可能才加载，重新获取引用
        if (bottomProgressbar == null) {
            bottomProgressbar = _playerView?.findViewById(R.id.bottom_progressbar)
            AppLog.put("startBufferUpdate: re-acquire bottomProgressbar=${bottomProgressbar != null}")
        }
        bufferUpdateRunnable = object : Runnable {
            override fun run() {
                // 健壮性：每次 run 时确认 bp 引用（防止 onViewCreated 时为 null）
                var bp = bottomProgressbar
                if (bp == null) {
                    bp = _playerView?.findViewById(R.id.bottom_progressbar)
                    if (bp != null) {
                        bottomProgressbar = bp
                        AppLog.put("bufferUpdate: re-acquire bottomProgressbar in run")
                    } else {
                        // playerView 为 null 或控制器布局未加载，停止轮询
                        AppLog.put("bufferUpdate: bottomProgressbar still null, stop polling")
                        return
                    }
                }
                // AD-39：灰条展示「就绪进度」= max(内存桶, 磁盘预取游标)，让用户看到整集落盘进展
                // （原 bufferedPercentage 只反映内存桶，约数十秒，看起来像「缓冲一会就停」）
                val bar = bp ?: return
                val prev = bar.secondaryProgress
                readySecondaryProgress(VideoPlay.videoManager.readyPercentage())?.let { v ->
                    if (v != prev) {
                        // IF-16：正常应只进不退；出现倒退即记录一次（限流 20 条，供真机定位）
                        if (v < prev && bufferJumpLogCount < 20) {
                            bufferJumpLogCount++
                            AppLog.put(
                                "BufferJump: back prev=$prev new=$v max=${bar.max} prog=${bar.progress}"
                            )
                        }
                        bar.secondaryProgress = v
                    }
                }
                bufferUpdateHandler.postDelayed(this, 500)
            }
        }
        bufferUpdateHandler.postDelayed(bufferUpdateRunnable!!, 500)
    }

    /**
     * 停止缓冲进度更新
     */
    private fun stopBufferUpdate() {
        bufferUpdateRunnable?.let { bufferUpdateHandler.removeCallbacks(it) }
        bufferUpdateRunnable = null
    }

    /**
     * 4.4 + 4.7 + 4.8 全屏状态变化通知
     *
     * 由 Activity 的 toggleFullScreen() 调用。
     * 进入全屏：Activity 旋转横屏 → Fragment 更新状态+布局
     * 退出全屏：Activity 恢复竖屏 → Fragment 恢复状态+布局
     */
    fun onFullScreenChanged(isFullScreen: Boolean) {
        if (isFullScreen) {
            // 进入横屏全屏态
            currentState = PlayState.FULLSCREEN
            controlsVisibleInFullscreen = true
            applyState(PlayState.FULLSCREEN)
            // 4.8 横屏布局适配：全屏按钮始终可见（显示退出图标）
            btnFullscreen?.visible()
            updateFullscreenButtonIcon(true)
            // R10 / V6：横屏可视高度有限（7 个 48dp 按钮会超框）⇒ 隐藏次要入口，保留全屏/听视频/设置
            applySecondaryButtonsVisibility(show = false)
            // B1+ 修复：全屏模式下显示悬浮返回按钮（titleBarNew 已被 gone() 隐藏）
            btnBackOverlay?.visible()
            // video-player-ux-fixes P4: 全屏态标题移到左上角（返回按钮右侧），左下角标题隐藏
            //（线路/集数选择器仍在左下角容器内，不受影响）
            tvTitleFullscreen?.visible()
            tvVideoTitle?.gone()
            // video-player-image-enhance A1.3: 全屏切换可能重建渲染视图，重新应用画质增强滤镜
            _playerView?.let { ImageEnhanceController.apply(it) }
            scheduleAutoHide()  // F2: 显示控件后 3 秒自动隐藏
        } else {
            // 退出横屏全屏态
            currentState = PlayState.NORMAL
            controlsVisibleInFullscreen = true
            applyState(PlayState.NORMAL)
            updateFullscreenButtonIcon(false)
            // R10 / V6：退出全屏恢复次要入口（倍速/收藏/下载；弹幕由数据态单独控制）
            applySecondaryButtonsVisibility(show = true)
            // B1+ 修复：退出全屏时隐藏悬浮返回按钮（titleBarNew 恢复显示提供返回按钮）
            btnBackOverlay?.gone()
            // video-player-ux-fixes P4: 退出全屏恢复左下角标题，隐藏全屏态标题
            tvVideoTitle?.visible()
            tvTitleFullscreen?.gone()
            // video-player-image-enhance A1.3: 退出全屏重新应用画质增强滤镜
            _playerView?.let { ImageEnhanceController.apply(it) }
            // 恢复全屏按钮显示逻辑（仅横屏视频显示）
            val pv = _playerView
            if (pv != null && pv.currentVideoWidth > 0 && pv.currentVideoHeight > 0) {
                updateFullscreenButtonVisibility(pv.currentVideoWidth, pv.currentVideoHeight)
            }
            scheduleAutoHide()  // F2: 显示控件后 3 秒自动隐藏
        }
    }

    /**
     * 听视频模式开关（video-cache-and-multiep R10 / V6）。
     *
     * 开启：画面隐藏（alpha=0）但播放器继续播放 ⇒ 可锁屏/退后台继续听；
     *       Activity 侧同时持有 PARTIAL_WAKE_LOCK，避免息屏后 CPU 休眠断流。
     */
    private fun toggleListenMode() {
        val enable = !VideoPlay.listenMode
        VideoPlay.listenMode = enable
        applyListenModeUi(enable)
        (activity as? VideoPlayerActivity)?.onListenModeChanged(enable)
        toastOnUi(if (enable) R.string.video_listen_mode_on else R.string.video_listen_mode_off)
    }

    /** 应用听视频 UI：画面隐藏/恢复；隐藏态下若暂停则自动续播（听模式语义是"一直在播"） */
    private fun applyListenModeUi(enable: Boolean) {
        val pv = _playerView ?: return
        pv.alpha = if (enable) 0f else 1f
        btnListen?.alpha = if (enable) 0.55f else 1f
        if (enable && pv.currentState == GSYVideoView.CURRENT_STATE_PAUSE) {
            pv.onVideoResume()
        }
    }

    /**
     * 次要入口显隐（R10 / V6）：横屏全屏可视高度不足以容纳 7 个按钮，
     * 全屏时只保留「全屏 / 听视频 / 设置」三个核心入口。
     */
    private fun applySecondaryButtonsVisibility(show: Boolean) {
        val visibility = if (show && secondaryEntriesVisible(currentState == PlayState.FULLSCREEN)) {
            View.VISIBLE
        } else {
            View.GONE
        }
        btnSpeed?.visibility = visibility
        btnStar?.visibility = visibility
        btnDownload?.visibility = visibility
    }

    /**
     * 更新视频标题（由 Activity 在 VIDEO_SUB_TITLE 事件时调用）
     */
    fun updateVideoTitle(title: String) {
        setTitle(title)
    }

    /**
     * video-player-ux-fixes P4: 标题更新统一入口
     * 双控件同步：非全屏 tv_video_title（左下角）/ 全屏 tv_title_fullscreen（返回按钮右侧）
     * 所有标题赋值点必须走此方法，禁止直接操作单一控件（防双数据源分叉）
     */
    private fun setTitle(text: String?) {
        val value = text ?: ""
        tvVideoTitle?.text = value
        tvTitleFullscreen?.text = value
        // F181：标题与位置指示同源刷新（集数/文章切换都会经过 setTitle）
        updatePositionIndicator()
    }

    /**
     * F181：沉浸式播放位置感知「第X集 · 共Y集」（订阅源文章模式为「第X篇 · 共Y篇」）。
     *
     * 真值与传统布局信息区（`VideoPlayerActivity.showRssLegacyInfo`）**同源同串**（复用
     * `video_playlist_position_episode/_article`），避免双布局信息架构分叉；
     * 单集/单篇或非订阅源场景不显示（避免无信息量的噪声）。
     *
     * video-cache-and-multiep R7 / V4：多集时追加「 ▾」并成为选集弹框入口
     * （与传统模式「选集 · 第X集 · 共Y集 ▾」同一交互，见 `VideoPlayerActivity.showEpisodePicker`）。
     */
    private fun updatePositionIndicator() {
        val tv = tvPositionIndicator ?: return
        val book = VideoPlay.book
        val playlist = book?.let { VideoPlaylistHolder.positionOf(it.bookUrl) }
        val position = resolvePlaybackPosition(
            hasBook = book != null,
            rssArticleIndex = VideoPlay.rssArticleIndex,
            rssArticleCount = VideoPlay.rssArticles?.size ?: 0,
            bookPlaylistIndex = playlist?.first,
            bookPlaylistTotal = playlist?.second ?: 0,
            bookEpisodeIndex = VideoPlay.chapterInVolumeIndex,
            bookEpisodeCount = VideoPlay.episodes?.size ?: 0,
            rssEpisodeIndex = VideoPlay.rssEpisodeIndex,
            rssEpisodeCount = VideoPlay.rssEpisodes?.size ?: 0,
        )
        // 段由纯函数决定「显示哪些 / 哪段可点」；篇永远不可点，集才打开分组选集
        val segments = positionSegments(position)
        if (segments.isEmpty()) {
            tv.text = ""
            tv.movementMethod = null
            tv.isClickable = false
            tv.setOnClickListener(null)
            tv.gone()
            return
        }
        val labels = segments.map { seg ->
            when (seg.kind) {
                PositionSegmentKind.ARTICLE -> getString(
                    R.string.video_playlist_position_article,
                    seg.index + 1,
                    seg.total,
                )
                PositionSegmentKind.EPISODE ->
                    getString(
                        R.string.video_playlist_position_episode,
                        seg.index + 1,
                        seg.total,
                    ) + EPISODE_PICKER_ARROW_SUFFIX
            }
        }
        val text = labels.joinToString("  ")
        val spannable = SpannableString(text)
        var start = 0
        labels.forEachIndexed { i, segment ->
            if (segments[i].clickable) {
                spannable.setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            (activity as? VideoPlayerActivity)?.showEpisodePicker()
                        }

                        override fun updateDrawState(ds: TextPaint) {
                            super.updateDrawState(ds)
                            // 沿用位置指示的白色文字，不额外加下划线（保持悬浮层视觉一致）
                            ds.isUnderlineText = false
                            ds.color = tv.currentTextColor
                        }
                    },
                    start,
                    start + segment.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            start += segment.length + "  ".length
        }
        tv.text = spannable
        tv.movementMethod = LinkMovementMethod.getInstance()
        // 真机修复（2026-11-07，tasks §7.3）：movementMethod 只有触达 TextView 才能分发 ClickableSpan。
        // 原实现 isClickable=false 时触摸穿透到视频手势层 ⇒ 点「第X集 · 全Y集」只切换控件、打不开选集。
        // 现改为 clickable=true + linksClickable=true：整段文本接收触摸，非链接区（篇）点击不弹选集。
        tv.linksClickable = true
        tv.isClickable = true
        tv.setOnClickListener(null)
        tv.visible()
    }

    /**
     * F180：首次进入沉浸式播放时展示一次性手势引导卡。
     *
     * 沉浸式把交互全部收进手势，可发现性即功能开关——四条手势（双击暂停 / 长按倍速 /
     * 左右滑 seek / 双指外扩全屏）全部已有实现，本卡只做「把功能存在翻译成功能可用」；
     * 一次性（点「知道了」写 `PreferKey.videoGestureGuideShown`），不做二次弹出与常驻入口。
     */
    private fun maybeShowGestureGuide() {
        val card = gestureGuideCard ?: return
        if (getPrefBoolean(PreferKey.videoGestureGuideShown)) return
        card.findViewById<TextView>(R.id.tv_guide_title)?.setText(R.string.video_gesture_guide_title)
        val speed = VideoPlay.longPressSpeed / 10.0f
        val items = listOf(
            getString(R.string.video_gesture_guide_double_tap),
            getString(R.string.video_gesture_guide_long_press, speed.toString()),
            getString(R.string.video_gesture_guide_swipe),
            getString(R.string.video_gesture_guide_pinch)
        ).joinToString("\n")
        card.findViewById<TextView>(R.id.tv_guide_items)?.text = items
        card.findViewById<TextView>(R.id.btn_guide_confirm)
            ?.setText(R.string.video_gesture_guide_confirm)
        card.findViewById<TextView>(R.id.tv_guide_footnote)
            ?.setText(R.string.video_gesture_guide_footnote)
        card.findViewById<TextView>(R.id.btn_guide_confirm)?.setOnClickListener {
            putPrefBoolean(PreferKey.videoGestureGuideShown, true)
            card.gone()
        }
        // 引导期间手势照常穿透可用：卡本身只消费自身区域的点击（按钮），其余区域不拦截
        card.isClickable = false
        card.visible()
    }

    /**
     * 更新收藏按钮状态
     */
    fun updateStarState(isStarred: Boolean) {
        btnStar?.setImageResource(
            if (isStarred) R.drawable.ic_star else R.drawable.ic_star_border
        )
    }

    /**
     * 4.3 根据视频宽高比显示/隐藏全屏按钮
     *
     * 用户需求：横竖屏视频都展示全屏按钮。
     * 在 FULLSCREEN 状态下始终显示（用于退出全屏）。
     * 竖屏视频点击全屏按钮进入竖屏全屏（由 Activity 的 toggleFullScreen 根据 isPortraitVideo 判断方向）。
     */
    fun updateFullscreenButtonVisibility(videoWidth: Int, videoHeight: Int) {
        if (currentState == PlayState.FULLSCREEN) {
            // 4.8 全屏态下始终显示全屏按钮
            btnFullscreen?.visible()
            return
        }
        // 用户需求：横竖屏视频都展示全屏按钮
        btnFullscreen?.visible()
    }

    // ==================== 状态切换 ====================

    /**
     * 切换播放状态
     *
     * PURE ↔ NORMAL 双向切换，FULLSCREEN 由 Activity 控制
     */
    private fun switchState(newState: PlayState) {
        if (currentState == newState) return
        currentState = newState
        applyState(newState)
        // F2: 控件显隐由单击切换 + 3 秒自动隐藏（scheduleAutoHide 由调用方触发）
    }

    /**
     * 应用状态：控制控件显隐 + 动画
     */
    private fun applyState(state: PlayState) {
        when (state) {
            PlayState.PURE -> {
                // 纯净播放态：隐藏所有控件（带淡出动画）
                hideControlsAnimated()
                _playerView?.setGsyControlVisibility(false)  // F2 修复：同步隐藏 GSY 原始控件
            }
            PlayState.NORMAL -> {
                // 竖屏常态：显示所有控件（带淡入动画）
                showControlsAnimated()
                _playerView?.setGsyControlVisibility(true)  // F2 修复：同步显示 GSY 原始控件
                // P0 修复：显示控件后重新设置全屏按钮 visibility
                // 全屏按钮现恒显示（横竖屏一致），保留调用防止容器显隐后子控件 visibility 丢失
                val pv = _playerView
                if (pv != null && pv.currentVideoWidth > 0 && pv.currentVideoHeight > 0) {
                    updateFullscreenButtonVisibility(pv.currentVideoWidth, pv.currentVideoHeight)
                }
            }
            PlayState.FULLSCREEN -> {
                // 4.8 横屏全屏态：根据 controlsVisibleInFullscreen 决定显隐
                if (controlsVisibleInFullscreen) {
                    showControlsAnimated()
                    _playerView?.setGsyControlVisibility(true)  // F2 修复：同步显示 GSY 原始控件
                    // 全屏状态下确保全屏按钮可见（显示退出图标）
                    btnFullscreen?.visible()
                } else {
                    hideControlsAnimated()
                    _playerView?.setGsyControlVisibility(false)  // F2 修复：同步隐藏 GSY 原始控件
                }
                updateFullscreenButtonIcon(true)
            }
        }
    }

    /**
     * 带动画显示所有悬浮控件
     */
    private fun showControlsAnimated() {
        val controls = getOverlayControls()
        controls.forEach { view ->
            if (view.visibility == View.GONE || view.alpha == 0f) {
                view.visible()
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(300)
                    .start()
            }
        }
    }

    /**
     * 带动画隐藏所有悬浮控件
     */
    private fun hideControlsAnimated() {
        val controls = getOverlayControls()
        controls.forEach { view ->
            if (view.visibility == View.VISIBLE && view.alpha == 1f) {
                view.animate()
                    .alpha(0f)
                    .translationY(view.height.toFloat() * 0.1f)
                    .setDuration(300)
                    .withEndAction { view.gone() }
                    .start()
            }
        }
    }

    /**
     * 获取所有悬浮控件视图列表
     *
     * P0 修复：用 leftBottomContainer 作为一个整体参与显隐动画，
     * 避免子控件（线路/集数选择器等）被 hideControlsAnimated 隐藏后
     * 因 visibility=GONE 不被 getOverlayControls 收录而无法恢复。
     * 子控件自身的 visibility（如线路/集数选择器的 gone）不受容器显隐影响。
     *
     * U1 优化：btn_fullscreen 已移入 rightButtons 容器作为第一个按钮，
     * 随 rightButtons 整体参与显隐动画（3秒自动隐藏+单击重新显示）。
     * btn_fullscreen 自身的 visibility 仍由 updateFullscreenButtonVisibility 控制
     *（横竖屏视频均 visible / 全屏态始终 visible）。
     */
    private fun getOverlayControls(): List<View> {
        val list = mutableListOf<View>()
        // 左下角容器（包含标题、线路选择器、集数选择器）
        // video-sniff-403-and-rss-classic-fix Phase 2：WebView 模式已删除，恢复无条件参与自动隐藏
        leftBottomContainer?.let { list.add(it) }
        // 右侧功能按钮容器（U1：现在包含全屏按钮作为第一个子控件）
        rightButtons?.let { list.add(it) }
        // B1+ 修复 + video-player-ux-fixes P4：全屏专属控件（悬浮返回按钮/全屏态标题）参与 3 秒自动隐藏
        // 仅全屏态加入显隐组：showControlsAnimated 会无条件 visible GONE 控件，
        // 非全屏时若加入列表会被单击误显（防 P4 新增全屏标题放大 B1+ 存量隐患）
        if (currentState == PlayState.FULLSCREEN) {
            btnBackOverlay?.let { list.add(it) }
            tvTitleFullscreen?.let { list.add(it) }
        }
        return list
    }

    // ==================== 悬浮控件初始化 ====================

    private fun initOverlayControls(view: View) {
        controlsLayer = view.findViewById(R.id.controlsLayer)
        leftBottomContainer = view.findViewById(R.id.left_bottom_container)
        tvVideoTitle = view.findViewById(R.id.tv_video_title)
        // video-booksource-multiroute AD-06：视频书源模式标题可点开详情抽屉
        // （书源有 intro/coverUrl 详情数据；订阅源无此数据，不注入入口，UI 零退化）
        val detailSource = VideoPlay.source as? BookSource
        // video-source-dual-track AD-02：判定收口（静态源类型 OR 运行时 book.type）
        if (detailSource.isVideoSource(VideoPlay.book?.type)) {
            tvVideoTitle?.setOnClickListener {
                showVideoBookDetailSheet()
            }
        }
        // video-player-ux-fixes P4: 绑定全屏态标题
        tvTitleFullscreen = view.findViewById(R.id.tv_title_fullscreen)
        tvRouteSelector = view.findViewById(R.id.tv_route_selector)
        tvPositionIndicator = view.findViewById(R.id.tv_position_indicator)
        gestureGuideCard = view.findViewById(R.id.gesture_guide_card)
        rvEpisodes = view.findViewById(R.id.rv_episodes)
        rightButtons = view.findViewById(R.id.right_buttons)
        btnStar = view.findViewById(R.id.btn_star)
        btnSettings = view.findViewById(R.id.btn_settings)
        btnFullscreen = view.findViewById(R.id.btn_fullscreen)
        btnDownload = view.findViewById(R.id.btn_download)
        // R10 / V6：听视频模式（隐藏画面继续播，支持后台/锁屏续播）
        btnListen = view.findViewById(R.id.btn_listen)
        btnListen?.setOnClickListener { toggleListenMode() }
        // 每次进入播放器默认「看视频」（会话级内存态，不沿用上次听模式）
        applyListenModeUi(VideoPlay.listenMode)
        btnSpeed = view.findViewById(R.id.btn_speed)
        // R9 / V5：沉浸式倍速（底部上浮单排 5 档，当前档高亮）
        btnSpeed?.setOnClickListener {
            val pv = _playerView ?: return@setOnClickListener
            val dialog = ChoiceSpeedDialog(requireContext())
            dialog.initList(
                ChoiceSpeedDialog.SPEED_VALUES,
                object : ChoiceSpeedDialog.OnListItemClickListener {
                    override fun onItemClick(value: Float) {
                        pv.setVideoSpeed(value)
                    }

                    override fun finishDialog() = Unit
                },
                currentSpeed = pv.playSpeed
            )
            dialog.show()
        }
        // 弹幕开关：显隐由 updateDanmakuButtonState 按「本集是否有弹幕数据」决定（默认 gone）
        btnDanmaku = view.findViewById(R.id.btn_danmaku)
        btnDanmaku?.setOnClickListener {
            val pv = _playerView ?: return@setOnClickListener
            pv.toggleDanmakuShow()
            updateDanmakuButtonState()
        }
        // video-player-ux-fixes P1: 本地已下载视频（file:// 直连）无下载意义，隐藏下载按钮
        // （判定链：DownloadManageActivity 播放时 putExtra videoUrl=Uri.fromFile(file)，
        //   在线直链 http(s) 不受影响；Activity 生命周期内 videoUrl 不变，无需动态恢复）
        if (VideoPlay.videoUrl?.startsWith("file://") == true) {
            btnDownload?.gone()
        }
        // B1+ 修复：初始化全屏模式悬浮返回按钮
        btnBackOverlay = view.findViewById(R.id.btn_back_overlay)
        btnBackOverlay?.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        // 2.1 左下角视频标题（适配文章模式/集数模式/书源单页模式）
        // 书源单页化：episodeIndex 恒 0，当前集取 chapterInVolumeIndex（标题单一权威经 displayEpisodeTitle）
        val title = when {
            !VideoPlay.rssArticles.isNullOrEmpty() ->
                VideoPlay.rssArticles?.getOrNull(episodeIndex)?.title ?: VideoPlay.videoTitle ?: ""
            !VideoPlay.rssEpisodes.isNullOrEmpty() ->
                VideoPlay.rssEpisodes?.getOrNull(episodeIndex)?.title ?: VideoPlay.videoTitle ?: ""
            VideoPlay.book != null ->
                VideoPlay.displayEpisodeTitle(VideoPlay.episodes?.getOrNull(VideoPlay.chapterInVolumeIndex)?.title)
            else -> VideoPlay.videoTitle ?: ""
        }
        setTitle(title)

        // R3 REQ-17 线路选择器（多线路时显示，标题下方）
        initRouteSelector()

        // R3 REQ-18 集数选择器（多集时显示，线路下方横向滚动）
        initEpisodeSelector()

        // F181：位置指示初始化（后续由 setTitle 统一刷新）
        updatePositionIndicator()

        // F180：首次进入沉浸式播放的一次性手势引导卡
        maybeShowGestureGuide()

        // video-gesture-overhaul: 快退/快进按钮已移除，改为左右滑动 seek（R2/R3）

        // 2.4 收藏按钮
        updateStarButtonState()
        btnStar?.setOnClickListener {
            (activity as? VideoPlayerActivity)?.onFragmentStarClicked()
        }

        // 2.6 设置按钮 → 阶段5：打开综合设置面板（BottomSheet）
        btnSettings?.setOnClickListener {
            showSettingsPanel()
        }

        // 2.7 全屏按钮（4.4 点击切换全屏，4.7 退出全屏）
        btnFullscreen?.setOnClickListener {
            (activity as? VideoPlayerActivity)?.toggleFullScreen()
        }

        // 2.8 下载按钮（video-download-manager）：用当前已解析的播放地址 + 防盗链头 + 视频标题发起下载
        // 下载目标为 app 专属外部目录（无需 MANAGE_EXTERNAL_STORAGE 特殊权限），直接发起无需先申请权限
        //
        // video-cache-and-multiep R8 / V4：**书源视频改走缓存管理**（按集缓存，供离线播放），
        // 订阅源仍走既有下载链；当前集已缓存则隐藏入口（无重复缓存的意义）。
        val videoBook = VideoPlay.book
        val videoChapter = VideoPlay.episodes?.getOrNull(VideoPlay.chapterInVolumeIndex)
        val isBookVideo = videoBook != null && videoBook.isVideo
        if (isBookVideo && videoChapter != null &&
            LocalMediaResolver.hasLocal(videoBook, videoChapter)
        ) {
            btnDownload?.gone()
        }
        btnDownload?.setOnClickListener {
            if (isBookVideo && videoBook != null) {
                VideoCacheKernel.start(requireContext(), videoBook.bookUrl)
                toastOnUi(R.string.video_cache_started)
                return@setOnClickListener
            }
            val url = VideoPlay.videoUrl
            if (url.isNullOrBlank()) {
                toastOnUi(R.string.video_download_no_url)
                return@setOnClickListener
            }
            startDownload()
        }

        // 用户需求：初始状态为 NORMAL（控件显示）
        // F2: onPrepared 后启动 3 秒自动隐藏；左右滑动/单击可切换显隐
        currentState = PlayState.NORMAL
        applyState(PlayState.NORMAL)
    }

    // ==================== 下载（video-download-manager） ====================
    private fun startDownload() {
        val url = VideoPlay.videoUrl ?: return
        val title = VideoPlay.videoTitle ?: ""
        val taskType =
            if (url.contains(".m3u8", ignoreCase = true)) DownloadTaskType.HLS else DownloadTaskType.DIRECT
        // Phase 3 收口：播放现场头直接进任务创建链（DownloadService.addTask 以 toJsonHeaders 持久化，恢复/续传不丢头）
        Download.start(requireContext(), url, title, taskType, VideoPlay.currentPlayHeaders ?: emptyMap())
    }

    // ==================== 线路选择器（REQ-17） ====================

    /**
     * 初始化线路选择器
     * 多线路时显示，点击弹出 ModernActionPopup 选择线路
     */
    private fun initRouteSelector() {
        val routes = VideoPlay.rssRoutes
        if (routes == null || routes.size <= 1) {
            tvRouteSelector?.gone()
            AppLog.put("VideoRoutesDiag initRouteSelector: routes=${routes?.size ?: "null"} -> gone")
            return
        }
        tvRouteSelector?.visible()
        updateRouteSelectorText()
        AppLog.put("VideoRoutesDiag initRouteSelector: routes=${routes.size} -> visible")
        tvRouteSelector?.setOnClickListener { anchor ->
            showRouteSelector(anchor)
        }
    }

    /** 弹出线路选择菜单（ModernActionPopup 动态菜单，ui-theme-gap-audit G7） */
    private fun showRouteSelector(anchor: View) {
        val routes = VideoPlay.rssRoutes ?: return
        routeMenuPopup = ModernActionPopup.show(
            anchor,
            routes.mapIndexed { index, route ->
                ModernActionPopup.Action(
                    title = route.name,
                    checked = index == VideoPlay.rssRouteIndex
                ) {
                    switchRoute(index)
                }
            },
            routeMenuPopup
        )
    }

    /** 切换线路（新/旧模式统一入口） */
    private fun switchRoute(newIndex: Int) {
        if (newIndex == VideoPlay.rssRouteIndex) return
        if (VideoPlay.isNewRoutesMode()) {
            // 新模式：异步按需采集新线路集数（switchToRoute 内部处理播放+UI更新）
            val player = _playerView?.currentPlayer
            if (player != null && VideoPlay.switchToRoute(newIndex, player)) {
                updateRouteSelectorText()
            }
        } else {
            // 旧模式：内存切换
            val episode = VideoPlay.switchRssRoute(newIndex)
            if (episode != null) {
                updateRouteSelectorText()
                updatePositionIndicator()
                (activity as? VideoSettingsPanel.SettingsPanelCallback)?.onRouteChanged(episode)
            }
        }
    }

    /**
     * video-booksource-multiroute AD-06：打开视频书源详情抽屉
     * 动作源与悬浮选择器统一：切线路走 switchRoute，选集走 playRssEpisode（内部已分派书源章节链）
     */
    private fun showVideoBookDetailSheet() {
        VideoBookDetailSheet()
            .setCallback(object : VideoBookDetailSheet.Callback {
                override fun onDetailRouteSelected(routeIndex: Int) {
                    switchRoute(routeIndex)
                }

                override fun onDetailEpisodeSelected(episodeIndex: Int, episode: RssEpisode) {
                    val pv = _playerView ?: return
                    if (episodeIndex != VideoPlay.rssEpisodeIndex) {
                        VideoPlay.rssEpisodeIndex = episodeIndex
                    }
                    VideoPlay.playRssEpisode(pv, episode)
                    updatePositionIndicator()
                }
            })
            .show(childFragmentManager, "VideoBookDetailSheet")
    }

    private fun updateRouteSelectorText() {
        val routes = VideoPlay.rssRoutes ?: return
        val currentRoute = routes.getOrNull(VideoPlay.rssRouteIndex)
        val routeName = currentRoute?.name ?: getString(R.string.video_unknown_route)
        tvRouteSelector?.text = getString(R.string.video_route_format, routeName)
    }

    // ==================== 集数选择器（REQ-18） ====================

    /**
     * 集数入口（video-cache-and-multiep R7 / V4 收敛）。
     *
     * 原实现为「位置指示 + 横向集数条」上下相邻，真实机点击极易误触切集
     * （实测 y 轴仅差 ~10px）；现统一收敛为**左下角位置指示（含 `>`）+ 分组选集弹框**，
     * 横向集数条停用（`rv_episodes` 恒 gone，布局保留避免无用资源改动）。
     */
    private fun initEpisodeSelector() {
        rvEpisodes?.gone()
        AppLog.put("VideoRoutesDiag initEpisodeSelector: retired（改用分组选集弹框）")
    }

    /**
     * 文章切换后更新集数/线路选择器（video-article-swipe-switch spec）
     *
     * 切换文章后，VideoPlay.rssEpisodes/rssRoutes 已更新为新文章的数据。
     * 由 VideoPlayerActivity 在 UP_VIDEO_INFO 事件中调用 currentFragment.updateEpisodeSelector()。
     */
    fun updateEpisodeSelector() {
        // R7 / V4：集数入口由左下角位置指示 + 分组弹框承担（横向集数条已停用）
        rvEpisodes?.gone()
        // 更新线路选择器
        val routes = VideoPlay.rssRoutes
        if (routes == null || routes.size <= 1) {
            tvRouteSelector?.gone()
        } else {
            tvRouteSelector?.visible()
            updateRouteSelectorText()
            // 重新绑定点击事件（线路列表已变化）
            tvRouteSelector?.setOnClickListener { anchor ->
                showRouteSelector(anchor)
            }
        }
        AppLog.put("VideoRoutesDiag updateEpisodeSelector: routes=${routes?.size ?: "null"}, episodes=${VideoPlay.rssEpisodes?.size ?: "null"}")
        // 更新标题（P4: 双控件统一同步；含位置指示刷新）
        setTitle(VideoPlay.videoTitle)
    }

    // video-gesture-overhaul: 快退/快进按钮已移除，改为左右滑动 seek（R2/R3）

    // ==================== 手势检测（单击切换显隐 + 双指缩放 + 长按倍速 + 双击暂停） ====================

    @SuppressLint("ClickableViewAccessibility")
    private fun initGestureDetector(rootView: View) {
        gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                when (currentState) {
                    PlayState.PURE -> {
                        switchState(PlayState.NORMAL)
                        scheduleAutoHide()  // F2: 显示后 3 秒自动隐藏
                    }
                    PlayState.NORMAL -> {
                        switchState(PlayState.PURE)
                        cancelAutoHide()  // F2: 手动隐藏，取消计时
                    }
                    PlayState.FULLSCREEN -> {
                        // 4.7 横屏全屏态：单击切换控件显隐（不退出全屏）
                        controlsVisibleInFullscreen = !controlsVisibleInFullscreen
                        if (controlsVisibleInFullscreen) {
                            showControlsAnimated()
                            _playerView?.setGsyControlVisibility(true)  // F2 修复：同步显示 GSY 原始控件
                            btnFullscreen?.visible()
                            updateFullscreenButtonIcon(true)
                            scheduleAutoHide()  // F2: 显示后 3 秒自动隐藏
                        } else {
                            hideControlsAnimated()
                            _playerView?.setGsyControlVisibility(false)  // F2 修复：同步隐藏 GSY 原始控件
                            cancelAutoHide()  // F2: 手动隐藏，取消计时
                        }
                    }
                }
                return true
            }

            // video-gesture-overhaul R1: 长按倍速播放（onLongPress 触发，ACTION_UP 恢复原速）
            // 根因：R3 重构替换 surface_container OnTouchListener 后，GSY 内部 onLongPress 收不到事件。
            // 在 VideoFragment 的 GestureDetector 中重新实现，复用 VideoPlayer 的 setVideoSpeed/showOverlayTip。
            override fun onLongPress(e: MotionEvent) {
                val pv = _playerView ?: return
                if (pv.currentState == GSYVideoView.CURRENT_STATE_PLAYING) {
                    val speed = VideoPlay.longPressSpeed / 10.0f
                    pv.setVideoSpeed(speed)
                    pv.showOverlayTip("${speed}倍速播放中")
                    isLongPressSpeed = true
                }
            }

            // video-gesture-overhaul R4: 双击暂停/播放（根因同 R1，GSY 内部 onDoubleTap 失效）
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val pv = _playerView ?: return false
                when (pv.currentState) {
                    GSYVideoView.CURRENT_STATE_PLAYING -> pv.onVideoPause()
                    GSYVideoView.CURRENT_STATE_PAUSE -> pv.startAfterPrepared()
                }
                return true
            }

            // video-booksource-align-rss AD-02/AD-05：垂直滑动由手势层驱动 Activity 分派
            // （ViewPager 已禁滑的场景：书源单页 + §12.6 RSS SERIES）。
            // 2026-11-07 真机修复：原条件限定 `book != null`，导致 RSS SERIES 禁翻页后
            // 上滑既不被 ViewPager 消费、也不进入此分支 ⇒ 完全无响应（零日志）。
            // 现改为与 ViewPager 输入能力严格互补：谁禁滑谁接管。
            // 左右滑动 seek（video-gesture-overhaul R2）由方向锁定互斥，不受影响
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val activity = activity as? VideoPlayerActivity
                val handlesVertical = activity?.shouldHandleVerticalFling() == true
                if (kotlin.math.abs(velocityY) > 200f) {
                    // 诊断：区分「手势层未接管」与「速度/方向未达阈值」两类无响应
                    VideoTrace.event(
                        "gesture_fling",
                        "handled" to handlesVertical,
                        "vx" to velocityX.toInt(),
                        "vy" to velocityY.toInt()
                    )
                }
                if (handlesVertical) {
                    if (kotlin.math.abs(velocityY) > kotlin.math.abs(velocityX) &&
                        kotlin.math.abs(velocityY) > FLING_MIN_VELOCITY
                    ) {
                        activity.onBookVerticalFling(velocityY)
                        return true
                    }
                }
                return super.onFling(e1, e2, velocityX, velocityY)
            }
        })

        // 4.5 双指缩放手势检测：双指向外拉伸（scaleFactor > 1.2）触发全屏
        scaleGestureDetector = ScaleGestureDetector(
            requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    // 4.6 双指拉伸触发全屏（仅非全屏状态下触发）
                    if (detector.scaleFactor > 1.2f && currentState != PlayState.FULLSCREEN) {
                        (activity as? VideoPlayerActivity)?.toggleFullScreen()
                    }
                }
            }
        )

        // Bug修复（F2 触摸事件不到达根因）：
        // GSY 在 GSYVideoControlView.init() 中对 mTextureViewContainer（即 R.id.surface_container，
        // 全屏 RelativeLayout）同时调用 setOnClickListener(this) 和 setOnTouchListener(this)。
        // 因此触摸事件被 surface_container 直接消费，playerView 的 OnTouchListener 永远不触发。
        // 修复：将 OnTouchListener 设到 surface_container 上（GSY 实际接收触摸的视图），
        // 替换 GSY 的 OnTouchListener，由我们统一处理手势（单击切换+双指缩放+双指滑动+文章切换）。
        // controlsLayer 在 playerView 之上，其按钮（ImageButton）仍可正常接收触摸（buttons 是 clickable）；
        // controlsLayer 的非按钮区域（clickable=false）穿透到 playerView → surface_container → 我们的 listener。
        // 注意：GSY 每次 setUp/startPlayLogic 都会重新覆盖该 listener，故 onPrepared 时必须重新安装
        // （`reRegisterTouchListener`）；否则第 2 集起手势层整体失效（2026-11-07 真机：SERIES 上滑零日志）。
        installGestureTouchListener()

        // video-sniff-403-and-rss-classic-fix Phase 2：原 P0-1.6 WebView 模式触摸事件说明段已随
        // WebView 播放器删除而失效清理（WebViewVideoPlayer.onInterceptTouchEvent 不复存在）
    }

    /**
     * 安装手势触摸监听（幂等）。
     *
     * 抽成独立方法的原因：GSY 在 `init()/setUp()` 里会把 surface_container 的 OnTouchListener
     * 覆盖回它自己 ⇒ 必须在每次 `onPrepared` 后（重新）安装，否则手势层只在首集有效。
     *
     * 诊断埋点 `stage=gesture_touch`：用于确认触摸是否到达本层（区分「被 GSY 覆盖」与
     * 「onFling 条件不满足」两类无响应，测试包日志是唯一可观测证据）。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun installGestureTouchListener() {
        val touchTarget: View? = _playerView?.findViewById(R.id.surface_container) ?: _playerView
        touchTarget?.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                touchEventCount++
                if (touchEventCount <= 3 || touchEventCount % 20 == 0) {
                    VideoTrace.event("gesture_touch", "count" to touchEventCount)
                }
            }
            // 检查触摸点是否在按钮区域内（按钮区域由控件自己处理）
            val x = event.rawX.toInt()
            val y = event.rawY.toInt()
            val onControls = isTouchOnControls(x, y)
            val isArticleMode = !VideoPlay.rssArticles.isNullOrEmpty()
                && VideoPlay.rssArticles!!.size > 1
            if (!onControls) {
                // 文章模式（上下滑动切换文章）：单指垂直滑动交给 ViewPager2 拦截
                // 非文章模式（集数模式/单URL）：双指事件由我们消费，单指事件交给 GSY
                if (isArticleMode) {
                    handleArticleModeTouchEvent(event)
                } else {
                    handlePlayerTouchEvent(event)
                }
                // 始终消费事件（返回 true），阻止 GSY 的 onClick（onClickBlank 回调）和
                // surface_container.onTouchEvent 触发。R3 沉浸竖滑风格不使用 GSY 的亮度/音量/进度滑动手势。
                true
            } else {
                false // 控件区域内不消费（让按钮处理；实际按钮在 controlsLayer 上层已消费）
            }
        }
    }

    /**
     * 处理播放器触摸事件（公共方法）
     *
     * 包含：手势检测（单击切换）+ 双指缩放（触发全屏）+ 双指左右滑动（隐藏控件）
     * initGestureDetector 和 reRegisterTouchListener 都调用此方法，
     * 确保 GSY 覆盖 OnTouchListener 后重新注册时不丢失双指滑动检测。
     *
     * @return true 表示消费事件（双指事件），阻止 GSY 拦截多指手势
     *         false 表示不消费（单指事件），交给 GSY 处理（进度条/亮度/音量）
     */
    private fun handlePlayerTouchEvent(event: MotionEvent): Boolean {
        gestureDetector?.onTouchEvent(event)
        scaleGestureDetector?.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // video-gesture-overhaul R2: 记录左右滑动 seek 起点 + 方向判定基准
                slideSeekStartX = event.x
                singleFingerStartY = event.y
                isSeeking = false
                // 修复 seek 预览漂移: ACTION_DOWN 时记录播放位置，避免滑动中 currentPosition 持续变化导致 seekTarget 漂移
                slideSeekStartPos = VideoPlay.videoManager.currentPosition
                slideSeekStartUrl = VideoPlay.videoUrl
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    twoFingerStartX1 = event.getX(0)
                    twoFingerStartX2 = event.getX(1)
                    isTwoFingerSwipe = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && isTwoFingerSwipe && currentState == PlayState.NORMAL) {
                    // 双指左右滑动检测：隐藏控件到 PURE 态
                    val dx1 = event.getX(0) - twoFingerStartX1
                    val dx2 = event.getX(1) - twoFingerStartX2
                    val threshold = 100f
                    if (kotlin.math.abs(dx1) > threshold && kotlin.math.abs(dx2) > threshold
                        && (dx1 > 0) == (dx2 > 0)
                    ) {
                        switchState(PlayState.PURE)
                        cancelAutoHide()  // F2: 已隐藏控件，取消自动隐藏计时
                        isTwoFingerSwipe = false
                    }
                } else if (event.pointerCount == 1 && !isLongPressSpeed) {
                    // video-gesture-overhaul R2: 单指左右滑动 seek（非文章模式）
                    handleSlideSeekMove(event)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                isTwoFingerSwipe = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // video-gesture-overhaul R1: 长按倍速松手恢复原速
                handleLongPressSpeedRelease()
                // video-gesture-overhaul R2: 执行左右滑动 seek
                handleSlideSeekRelease()
            }
        }

        // 双指事件消费（阻止 GSY 拦截多指手势）
        // video-gesture-overhaul R2: seek 期间也消费（阻止 GSY 进度条冲突）
        return event.pointerCount >= 2 || isSeeking
    }

    /**
     * 文章模式下的触摸事件处理（上下滑动切换文章）
     *
     * 文章模式下需要区分单指滑动方向：
     * - 垂直滑动 → 交给 ViewPager2 拦截，切换上/下一篇文章
     * - 水平滑动 → 交给 GSY 处理（进度条拖动）
     * - 双指缩放 → 触发全屏（复用 handlePlayerTouchEvent 的双指逻辑）
     * - 双指左右滑动 → 隐藏控件到 PURE 态
     * - 单击 → 切换控件显隐
     *
     * 实现原理：
     * GSY 在 ACTION_DOWN 时会调用 parent.requestDisallowInterceptTouchEvent(true)
     * 阻止 ViewPager2 拦截。我们在检测到垂直滑动时：
     * 1. 调用 parent.requestDisallowInterceptTouchEvent(false) 恢复 ViewPager2 拦截能力
     * 2. 返回 true 消费当前事件，阻止 GSY 的 onTouchEvent 被调用
     *    （否则 GSY 会再次调用 requestDisallowInterceptTouchEvent(true) 覆盖）
     * 3. 下一个 ACTION_MOVE 事件 ViewPager2 的 onInterceptTouchEvent 被调用，
     *    检测到垂直滑动后拦截，接管后续事件完成文章切换
     *
     * @return true 消费事件（双指事件 + 垂直滑动），false 不消费（水平滑动交给 GSY）
     */
    private fun handleArticleModeTouchEvent(event: MotionEvent): Boolean {
        gestureDetector?.onTouchEvent(event)
        scaleGestureDetector?.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                singleFingerStartX = event.x
                singleFingerStartY = event.y
                // video-gesture-overhaul R2: 记录 seek 起点（与 singleFingerStartX 一致）
                slideSeekStartX = event.x
                isVerticalSwipe = false
                isSeeking = false
                // 修复 seek 预览漂移: ACTION_DOWN 时记录播放位置，避免滑动中 currentPosition 持续变化导致 seekTarget 漂移
                slideSeekStartPos = VideoPlay.videoManager.currentPosition
                slideSeekStartUrl = VideoPlay.videoUrl
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && isTwoFingerSwipe && currentState == PlayState.NORMAL) {
                    // 双指左右滑动检测：隐藏控件到 PURE 态
                    val dx1 = event.getX(0) - twoFingerStartX1
                    val dx2 = event.getX(1) - twoFingerStartX2
                    val threshold = 100f
                    if (kotlin.math.abs(dx1) > threshold && kotlin.math.abs(dx2) > threshold
                        && (dx1 > 0) == (dx2 > 0)
                    ) {
                        switchState(PlayState.PURE)
                        cancelAutoHide()  // F2: 已隐藏控件，取消自动隐藏计时
                        isTwoFingerSwipe = false
                    }
                } else if (event.pointerCount == 1) {
                    val dx = event.x - singleFingerStartX
                    val dy = event.y - singleFingerStartY
                    // AD-05: 首次判定滑动方向——垂直滑动优先交给 ViewPager2（上下滑动切文章完全保留）
                    if (!isVerticalSwipe && !isSeeking && kotlin.math.abs(dy) > kotlin.math.abs(dx)
                        && kotlin.math.abs(dy) > 30f
                    ) {
                        isVerticalSwipe = true
                    }
                    if (isVerticalSwipe) {
                        // 垂直滑动：ViewPager 可翻页时恢复其拦截能力（交回翻页）；
                        // §12.6 若 ViewPager 已禁滑（SERIES/书源单页），保持消费并由
                        // GestureDetector 在 ACTION_UP 计算 fling ⇒ onFling 分派切集。
                        val pagerTakesVertical =
                            (activity as? VideoPlayerActivity)?.shouldHandleVerticalFling() != true
                        if (pagerTakesVertical) {
                            _playerView?.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        return true
                    }
                    // video-gesture-overhaul R2: 水平滑动判定为 seek（方向锁定，与垂直滑动互斥）
                    if (!isSeeking && !isLongPressSpeed && kotlin.math.abs(dx) > kotlin.math.abs(dy)
                        && kotlin.math.abs(dx) > 30f
                    ) {
                        isSeeking = true
                    }
                    if (isSeeking && !isLongPressSpeed) {
                        handleSlideSeekMove(event)
                        return true  // 消费事件，阻止 GSY 处理水平滑动
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    twoFingerStartX1 = event.getX(0)
                    twoFingerStartX2 = event.getX(1)
                    isTwoFingerSwipe = true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                isTwoFingerSwipe = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isVerticalSwipe = false
                // video-gesture-overhaul R1: 长按倍速松手恢复原速
                handleLongPressSpeedRelease()
                // video-gesture-overhaul R2: 执行左右滑动 seek
                handleSlideSeekRelease()
            }
        }

        // 双指事件消费 + seek 期间消费（阻止 GSY 拦截）
        // AD-05: 垂直滑动已 return true 消费，水平滑动未达阈值时返回 false 交给 GSY
        return event.pointerCount >= 2 || isSeeking
    }

    // ==================== video-gesture-overhaul: 左右滑动 seek + 长按倍速辅助方法 ====================

    /**
     * R2: 处理左右滑动 seek 的 ACTION_MOVE 逻辑
     *
     * 方向判定锁定机制（AD-05）：
     * - 首次判定为水平（|dx|>|dy| 且 |dx|>30）后，设置 isSeeking=true
     * - 一旦锁定为 seek，本次触摸序列内不再改变（即使手指轨迹偏移）
     * - 只有 ACTION_UP/ACTION_CANCEL 才重置 isSeeking
     * - 这确保了上下滑动切文章和左右滑动 seek 互不干扰
     *
     * seek 量计算（AD-02）：seek量 = (dx / screenWidth) × duration
     * - 滑动全屏宽度 ≈ 跳转整个视频时长，与主流播放器一致
     * - 非固定 60 秒，符合用户"跟常规视频播放器一样"的要求
     */
    private fun handleSlideSeekMove(event: MotionEvent) {
        val pv = _playerView ?: return
        val dx = event.x - slideSeekStartX
        // 首次方向判定：水平滑动（|dx|>|dy| 且 |dx|>30）锁定为 seek
        if (!isSeeking) {
            val dy = event.y - singleFingerStartY
            if (kotlin.math.abs(dx) > 30f && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                isSeeking = true
            } else {
                return  // 未达阈值或不满足方向判定，不处理
            }
        }
        // 持续更新 seek 预览
        val screenWidth = resources.displayMetrics.widthPixels
        val duration = VideoPlay.videoManager.duration
        // 边界修复: 视频未准备好时 duration<=0，重置 isSeeking 避免 ACTION_UP 执行无效 seekTo
        if (duration <= 0) {
            isSeeking = false
            return
        }
        val ratio = dx / screenWidth  // -1.0 ~ 1.0（左滑负/右滑正）
        // video-player-ux-fixes P2: 乘以用户可配置的滑动灵敏度（默认 10=1.0x 保持原行为）
        val offset = (ratio * (VideoPlay.seekSensitivity / 10f) * duration).toLong()
        // 修复 seek 预览漂移: 基于 ACTION_DOWN 时记录的 slideSeekStartPos 计算，而非实时 currentPosition
        // 原因: currentPosition 在视频播放过程中持续增长，滑动过程中用它做基准会导致 seekTarget 不断漂移
        seekTarget = (slideSeekStartPos + offset).coerceIn(0, duration)
        val targetSec = seekTarget / 1000
        val arrow = if (dx >= 0) "→" else "←"
        pv.showOverlayTip("$arrow ${formatSeekTime(targetSec)}")
    }

    /**
     * R2: ACTION_UP 中执行 seek（左右滑动结束）
     * 重置 isSeeking 标志，隐藏预览提示
     */
    private fun handleSlideSeekRelease() {
        if (isSeeking) {
            val pv = _playerView
            // 切集竞态：按下与抬起之间已换集（播完自动续播/手动选集），本次 seek 目标属于旧集，
            // 直接丢弃；否则会把旧集进度套到新集并连锁触发「又播完」
            val sameEpisode = slideSeekStartUrl == VideoPlay.videoUrl
            if (pv != null && seekTarget >= 0 && sameEpisode) {
                pv.currentPlayer.seekTo(seekTarget)
            }
            isSeeking = false
            pv?.showOverlayTip()  // 隐藏预览提示
        }
    }

    /**
     * R1: ACTION_UP 中恢复长按倍速到原速
     * onLongPress 没有"松手"回调，通过 ACTION_UP 配合恢复（AD-03）
     */
    private fun handleLongPressSpeedRelease() {
        if (isLongPressSpeed) {
            isLongPressSpeed = false
            val pv = _playerView
            val originalSpeed = pv?.playSpeed ?: 1.0f
            pv?.setVideoSpeed(originalSpeed)
            pv?.showOverlayTip()  // 隐藏倍速提示
        }
    }

    /**
     * 格式化 seek 预览时间（mm:ss 或 hh:mm:ss）
     */
    private fun formatSeekTime(seconds: Long): String {
        if (seconds < 0) return "00:00"
        val totalSec = seconds.toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    /**
     * 检查触摸点是否落在悬浮控件（按钮/标题）区域内
     */
    private fun isTouchOnControls(x: Int, y: Int): Boolean {
        // 检查右侧按钮容器
        rightButtons?.let {
            val loc = IntArray(2)
            it.getLocationOnScreen(loc)
            if (x >= loc[0] && x <= loc[0] + it.width && y >= loc[1] && y <= loc[1] + it.height) {
                return true
            }
        }
        // 检查左下角容器（标题+线路选择器+集数选择器；全屏按钮已迁入右侧按钮容器，由上方检查覆盖）
        leftBottomContainer?.let {
            if (it.visibility == View.VISIBLE) {
                val loc = IntArray(2)
                it.getLocationOnScreen(loc)
                if (x >= loc[0] && x <= loc[0] + it.width && y >= loc[1] && y <= loc[1] + it.height) {
                    return true
                }
            }
        }
        return false
    }

    // ==================== 按钮状态更新 ====================

    /**
     * 弹幕开关入口状态同步（沉浸式悬浮层）
     *
     * 可用性取自 `VideoPlayer.isDanmakuAvailable`：本集无弹幕数据时 `initDanmaku()` 早退、
     * DanmakuView 不创建 → 入口保持 gone（与传统模式 `toggle_danmaku` 置 GONE 的口径一致）。
     * 有弹幕时展示入口，并按全局 `danmakuShow` 切换图标与无障碍描述。
     *
     * 同时隐藏 GSY 原始控制条中的 `toggle_danmaku`：沉浸式以自定义悬浮层为唯一弹幕入口，
     * 否则点击视频调出 GSY 控制条时会出现两个弹幕开关（重复入口）。
     */
    private fun updateDanmakuButtonState() {
        val pv = _playerView
        pv?.findViewById<TextView>(R.id.toggle_danmaku)?.gone()
        if (pv == null || !pv.isDanmakuAvailable) {
            btnDanmaku?.gone()
            return
        }
        val showing = pv.isDanmakuShowing
        btnDanmaku?.visible()
        btnDanmaku?.setImageResource(
            if (showing) R.drawable.ic_danmaku else R.drawable.ic_danmaku_off
        )
        btnDanmaku?.contentDescription = getString(
            if (showing) R.string.video_danmaku_off else R.string.video_danmaku_on
        )
    }

    private fun updateStarButtonState() {
        val isStarred = VideoPlay.rssStar != null
        btnStar?.setImageResource(
            if (isStarred) R.drawable.ic_star else R.drawable.ic_star_border
        )
        // video-live-favorite-fix AD-03：可见性与顶栏同源（`VideoPlay.canFavoriteCurrent()`）——
        // 旧口径 `book == null && !singleUrl` 与顶栏 `rssStar/rssRecord != null` 不一致，已收敛。
        if (VideoPlay.canFavoriteCurrent()) {
            btnStar?.visible()
        } else {
            btnStar?.gone()
        }
    }

    /**
     * 4.8 更新全屏按钮图标
     *
     * 全屏中显示"退出全屏"图标，非全屏显示"进入全屏"图标
     */
    private fun updateFullscreenButtonIcon(isInFullScreen: Boolean) {
        btnFullscreen?.setImageResource(
            if (isInFullScreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        )
    }

    /**
     * 选集切集后的「确保在播」（重试轮询用）。
     *
     * 与 [restartCurrentEpisode] 的区别：准备中/播放中不重复 setUp，只在真正停住（NORMAL/ERROR）
     * 或暂停态时才重启/恢复，避免一次切集触发多次重建。
     */
    internal fun ensurePlaying(): Boolean {
        val pv = _playerView ?: return false
        return when (pv.currentState) {
            GSYVideoView.CURRENT_STATE_PLAYING -> true
            GSYVideoView.CURRENT_STATE_PAUSE -> {
                pv.startAfterPrepared()
                true
            }
            GSYVideoView.CURRENT_STATE_NORMAL, GSYVideoView.CURRENT_STATE_ERROR -> {
                restartCurrentEpisode()
            }
            // 其余状态（准备中/缓冲中/自动完成）已处于播放管线内，重复 setUp 反而更慢
            else -> true
        }
    }

    /**
     * 按 `VideoPlay` 当前索引重启沉浸式播放器（选集切集专用）。
     *
     * 沉浸式布局的活跃播放器在本 Fragment 内，Activity 持有的 `playerView` 不是它；
     * 选集弹框切集后必须由这里重走起播入口（与首次 activatePlayer 同一路径），否则黑屏停在播放键。
     */
    internal fun restartCurrentEpisode(): Boolean {
        val pv = _playerView ?: return false
        val book = VideoPlay.book
        when {
            book != null -> VideoPlay.startPlay(pv)
            !VideoPlay.rssEpisodes.isNullOrEmpty() -> {
                val episode = VideoPlay.rssEpisodes?.getOrNull(VideoPlay.rssEpisodeIndex) ?: return false
                VideoPlay.playRssEpisode(pv, episode)
            }
            else -> VideoPlay.startPlay(pv)
        }
        return true
    }

    /**
     * 重新安装触摸监听（GSY setUp 后必调）
     *
     * GSY 的 setUp/startPlayLogic 会覆盖我们在 surface_container 上设置的 OnTouchListener。
     * 因此**每次** onPrepared 都要重新安装（不能只在首集装一次）——否则第 2 集起手势层
     * 整体失效，表现为「上滑/单击/双击全部无响应」且日志只有 GSY 自己的行为。
     */
    private fun reRegisterTouchListener() {
        installGestureTouchListener()
    }

    /**
     * 阶段5：打开综合设置面板（BottomSheet）
     *
     * 注入播放器引用和回调给面板，面板通过回调委托 Activity 处理菜单功能。
     * Activity 保存面板引用，用于 VIDEO_PLAY_ERROR 事件同步调试日志。
     */
    private fun showSettingsPanel() {
        val activity = activity ?: return
        val panel = VideoSettingsPanel.newInstance()
        panel.playerView = _playerView
        panel.callback = activity as? VideoSettingsPanel.SettingsPanelCallback
        // 通知 Activity 保存面板引用（用于调试日志同步）
        (activity as? VideoPlayerActivity)?.settingsPanel = panel
        panel.show(activity.supportFragmentManager, VideoSettingsPanel.TAG)
    }

    companion object {
        private const val ARG_EPISODE_INDEX = "episode_index"

        /** video-booksource-align-rss AD-02：垂直 fling 最小速度阈值（px/s），低于此值不触发列表切换 */
        private const val FLING_MIN_VELOCITY = 1200f

        /** R7 / V4：多集时左下角位置指示追加的可点击提示（用 ASCII `>` 避免 ▾ 字形缺失） */
        private const val EPISODE_PICKER_ARROW_SUFFIX = " >"

        fun newInstance(index: Int): VideoFragment {
            return VideoFragment().apply {
                arguments = Bundle().apply { putInt(ARG_EPISODE_INDEX, index) }
            }
        }
    }
}
