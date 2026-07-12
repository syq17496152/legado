package io.legado.app.ui.video

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shuyu.gsyvideoplayer.listener.GSYSampleCallBack
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.RssEpisode
import io.legado.app.help.gsyVideo.VideoPlayer
import io.legado.app.model.VideoPlay
import io.legado.app.utils.gone
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible

/**
 * R3 抖音风格视频播放 Fragment
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
 * - 横屏视频自动显示全屏按钮
 */
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

    // P0-1.6: WebView 降级播放相关（ExoPlayer 失败时降级到 WebView，复用 skill V2 模板）
    private var webViewPlayer: WebViewVideoPlayer? = null
    private var btnSwitchBack: ImageButton? = null
    /** 当前是否处于 WebView 播放模式（影响 activatePlayer/deactivatePlayer 行为） */
    private var isWebViewMode = false

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

    /** 标记是否需要在 activatePlayer 后重新注册触摸监听 */
    private var needReRegisterTouchListener = true

    // ==================== 悬浮控件视图引用 ====================

    private var controlsLayer: ConstraintLayout? = null
    private var leftBottomContainer: LinearLayout? = null
    private var tvVideoTitle: TextView? = null
    private var tvRouteSelector: TextView? = null
    private var rvEpisodes: RecyclerView? = null
    private var rightButtons: LinearLayout? = null
    private var btnRewind: ImageButton? = null
    private var btnStar: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private var btnForward: ImageButton? = null
    private var btnFullscreen: ImageButton? = null

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

    // ==================== F1: 缓冲进度条更新 ====================
    /** GSY 底部进度条引用（用于更新 secondaryProgress 显示缓冲进度） */
    private var bottomProgressbar: ProgressBar? = null
    /** 缓冲进度更新 Handler（每 500ms 轮询 bufferedPercentage 更新 secondaryProgress） */
    private val bufferUpdateHandler = Handler(Looper.getMainLooper())
    private var bufferUpdateRunnable: Runnable? = null

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
        // F1: 获取 GSY 底部进度条引用（用于更新 secondaryProgress 显示缓冲进度）
        bottomProgressbar = _playerView?.findViewById(R.id.bottom_progressbar)

        // P0-1.6: 初始化 WebView 降级播放器 + 切换回内置播放器按钮
        webViewPlayer = view.findViewById(R.id.webViewPlayer)
        btnSwitchBack = view.findViewById<ImageButton>(R.id.btn_switch_back).apply {
            setOnClickListener { switchBackToExo() }
        }

        // 初始化悬浮控件
        initOverlayControls(view)

        // 初始化手势检测（含双指缩放）
        initGestureDetector(view)

        // R3: 通知 Activity 视图已就绪
        (activity as? VideoPlayerActivity)?.onFragmentViewReady(this, episodeIndex)
    }

    override fun onDestroyView() {
        cancelAutoHide()  // F2: 清理自动隐藏 Handler
        stopBufferUpdate()  // F1: 停止缓冲进度更新
        stopProgressMonitor()  // 阶段8 F10：停止进度监听
        releasePlayer()
        // P0-1.6: 释放 WebView 资源，防内存泄漏
        webViewPlayer?.release()
        webViewPlayer = null
        btnSwitchBack = null
        isWebViewMode = false
        _playerView = null
        bottomProgressbar = null  // F1: 清理引用
        controlsLayer = null
        leftBottomContainer = null
        tvVideoTitle = null
        tvRouteSelector = null
        rvEpisodes = null
        rightButtons = null
        btnRewind = null
        btnStar = null
        btnSettings = null
        btnForward = null
        btnFullscreen = null
        gestureDetector = null
        scaleGestureDetector = null
        isActivated = false
        super.onDestroyView()
    }

    // ==================== 播放器生命周期控制 ====================

    fun activatePlayer() {
        if (isActivated) return
        val pv = _playerView ?: return
        isActivated = true

        // P0-1.6: WebView 模式下仅恢复 WebView 播放，不重新设置 ExoPlayer
        // ViewPager2 切换回此 Fragment 时恢复 WebView 播放
        if (isWebViewMode) {
            webViewPlayer?.resume()
            return
        }

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
        stopProgressMonitor()  // 阶段8 F10：停止进度监听
        // P0-1.6: WebView 模式下仅暂停 WebView（ViewPager2 切走时）
        if (isWebViewMode) {
            webViewPlayer?.pause()
            return
        }
        _playerView?.onVideoPause()
    }

    fun releasePlayer() {
        _playerView?.currentPlayer?.release()
    }

    // ==================== P0-1.6: WebView 降级播放模式 ====================

    /**
     * P0-1.6: 切换到 WebView 播放模式
     *
     * ExoPlayer 播放失败时由 Activity 调用。
     * 暂停 ExoPlayer + 隐藏其控件层，显示 WebView 播放器 + 切换回按钮。
     * WebView 使用 skill V2 hls-video-player 模板（HLS.js + 进度条/倍速/全屏/横竖屏/上下集）。
     *
     * ViewPager2 兼容性（用户反馈2）：切换后 isWebViewMode=true，后续 activatePlayer/deactivatePlayer
     * 走 WebView 分支（resume/pause），ViewPager2 上下滑动切换不受影响。
     */
    fun switchToWebViewMode(url: String, title: String, headers: Map<String, String>) {
        val pv = _playerView ?: return
        val wvp = webViewPlayer ?: return
        // 暂停 ExoPlayer
        pv.onVideoPause()
        // 隐藏 ExoPlayer + 控件层
        pv.visibility = View.GONE
        controlsLayer?.visibility = View.GONE
        // 显示 WebView 播放器 + 切换回按钮
        wvp.visibility = View.VISIBLE
        btnSwitchBack?.visibility = View.VISIBLE
        // 启动 WebView 播放
        wvp.play(url, title, headers)
        isWebViewMode = true
        // P0 日志规范：播放器状态切换（降级到 WebView）永久日志
        AppLog.put("switchToWebView: episode=$episodeIndex, title=$title, urlLen=${url.length}")
    }

    /**
     * P0-1.6: 切换回内置播放器（ExoPlayer）
     *
     * 由"切换回内置播放器"按钮调用，委托给 retryExoPlayback 重新激活 ExoPlayer。
     */
    fun switchBackToExo() {
        if (!isWebViewMode) return
        retryExoPlayback()
    }

    /**
     * P0-1.4: 重试 ExoPlayer 播放
     *
     * 由错误对话框"重试"按钮或 switchBackToExo 调用。
     * 重置到 ExoPlayer 模式（隐藏 WebView、显示 ExoPlayer），重置 isActivated 后重新激活。
     */
    fun retryExoPlayback() {
        val pv = _playerView ?: return
        // 重置到 ExoPlayer 模式（处理可能处于 WebView 模式的情况）
        webViewPlayer?.let {
            it.pause()
            it.visibility = View.GONE
        }
        btnSwitchBack?.visibility = View.GONE
        pv.visibility = View.VISIBLE
        controlsLayer?.visibility = View.VISIBLE
        isWebViewMode = false
        // 重置激活标志，让 activatePlayer 重新走完整 ExoPlayer 设置流程
        isActivated = false
        activatePlayer()
        // P0 日志规范：播放器状态切换（切换回 ExoPlayer）永久日志
        AppLog.put("retryExoPlayback: episode=$episodeIndex, isWebViewMode=false")
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
     */
    private fun startBufferUpdate() {
        stopBufferUpdate()
        bufferUpdateRunnable = object : Runnable {
            override fun run() {
                val bp = bottomProgressbar ?: return
                val percentage = VideoPlay.videoManager.bufferedPercentage
                if (percentage >= 0) {
                    bp.secondaryProgress = percentage
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
            scheduleAutoHide()  // F2: 显示控件后 3 秒自动隐藏
        } else {
            // 退出横屏全屏态
            currentState = PlayState.NORMAL
            controlsVisibleInFullscreen = true
            applyState(PlayState.NORMAL)
            updateFullscreenButtonIcon(false)
            // 恢复全屏按钮显示逻辑（仅横屏视频显示）
            val pv = _playerView
            if (pv != null && pv.currentVideoWidth > 0 && pv.currentVideoHeight > 0) {
                updateFullscreenButtonVisibility(pv.currentVideoWidth, pv.currentVideoHeight)
            }
            scheduleAutoHide()  // F2: 显示控件后 3 秒自动隐藏
        }
    }

    /**
     * 更新视频标题（由 Activity 在 VIDEO_SUB_TITLE 事件时调用）
     */
    fun updateVideoTitle(title: String) {
        tvVideoTitle?.text = title
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
     * 横屏视频（宽高比 > 1.2）显示全屏按钮，竖屏视频隐藏。
     * 在 FULLSCREEN 状态下始终显示（用于退出全屏）。
     */
    fun updateFullscreenButtonVisibility(videoWidth: Int, videoHeight: Int) {
        if (currentState == PlayState.FULLSCREEN) {
            // 4.8 横屏全屏态下始终显示全屏按钮
            btnFullscreen?.visible()
            return
        }
        val isLandscape =
            videoWidth > 0 && videoHeight > 0 && videoWidth.toFloat() / videoHeight.toFloat() > 1.2f
        if (isLandscape) {
            btnFullscreen?.visible()
        } else {
            btnFullscreen?.gone()
        }
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
                // btn_fullscreen 默认 gone，需根据视频宽高比重新判断
                // 防止 onPrepared 时序问题或容器显隐后子控件 visibility 丢失
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
     * 避免子控件（全屏按钮等）被 hideControlsAnimated 隐藏后
     * 因 visibility=GONE 不被 getOverlayControls 收录而无法恢复。
     * 子控件自身的 visibility（如线路/集数选择器的 gone）不受容器显隐影响。
     */
    private fun getOverlayControls(): List<View> {
        val list = mutableListOf<View>()
        // 左下角容器（包含标题、线路选择器、集数选择器、全屏按钮）
        leftBottomContainer?.let { list.add(it) }
        // 右侧功能按钮容器
        rightButtons?.let { list.add(it) }
        return list
    }

    // ==================== 悬浮控件初始化 ====================

    private fun initOverlayControls(view: View) {
        controlsLayer = view.findViewById(R.id.controlsLayer)
        leftBottomContainer = view.findViewById(R.id.left_bottom_container)
        tvVideoTitle = view.findViewById(R.id.tv_video_title)
        tvRouteSelector = view.findViewById(R.id.tv_route_selector)
        rvEpisodes = view.findViewById(R.id.rv_episodes)
        rightButtons = view.findViewById(R.id.right_buttons)
        btnRewind = view.findViewById(R.id.btn_rewind)
        btnStar = view.findViewById(R.id.btn_star)
        btnSettings = view.findViewById(R.id.btn_settings)
        btnForward = view.findViewById(R.id.btn_forward)
        btnFullscreen = view.findViewById(R.id.btn_fullscreen)

        // 2.1 左下角视频标题（适配文章模式/集数模式）
        val title = when {
            !VideoPlay.rssArticles.isNullOrEmpty() ->
                VideoPlay.rssArticles?.getOrNull(episodeIndex)?.title ?: VideoPlay.videoTitle ?: ""
            !VideoPlay.rssEpisodes.isNullOrEmpty() ->
                VideoPlay.rssEpisodes?.getOrNull(episodeIndex)?.title ?: VideoPlay.videoTitle ?: ""
            else -> VideoPlay.videoTitle ?: ""
        }
        tvVideoTitle?.text = title

        // R3 REQ-17 线路选择器（多线路时显示，标题下方）
        initRouteSelector()

        // R3 REQ-18 集数选择器（多集时显示，线路下方横向滚动）
        initEpisodeSelector()

        // R3 快进/快退按钮（读取配置的快进时间，默认60秒）
        initSkipButtons()

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

        // 用户需求：初始状态为 NORMAL（控件显示）
        // F2: onPrepared 后启动 3 秒自动隐藏；左右滑动/单击可切换显隐
        currentState = PlayState.NORMAL
        applyState(PlayState.NORMAL)
    }

    // ==================== 线路选择器（REQ-17） ====================

    /**
     * 初始化线路选择器
     * 多线路时显示，点击弹出 PopupMenu 选择线路
     */
    private fun initRouteSelector() {
        val routes = VideoPlay.rssRoutes
        if (routes == null || routes.size <= 1) {
            tvRouteSelector?.gone()
            return
        }
        tvRouteSelector?.visible()
        updateRouteSelectorText()
        tvRouteSelector?.setOnClickListener { anchor ->
            val popup = PopupMenu(requireContext(), anchor)
            routes.forEachIndexed { index, route ->
                popup.menu.add(0, index, index, route.name)
            }
            popup.setOnMenuItemClickListener { item ->
                val newIndex = item.itemId
                if (newIndex != VideoPlay.rssRouteIndex) {
                    val episode = VideoPlay.switchRssRoute(newIndex)
                    if (episode != null) {
                        updateRouteSelectorText()
                        // 更新集数列表
                        updateEpisodeList()
                        // 通知 Activity 播放新集
                        (activity as? VideoSettingsPanel.SettingsPanelCallback)?.onRouteChanged(episode)
                    }
                }
                true
            }
            popup.show()
        }
    }

    private fun updateRouteSelectorText() {
        val routes = VideoPlay.rssRoutes ?: return
        val currentRoute = routes.getOrNull(VideoPlay.rssRouteIndex)
        tvRouteSelector?.text = "线路：${currentRoute?.name ?: "未知"} ▼"
    }

    // ==================== 集数选择器（REQ-18） ====================

    /**
     * 初始化集数选择器
     * 多集时显示横向滚动列表
     */
    private fun initEpisodeSelector() {
        val episodes = VideoPlay.rssEpisodes
        if (episodes == null || episodes.isEmpty()) {
            rvEpisodes?.gone()
            return
        }
        rvEpisodes?.visible()
        rvEpisodes?.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        updateEpisodeList()
    }

    /**
     * 更新集数列表数据
     */
    private fun updateEpisodeList() {
        val episodes = VideoPlay.rssEpisodes ?: return
        val rv = rvEpisodes ?: return
        val adapter = RssEpisodeAdapter(episodes, VideoPlay.rssEpisodeIndex) { _, index ->
            if (index != VideoPlay.rssEpisodeIndex) {
                VideoPlay.rssEpisodeIndex = index
                val episode = episodes.getOrNull(index)
                val pv = _playerView
                if (episode != null && pv != null) {
                    VideoPlay.playRssEpisode(pv, episode)
                    // 更新选中状态
                    (rv.adapter as? RssEpisodeAdapter)?.updateSelectedPosition(index)
                    // 更新标题
                    tvVideoTitle?.text = episode.title
                }
            }
        }
        rv.adapter = adapter
    }

    /**
     * 文章切换后更新集数/线路选择器（video-article-swipe-switch spec）
     *
     * 切换文章后，VideoPlay.rssEpisodes/rssRoutes 已更新为新文章的数据。
     * 由 VideoPlayerActivity 在 UP_VIDEO_INFO 事件中调用 currentFragment.updateEpisodeSelector()。
     */
    fun updateEpisodeSelector() {
        // 更新集数选择器
        val episodes = VideoPlay.rssEpisodes
        if (episodes.isNullOrEmpty()) {
            rvEpisodes?.gone()
        } else {
            rvEpisodes?.visible()
            updateEpisodeList()
        }
        // 更新线路选择器
        val routes = VideoPlay.rssRoutes
        if (routes == null || routes.size <= 1) {
            tvRouteSelector?.gone()
        } else {
            tvRouteSelector?.visible()
            updateRouteSelectorText()
            // 重新绑定点击事件（线路列表已变化）
            tvRouteSelector?.setOnClickListener { anchor ->
                val popup = PopupMenu(requireContext(), anchor)
                routes.forEachIndexed { index, route ->
                    popup.menu.add(0, index, index, route.name)
                }
                popup.setOnMenuItemClickListener { item ->
                    val newIndex = item.itemId
                    if (newIndex != VideoPlay.rssRouteIndex) {
                        val episode = VideoPlay.switchRssRoute(newIndex)
                        if (episode != null) {
                            updateRouteSelectorText()
                            updateEpisodeList()
                            (activity as? VideoSettingsPanel.SettingsPanelCallback)?.onRouteChanged(episode)
                        }
                    }
                    true
                }
                popup.show()
            }
        }
        // 更新标题
        tvVideoTitle?.text = VideoPlay.videoTitle ?: ""
    }

    // ==================== 快进/快退按钮 ====================

    /**
     * 初始化快进/快退按钮
     * 读取 VideoPlay.videoSkipTime 配置（默认60秒），点击按配置时间快进/快退
     */
    private fun initSkipButtons() {
        btnRewind?.setOnClickListener {
            skipVideo(-VideoPlay.videoSkipTime.toLong() * 1000)
        }
        btnForward?.setOnClickListener {
            skipVideo(VideoPlay.videoSkipTime.toLong() * 1000)
        }
    }

    /**
     * 快进/快退：跳转到当前位置 ± offsetMillis
     */
    private fun skipVideo(offsetMillis: Long) {
        val pv = _playerView ?: return
        val player = pv.currentPlayer
        val currentPosition = VideoPlay.videoManager.currentPosition
        val duration = VideoPlay.videoManager.duration
        var target = currentPosition + offsetMillis
        if (target < 0) target = 0
        if (duration > 0 && target > duration) target = duration
        player.seekTo(target)
        val skipSeconds = (offsetMillis / 1000).toInt()
        activity?.toastOnUi(if (skipSeconds > 0) "快进 ${skipSeconds}秒" else "快退 ${-skipSeconds}秒")
    }

    // ==================== 手势检测（单击切换显隐 + 双指缩放） ====================

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
        val touchTarget: View? = _playerView?.findViewById(R.id.surface_container) ?: _playerView
        touchTarget?.setOnTouchListener { v, event ->
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
                // surface_container.onTouchEvent 触发。R3 抖音风格不使用 GSY 的亮度/音量/进度滑动手势。
                true
            } else {
                false // 控件区域内不消费（让按钮处理；实际按钮在 controlsLayer 上层已消费）
            }
        }

        // P0-1.6 修复（5.6 验证发现）：WebView 模式下的触摸事件处理
        // 问题：WebView 全屏播放时消费所有触摸事件，导致 ViewPager2 无法上下滑动切换文章
        // 修复方案：在 WebViewVideoPlayer 中重写 onInterceptTouchEvent（而非 OnTouchListener）
        // 原因：FrameLayout 是 ViewGroup，其 OnTouchListener 只在子 View 不处理事件时才被调用，
        //        但 WebView 默认消费所有触摸事件，导致 OnTouchListener 永远不触发。
        //        onInterceptTouchEvent 在事件传递给子 View 之前被调用，可以拦截垂直滑动。
        // 实现位置：WebViewVideoPlayer.onInterceptTouchEvent()
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

        // 用户需求：双指"同时左右滑动"时隐藏控件到 PURE
        // 双指检测避免与 GSY 单指进度条拖动冲突
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    twoFingerStartX1 = event.getX(0)
                    twoFingerStartX2 = event.getX(1)
                    isTwoFingerSwipe = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && isTwoFingerSwipe && currentState == PlayState.NORMAL) {
                    val dx1 = event.getX(0) - twoFingerStartX1
                    val dx2 = event.getX(1) - twoFingerStartX2
                    val threshold = 100f
                    // 两指同时向同一方向（左或右）移动且超过阈值
                    if (kotlin.math.abs(dx1) > threshold && kotlin.math.abs(dx2) > threshold
                        && (dx1 > 0) == (dx2 > 0)
                    ) {
                        switchState(PlayState.PURE)
                        cancelAutoHide()  // F2: 已隐藏控件，取消自动隐藏计时
                        isTwoFingerSwipe = false
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                isTwoFingerSwipe = false
            }
        }

        // Bug修复：双指事件必须消费，阻止 GSY 播放器拦截多指手势
        // GSY 内部有单指手势处理（进度条/亮度/音量），但不处理双指事件
        // 如果不消费双指事件，GSY 可能拦截导致我们的双指检测不生效
        return event.pointerCount >= 2
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
                isVerticalSwipe = false
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
                    // 首次判定滑动方向：垂直滑动优先交给 ViewPager2
                    if (!isVerticalSwipe && kotlin.math.abs(dy) > kotlin.math.abs(dx)
                        && kotlin.math.abs(dy) > 30f
                    ) {
                        isVerticalSwipe = true
                    }
                    if (isVerticalSwipe) {
                        // 垂直滑动：恢复 ViewPager2 拦截能力 + 消费事件阻止 GSY 覆盖
                        _playerView?.parent?.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                    // 水平滑动：交给 GSY 处理进度条，返回 false
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
            }
        }

        // 双指事件消费（阻止 GSY 拦截多指手势），单指水平滑动不消费（交给 GSY 进度条）
        return event.pointerCount >= 2
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
        // 检查左下角容器（标题+线路选择器+集数选择器+全屏按钮）
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

    private fun updateStarButtonState() {
        val isStarred = VideoPlay.rssStar != null
        btnStar?.setImageResource(
            if (isStarred) R.drawable.ic_star else R.drawable.ic_star_border
        )
        val showStar = VideoPlay.book == null && !VideoPlay.singleUrl
        if (showStar) {
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
     * Bug修复：重新注册触摸监听
     *
     * GSY 的 setUp/startPlayLogic 可能覆盖我们在 surface_container 上设置的 OnTouchListener。
     * 在 onPrepared 回调后重新注册，确保手势检测正常工作。
     *
     * 注意：OnTouchListener 必须设在 R.id.surface_container 上（不是 playerView），
     * 因为 GSY 在 init() 中对 surface_container 调用了 setOnTouchListener(this)，
     * surface_container 是实际接收触摸的视图。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun reRegisterTouchListener() {
        if (!needReRegisterTouchListener) return
        needReRegisterTouchListener = false
        val pv = _playerView ?: return
        // Bug修复：reRegisterTouchListener 必须与 initGestureDetector 行为完全一致
        // 之前只注册了 gestureDetector + scaleGestureDetector，丢失了双指左右滑动检测
        // 现统一调用 handlePlayerTouchEvent/handleArticleModeTouchEvent，确保所有触摸逻辑一致
        // 文章模式：单指垂直滑动交给 ViewPager2 拦截（切换文章），水平滑动交给 GSY（进度条）
        // 非文章模式：双指事件消费（阻止 GSY 拦截），单指事件交给 GSY 处理
        val touchTarget: View? = pv.findViewById(R.id.surface_container) ?: pv
        touchTarget?.setOnTouchListener { v, event ->
            val x = event.rawX.toInt()
            val y = event.rawY.toInt()
            val onControls = isTouchOnControls(x, y)
            val isArticleMode = !VideoPlay.rssArticles.isNullOrEmpty()
                && VideoPlay.rssArticles!!.size > 1
            if (!onControls) {
                if (isArticleMode) {
                    handleArticleModeTouchEvent(event)
                } else {
                    handlePlayerTouchEvent(event)
                }
                // 始终消费事件（返回 true），阻止 GSY 的 onClick 和 onTouchEvent
                true
            } else {
                false
            }
        }
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

        fun newInstance(index: Int): VideoFragment {
            return VideoFragment().apply {
                arguments = Bundle().apply { putInt(ARG_EPISODE_INDEX, index) }
            }
        }
    }
}
