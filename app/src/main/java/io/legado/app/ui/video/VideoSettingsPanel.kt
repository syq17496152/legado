package io.legado.app.ui.video

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
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
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.sendToClip
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
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
 * - 播放设置：自动播放 / 直接全屏 / 底部进度条 / 静音 / 长按倍速 / 快进快退时间 / 缓存大小 / 播放器类型
 * - 播放器优化：首帧预加载 / 缓冲策略 / 播放历史 / 错误提示 / 自动重连
 *
 * task 12.4B（L-D9 视频播放器 · 视频设置弹框改造）：UI 层 Compose 化（[VideoSettingsPanelContent]），
 * 保留 BottomSheetDialogFragment 壳、全部业务逻辑与 [SettingsPanelCallback] 接口。
 */
class VideoSettingsPanel : BottomSheetDialogFragment() {

    /** 由 Activity 注入的播放器引用，用于快进快退/画面比例/音轨 */
    var playerView: VideoPlayer? = null

    /** 由 Activity 注入的回调，用于菜单功能（悬浮窗/编辑源等） */
    var callback: SettingsPanelCallback? = null

    /** 长按倍速展示值（NumberPicker 选择后由宿主更新驱动重组） */
    private var pressSpeedDisplay by mutableStateOf(0f)

    /** 调试日志文本（Activity 通过 appendDebugLog() 追加，Compose 读取驱动重组） */
    private var debugLog by mutableStateOf("")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        pressSpeedDisplay = VideoPlay.longPressSpeed / 10.0f
        return ComposeView(requireContext()).apply {
            setContent {
                LegadoTheme {
                    VideoSettingsPanelContent(
                        videoUrl = VideoPlay.videoUrl,
                        description = VideoPlay.rssStar?.toRssArticle()?.description
                            ?: VideoPlay.rssRecord?.toRssArticle()?.description,
                        showLogin = !VideoPlay.source?.loginUrl.isNullOrBlank(),
                        debugLog = debugLog,
                        pressSpeedSummary = getString(R.string.press_speed_summary, pressSpeedDisplay),
                        onDismissRequest = { dismiss() },
                        onSkip = ::skipVideo,
                        onRatio = { playerView?.showRatioDialogPublic() },
                        onAudioTrack = { playerView?.showAudioTrackDialogPublic() },
                        onCopyUrl = ::copyVideoUrl,
                        onFloatWindow = {
                            callback?.onFloatWindow()
                            dismiss()
                        },
                        onOtherPlayer = ::openOtherPlayer,
                        onEditSource = { callback?.onEditSource() },
                        onLogin = ::openLogin,
                        onLog = { callback?.onLog() },
                        onPickPressSpeed = ::pickPressSpeed
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 修复：应用级暗色主题不激活 values-night 资源, 需动态设置 sheet 背景色
        // Compose 内容由 LegadoTheme 适配, 此处仅设置外层 sheet 容器背景
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.let { sheet ->
                val radius = resources.getDimension(R.dimen.corner_large)
                sheet.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
                    setColor(io.legado.app.lib.theme.ThemeStore.backgroundColor())
                }
                sheet.clipToOutline = true
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

    private fun copyVideoUrl() {
        val videoUrl = VideoPlay.videoUrl
        if (!videoUrl.isNullOrEmpty()) {
            requireContext().sendToClip(videoUrl)
            activity?.toastOnUi(getString(R.string.video_play_url_copied))
        }
    }

    private fun openOtherPlayer() {
        val url = VideoPlay.videoUrl
        if (url.isNullOrBlank()) {
            activity?.toastOnUi(getString(R.string.video_no_play_url))
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(url.toUri(), "video/*")
        }
        startActivity(intent)
    }

    private fun openLogin() {
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

    private fun pickPressSpeed() {
        NumberPickerDialog(requireContext(), true)
            .setTitle(getString(R.string.press_speed))
            .setMaxValue(60)
            .setMinValue(5)
            .setValue(VideoPlay.longPressSpeed)
            .setCustomButton((R.string.btn_default_s)) {
                VideoPlay.longPressSpeed = 30
                pressSpeedDisplay = 3.0f
            }
            .show {
                VideoPlay.longPressSpeed = it
                pressSpeedDisplay = it / 10.0f
            }
    }

    /**
     * 追加调试日志文本
     * 由 Activity 在 VIDEO_PLAY_ERROR 事件时调用
     */
    fun appendDebugLog(text: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        debugLog += "[$time] $text\n"
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
