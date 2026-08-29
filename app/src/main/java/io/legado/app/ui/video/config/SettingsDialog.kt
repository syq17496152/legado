package io.legado.app.ui.video.config

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.R
import io.legado.app.model.VideoPlay
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.video.VideoSettingsPanelContent
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.number.NumberPickerDialog

/**
 * 视频设置对话框（ComposeDialogFragment 壳迁移）。
 *
 * 原 BaseDialogFragment(R.layout.dialog_video_settings)（ComposeView 宿主布局）迁移为
 * [ComposeDialogFragment] + 程序化创建 ComposeView，移除 R.layout / ViewBinding 依赖。
 *
 * video-player-ux-fixes P3 透明修复：ComposeDialogFragment 基类 window 背景透明，
 * 原内容无背景壳导致弹框透明。现接入 [AppDialogFrame] 规范壳
 * （ui-standards/dialog-shell.md：themeUiPalette.cardColor 取色 + panelRadius 圆角），
 * scrollContent=false 避免与 VideoSettingsPanelContent 自身 verticalScroll 嵌套（滚动嵌套禁令），
 * showDragHandle=false 隐藏 BottomSheet 专属拖拽手柄。
 *
 * 使用场景：
 * - [io.legado.app.ui.video.VideoPlayerActivity] 设置菜单
 * - [io.legado.app.ui.config.OtherConfigFragment] 视频设置入口
 */
class SettingsDialog() : ComposeDialogFragment() {

    constructor(context: Context, callBack: CallBack? = null) : this()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    AppDialogFrame(
                        title = getString(R.string.video_settings_title),
                        scrollContent = false,
                        content = {
                            VideoSettingsPanelContent(
                                videoUrl = VideoPlay.videoUrl,
                                description = null,
                                showLogin = false,
                                debugLog = "",
                                pressSpeedSummary = getString(R.string.press_speed_summary, VideoPlay.longPressSpeed / 10.0f),
                                onDismissRequest = { dismiss() },
                                onSkip = {},
                                onRatio = {},
                                onAudioTrack = {},
                                onCopyUrl = {},
                                onFloatWindow = {},
                                onOtherPlayer = {},
                                onEditSource = {},
                                onLogin = {},
                                onLog = {},
                                onPickPressSpeed = ::pickPressSpeed,
                                showDragHandle = false
                            )
                        },
                        actions = {}
                    )
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
            }
            .show {
                VideoPlay.longPressSpeed = it
            }
    }

    interface CallBack
}
