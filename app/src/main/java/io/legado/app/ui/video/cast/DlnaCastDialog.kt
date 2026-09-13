package io.legado.app.ui.video.cast

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.legado.app.help.dlna.DlnaCastManager
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.theme.LegadoTheme

/**
 * add-dlna-cast：投屏面板壳（BottomSheetDialogFragment）。
 *
 * 职责边界（design「UI 实现约定」）：
 *  - **只做壳**：收集 `DlnaCastManager.state` 并透传给 [DlnaCastContent]，把用户动作
 *    转成 Manager 调用。本身不保存任何会话状态 → 面板反复开合/Activity 重建都能复原。
 *  - `dismiss()` **不触发 teardown**：关掉面板只是收起 UI，投屏会话继续；
 *    只有用户显式点「结束投屏」或发生失败才终止。
 *  - 传入回调的一律是 `applicationContext`：Manager 是 `object`，绝不能被塞进 Activity 引用。
 *  - 样式与 [io.legado.app.ui.video.VideoSettingsPanel] 同族（ComposeView + LegadoTheme +
 *    `UiCorner.panelRadius` 圆角 + `ThemeStore.backgroundColor()` 背景），不新造视觉。
 */
class DlnaCastDialog : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val appContext: Context = requireContext().applicationContext
        return ComposeView(requireContext()).apply {
            setContent {
                LegadoTheme {
                    val state by DlnaCastManager.state.collectAsState()
                    DlnaCastContent(
                        state = state,
                        onDismiss = { dismiss() },
                        onRetry = { DlnaCastManager.startDiscovery(appContext) },
                        onSelect = { device -> DlnaCastManager.castTo(appContext, device) },
                        onTogglePause = { DlnaCastManager.togglePause() },
                        onStop = { DlnaCastManager.stopByUser() },
                        onSeek = { DlnaCastManager.seekTo(it) },
                        onVolume = { DlnaCastManager.setVolume(it) },
                        onSwitchDevice = {
                            // 换设备：重新走发现（REQ-03 Scenario「投屏中切换到另一个设备」）
                            DlnaCastManager.teardown()
                            DlnaCastManager.startDiscovery(appContext)
                        }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 已有会话在跑（投递中 CASTING 或建立中 CONNECTING）时不再重新发现，
        // 面板直接落到控制态（REQ-03 Scenario「投屏中重新打开面板」；3.4 防第二发现流程）
        if (!DlnaCastManager.isSessionBusy()) {
            DlnaCastManager.startDiscovery(requireContext().applicationContext)
        }
    }

    override fun onStart() {
        super.onStart()
        // 应用级暗色主题不激活 values-night 资源，需动态设置 sheet 容器背景；
        // 圆角跟随全局缩放（对齐 VideoSettingsPanel 同族实现）
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.let { sheet ->
                val radius = UiCorner.panelRadius(requireContext())
                sheet.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
                    setColor(ThemeStore.backgroundColor())
                }
                sheet.clipToOutline = true
            }
    }

    companion object {
        const val TAG = "DlnaCastDialog"
    }
}
