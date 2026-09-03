package io.legado.app.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.model.VideoPlay
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.video.PanelHost
import io.legado.app.ui.video.VideoSettingsPanelContent
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.ui.widget.number.NumberPickerDialog

/**
 * 视频播放器全局设置页（video-player-dual-layout AD-08 路线 B）
 *
 * 设计要点（红队 R3-1 铁证裁决）：
 * - **禁止继承 ComposeSettingFragment**——其 prefs 监听硬绑默认 SharedPreferences 且非 open，
 *   与 VideoPlay 自持的 video_config 独立文件不兼容（监听永不触发/配置双文件分裂）。
 * - 路线 B：普通 Fragment 挂 ConfigActivity.replaceFragment，自建 ComposeView +
 *   LegadoTheme + [VideoSettingsPanelContent](PanelHost.GLOBAL) 复用面板组件（一份代码三处行为一致）。
 * - 取色遵循 ui-standards：页面根背景 = rememberAppSettingPalette().page（ThemeStore 直读同源），
 *   面板内部取色经 rememberAppDialogStyle() 同源，无硬编码色。
 * - 配置刷新：Composable 初值 remember 读 VideoPlay，进入页面（onCreateView）即重建读取，
 *   弹框选择即写回 VideoPlay——无需额外监听机制。
 */
class VideoPlayerConfigFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val palette = rememberAppSettingPalette()
                LegadoTheme {
                    Column(
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxSize()
                            .background(palette.page)
                    ) {
                        VideoSettingsPanelContent(
                            videoUrl = null,
                            description = null,
                            showLogin = false,
                            debugLog = "",
                            pressSpeedSummary = stringResource(
                                R.string.press_speed_summary,
                                VideoPlay.longPressSpeed / 10.0f
                            ),
                            onDismissRequest = {},
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
                            showDragHandle = false,
                            host = PanelHost.GLOBAL,
                            expand = true
                        )
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.setTitle(R.string.video_setting)
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
}
