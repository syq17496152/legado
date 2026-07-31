package io.legado.app.ui.video

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.data.entities.RssEpisode
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.BookSource
import io.legado.app.help.gsyVideo.VideoPlayer
import io.legado.app.model.VideoPlay
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.video.config.SettingsDialog
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.gone
import io.legado.app.utils.sendToClip
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import io.legado.app.utils.StartActivityContract

/**
 * R3 阶段5：视频设置面板（BottomSheet）
 *
 * ViewPager2 沉浸式模式下的综合功能面板，100% 保留旧模式所有功能。
 * 仅在 ViewPager2 模式下使用（RSS 订阅源非单 URL）。
 *
 * 注意：线路选择器和集数选择器已移至 VideoFragment 左下角（符合 R3 设计文档 REQ-17/18），
 * 本面板不再包含线路/集数选择，仅保留综合设置与功能菜单。
 *
 * 包含功能分区：
 * - 播放控制：快进快退 / 画面比例 / 音轨选择
 * - 播放信息：播放地址展示+复制 / 视频简介
 * - 功能菜单：悬浮窗 / 其他播放器 / 编辑源 / 登录 / 日志 / 调试
 * - 播放设置：自动播放 / 直接全屏 / 底部进度条 / 静音 / 长按倍速 / 快进快退时间 / 缓存大小
 */
class VideoSettingsPanel : BottomSheetDialogFragment() {

    /** 由 Activity 注入的播放器引用，用于快进快退/画面比例/音轨 */
    var playerView: VideoPlayer? = null

    /** 由 Activity 注入的回调，用于菜单功能（悬浮窗/编辑源等） */
    var callback: SettingsPanelCallback? = null

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.layout_video_settings_panel, container, false)
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initPlaybackControls(view)
        initPlayInfo(view)
        initMenuActions(view)
        initDebugPanel(view)
        initSettings(view)
    }

    // ==================== 播放控制 ====================

    private fun initPlaybackControls(view: View) {
        // 快进快退
        view.findViewById<View>(R.id.btn_skip_back_30s)?.setOnClickListener { skipVideo(-30000) }
        view.findViewById<View>(R.id.btn_skip_back_10s)?.setOnClickListener { skipVideo(-10000) }
        view.findViewById<View>(R.id.btn_skip_fwd_10s)?.setOnClickListener { skipVideo(10000) }
        view.findViewById<View>(R.id.btn_skip_fwd_30s)?.setOnClickListener { skipVideo(30000) }

        // 画面比例
        view.findViewById<View>(R.id.btn_ratio)?.setOnClickListener {
            playerView?.showRatioDialogPublic()
        }

        // 音轨选择
        view.findViewById<View>(R.id.btn_audio_track)?.setOnClickListener {
            playerView?.showAudioTrackDialogPublic()
        }
    }

    /**
     * 快进快退：跳转到当前位置 ± offset
     */
    private fun skipVideo(offsetMillis: Long) {
        val pv = playerView ?: return
        val player = pv.currentPlayer
        val currentPosition = VideoPlay.videoManager.currentPosition
        val duration = VideoPlay.videoManager.duration
        var target = currentPosition + offsetMillis
        if (target < 0) target = 0
        if (duration > 0 && target > duration) target = duration
        player.seekTo(target)
    }

    // ==================== 播放信息 ====================

    @SuppressLint("SetTextI18n")
    private fun initPlayInfo(view: View) {
        // 播放地址展示 + 复制
        val videoUrl = VideoPlay.videoUrl
        val tvPlayUrl = view.findViewById<android.widget.TextView>(R.id.tv_play_url)
        val btnCopyUrl = view.findViewById<android.widget.Button>(R.id.btn_copy_url)

        if (!videoUrl.isNullOrEmpty()) {
            tvPlayUrl?.text = "播放地址：$videoUrl"
            tvPlayUrl?.visible()
            btnCopyUrl?.visible()
            tvPlayUrl?.setOnClickListener {
                requireContext().sendToClip(videoUrl)
                activity?.toastOnUi("播放地址已复制")
            }
            btnCopyUrl?.setOnClickListener {
                requireContext().sendToClip(videoUrl)
                activity?.toastOnUi("播放地址已复制")
            }
        } else {
            tvPlayUrl?.text = "暂无播放地址"
            tvPlayUrl?.visible()
            btnCopyUrl?.gone()
        }

        // 视频简介
        val description = VideoPlay.rssStar?.toRssArticle()?.description
            ?: VideoPlay.rssRecord?.toRssArticle()?.description
        val tvDescription = view.findViewById<android.widget.TextView>(R.id.tv_description)
        if (!description.isNullOrEmpty()) {
            tvDescription?.text = description
            tvDescription?.visible()
        } else {
            tvDescription?.gone()
        }
    }

    // ==================== 功能菜单 ====================

    private fun initMenuActions(view: View) {
        // 悬浮窗
        view.findViewById<View>(R.id.btn_float_window)?.setOnClickListener {
            callback?.onFloatWindow()
            dismiss()
        }

        // 其他播放器
        view.findViewById<View>(R.id.btn_other_player)?.setOnClickListener {
            val url = VideoPlay.videoUrl
            if (url.isNullOrBlank()) {
                activity?.toastOnUi("暂无播放地址")
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(url.toUri(), "video/*")
            }
            startActivity(intent)
        }

        // 编辑源
        view.findViewById<View>(R.id.btn_edit_source)?.setOnClickListener {
            callback?.onEditSource()
        }

        // 登录
        val btnLogin = view.findViewById<View>(R.id.btn_login)
        if (VideoPlay.source?.loginUrl.isNullOrBlank()) {
            btnLogin?.gone()
        } else {
            btnLogin?.visible()
            btnLogin?.setOnClickListener {
                VideoPlay.source?.let { s ->
                    when (s) {
                        is BookSource -> {
                            startActivity<SourceLoginActivity> {
                                putExtra("bookType", BookType.video)
                            }
                        }
                        is RssSource -> {
                            startActivity<SourceLoginActivity> {
                                putExtra("type", "rssSource")
                                putExtra("key", s.getKey())
                            }
                        }
                    }
                }
            }
        }

        // 日志
        view.findViewById<View>(R.id.btn_log)?.setOnClickListener {
            callback?.onLog()
        }

        // 调试按钮
        view.findViewById<View>(R.id.btn_debug_toggle)?.setOnClickListener {
            val debugSection = view.findViewById<View>(R.id.debug_log_section)
            if (debugSection?.visibility == View.VISIBLE) {
                debugSection.gone()
            } else {
                debugSection?.visible()
            }
        }
    }

    // ==================== 调试面板 ====================

    private fun initDebugPanel(view: View) {
        // 调试日志文本在面板内展示
        // 外部通过 appendDebugLog() 追加日志
    }

    /**
     * 追加调试日志文本
     * 由 Activity 在 VIDEO_PLAY_ERROR 事件时调用
     */
    fun appendDebugLog(text: String) {
        val view = view ?: return
        val tvDebugLog = view.findViewById<android.widget.TextView>(R.id.tv_debug_log) ?: return
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        tvDebugLog.append("[$time] $text\n")
    }

    // ==================== 播放设置（合并自 SettingsDialog） ====================

    @SuppressLint("SetTextI18n")
    private fun initSettings(view: View) {
        // 自动播放
        val cbAutoPlay = view.findViewById<android.widget.CheckBox>(R.id.cb_auto_play)
        val ctStartFull = view.findViewById<View>(R.id.ct_start_full)
        val cbStartFull = view.findViewById<android.widget.CheckBox>(R.id.cb_start_full)
        val cbFullBottomProgress = view.findViewById<android.widget.CheckBox>(R.id.cb_full_bottom_progress)
        val cbMuteOnStart = view.findViewById<android.widget.CheckBox>(R.id.cb_mute_on_start)
        val tvPressSpeed = view.findViewById<android.widget.TextView>(R.id.tv_press_speed)

        cbAutoPlay?.isChecked = VideoPlay.autoPlay
        cbStartFull?.isChecked = VideoPlay.startFull
        cbFullBottomProgress?.isChecked = VideoPlay.fullBottomProgressBar
        cbMuteOnStart?.isChecked = VideoPlay.muteOnStart
        ctStartFull?.visibility = if (VideoPlay.autoPlay) View.VISIBLE else View.GONE
        tvPressSpeed?.text = (VideoPlay.longPressSpeed / 10.0f).toPressSpeedStr()

        cbAutoPlay?.setOnCheckedChangeListener { _, isChecked ->
            VideoPlay.autoPlay = isChecked
            ctStartFull?.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        cbStartFull?.setOnCheckedChangeListener { _, isChecked ->
            VideoPlay.startFull = isChecked
        }
        cbFullBottomProgress?.setOnCheckedChangeListener { _, isChecked ->
            VideoPlay.fullBottomProgressBar = isChecked
        }
        cbMuteOnStart?.setOnCheckedChangeListener { _, isChecked ->
            VideoPlay.muteOnStart = isChecked
        }
        tvPressSpeed?.setOnClickListener {
            NumberPickerDialog(requireContext(), true)
                .setTitle(getString(R.string.press_speed))
                .setMaxValue(60)
                .setMinValue(5)
                .setValue(VideoPlay.longPressSpeed)
                .setCustomButton((R.string.btn_default_s)) {
                    VideoPlay.longPressSpeed = 30
                    tvPressSpeed.text = 3.0f.toPressSpeedStr()
                }
                .show {
                    VideoPlay.longPressSpeed = it
                    tvPressSpeed.text = (it / 10.0f).toPressSpeedStr()
                }
        }

        // 快进/快退时间 Spinner（右侧功能按钮快进快退使用，默认 60 秒）
        val spSkipTime = view.findViewById<android.widget.Spinner>(R.id.sp_skip_time)
        val skipTimes = intArrayOf(10, 30, 60, 90, 120)
        val skipLabels = skipTimes.map { "${it}秒" }
        spSkipTime?.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, skipLabels
        )
        spSkipTime?.setSelection(
            skipTimes.indexOfFirst { it == VideoPlay.videoSkipTime }.coerceAtLeast(0)
        )
        spSkipTime?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                VideoPlay.videoSkipTime = skipTimes[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 缓存容量 Spinner
        val spVideoCacheSize = view.findViewById<android.widget.Spinner>(R.id.sp_video_cache_size)
        val cacheSizes = intArrayOf(50, 100, 200, 500)
        val cacheLabels = cacheSizes.map { getString(R.string.video_cache_size_summary, it) }
        spVideoCacheSize?.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, cacheLabels
        )
        spVideoCacheSize?.setSelection(
            cacheSizes.indexOfFirst { it == VideoPlay.videoCacheSize }.coerceAtLeast(0)
        )
        spVideoCacheSize?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                VideoPlay.videoCacheSize = cacheSizes[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // P0-1.8: 播放器类型 Spinner（0=自动 / 1=内置播放器 / 2=WebView）
        val spPlayerType = view.findViewById<android.widget.Spinner>(R.id.sp_player_type)
        val playerTypeLabels = listOf(
            getString(R.string.player_type_auto),
            getString(R.string.player_type_exo),
            getString(R.string.player_type_webview)
        )
        spPlayerType?.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, playerTypeLabels
        )
        spPlayerType?.setSelection(VideoPlay.playerType.coerceIn(0, 2))
        spPlayerType?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                VideoPlay.playerType = position
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ====== BUG2 fix: 播放器优化5项配置 ======

        // 首帧预加载
        val cbFirstFramePreload = view.findViewById<android.widget.CheckBox>(R.id.cb_first_frame_preload)
        cbFirstFramePreload?.isChecked = VideoPlay.playerFirstFramePreload
        cbFirstFramePreload?.setOnCheckedChangeListener { _, isChecked ->
            VideoPlay.playerFirstFramePreload = isChecked
        }

        // 缓冲策略 Spinner（0=自动/1=激进/2=平衡/3=保守）
        val spBufferStrategy = view.findViewById<android.widget.Spinner>(R.id.sp_buffer_strategy)
        val bufferStrategyLabels = listOf(
            getString(R.string.player_buffer_strategy_auto),
            getString(R.string.player_buffer_strategy_aggressive),
            getString(R.string.player_buffer_strategy_balanced),
            getString(R.string.player_buffer_strategy_conservative)
        )
        spBufferStrategy?.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, bufferStrategyLabels
        )
        spBufferStrategy?.setSelection(VideoPlay.playerBufferStrategy.coerceIn(0, 3))
        spBufferStrategy?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                VideoPlay.playerBufferStrategy = position
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 播放历史
        val cbHistoryEnabled = view.findViewById<android.widget.CheckBox>(R.id.cb_history_enabled)
        cbHistoryEnabled?.isChecked = VideoPlay.playerHistoryEnabled
        cbHistoryEnabled?.setOnCheckedChangeListener { _, isChecked ->
            VideoPlay.playerHistoryEnabled = isChecked
        }

        // 播放错误提示
        val cbErrorTip = view.findViewById<android.widget.CheckBox>(R.id.cb_error_tip)
        cbErrorTip?.isChecked = VideoPlay.playerErrorTip
        cbErrorTip?.setOnCheckedChangeListener { _, isChecked ->
            VideoPlay.playerErrorTip = isChecked
        }

        // 自动重连
        val cbAutoReconnect = view.findViewById<android.widget.CheckBox>(R.id.cb_auto_reconnect)
        cbAutoReconnect?.isChecked = VideoPlay.playerAutoReconnect
        cbAutoReconnect?.setOnCheckedChangeListener { _, isChecked ->
            VideoPlay.playerAutoReconnect = isChecked
        }
    }

    private fun Float.toPressSpeedStr(): String {
        return requireContext().getString(R.string.press_speed_summary, this)
    }

    // ==================== 回调接口 ====================

    /**
     * 设置面板回调接口
     * Activity 实现此接口，处理需要 Activity 上下文的操作
     */
    interface SettingsPanelCallback {
        /** 线路切换后，Activity 需要播放新集 + 更新 ViewPager2 */
        fun onRouteChanged(episode: RssEpisode)

        /** 悬浮窗 */
        fun onFloatWindow()

        /** 编辑源 */
        fun onEditSource()

        /** 日志 */
        fun onLog()
    }

    companion object {
        const val TAG = "VideoSettingsPanel"

        fun newInstance(): VideoSettingsPanel {
            return VideoSettingsPanel()
        }
    }
}
