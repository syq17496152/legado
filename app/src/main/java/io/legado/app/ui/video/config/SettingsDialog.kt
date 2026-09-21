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
import io.legado.app.ui.video.PanelHost
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
 *   （video-player-dual-layout：OtherConfig 入口已改跳转全局设置页 ConfigTag.VIDEO_PLAYER；
 *    C6 审计（2026-09-22）后宿主收窄为 **PanelHost.GLOBAL** —— 本弹框语义是「配置设置」，
 *    只渲染布局模式 / 播放设置 / 画质增强；**播放控制区（快进快退·比例·音轨·复制地址·悬浮窗等）
 *    不在此渲染**，该能力统一走播放页 [io.legado.app.ui.video.VideoSettingsPanel]（BottomSheet，回调全接线）。
 *    布局模式切换仍经 onLayoutModeSelected 重建续播）
 */
class SettingsDialog() : ComposeDialogFragment() {

    constructor(context: Context, callBack: CallBack? = null) : this()

    /** video-player-dual-layout R7：布局模式选中回调（Activity 注入，执行容器重建续播） */
    var onLayoutModeSelected: ((Int) -> Unit)? = null

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
                                // C6 审计修复（2026-09-22）：原 host = PLAYER_PAGE 会让「配置设置」弹框
                                // 渲染播放控制区（←30s/10s→、画面比例、音轨、复制地址、悬浮窗…），
                                // 但本弹框的 9 个播放回调**全部为空实现** ⇒ 按钮点了无反应（真机可感知）。
                                // 修法取「收窄宿主」而非「补接线」：本弹框语义是**设置**（布局模式/播放设置/
                                // 画质增强，均由自身状态直读写回 VideoPlay），播放控制已有播放页 BottomSheet
                                // 完整入口（VideoSettingsPanel，回调全接线）⇒ 避免同一功能双入口其一静默失效。
                                host = PanelHost.GLOBAL,
                                onLayoutModeSelected = { target ->
                                    onLayoutModeSelected?.invoke(target)
                                    dismiss()
                                }
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
