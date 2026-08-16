package io.legado.app.ui.video.config

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.compose.ui.platform.ComposeView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.model.VideoPlay
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.video.VideoSettingsPanelContent
import io.legado.app.ui.widget.number.NumberPickerDialog

/**
 * 视频设置对话框（task 12.4B 深化：Compose 化改造）。
 *
 * 原 View 控件布局 [dialog_video_settings.xml] 已迁移至 [VideoSettingsPanelContent] 统一组件，
 * 布局文件精简为 ComposeView 宿主。保留 DialogFragment 壳，通过 ComposeView 承载内容。
 * 与 [VideoSettingsPanel]（BottomSheetDialogFragment）共享同一 Compose 内容组件。
 *
 * 使用场景：
 * - [io.legado.app.ui.video.VideoPlayerActivity] 设置菜单
 * - [io.legado.app.ui.config.OtherConfigFragment] 视频设置入口
 */
class SettingsDialog(private val context: Context, private val callBack: CallBack? = null) :
    BaseDialogFragment(R.layout.dialog_video_settings) {

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<ComposeView>(R.id.compose_view).setContent {
            LegadoTheme {
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
                    onPickPressSpeed = ::pickPressSpeed
                )
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