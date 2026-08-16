package io.legado.app.ui.widget.components

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.DialogFragment
import io.legado.app.ui.theme.LegadoTheme

/**
 * Compose 弹窗统一宿主基类（公共组件库三期 Dialog 族）。
 *
 * 规格：ui-standards §3.4 `BaseComposeDialogFragment`（task 12.33，from Legado_Max Dialog 族统一宿主）
 * - 继承 [DialogFragment]，子类只需覆写 [content] 提供 Compose 内容
 * - [onCreateView] 返回 [ComposeView] 并包裹 [LegadoTheme]（跟随用户主题色）
 * - [onCreate] `STYLE_NO_FRAME` 无边框；[onStart] 默认 WRAP_CONTENT（需全宽可覆写）
 * - 资源访问：Compose 内用 `LocalContext.current` 或本类 [dialogContext]
 */
abstract class BaseComposeDialogFragment : DialogFragment() {

    /** Dialog 宿主 Context（在 [content] 内可直接访问 activity/fragment 资源）。 */
    protected val dialogContext get() = requireContext()

    /** Compose 内容，由子类实现；运行在 [LegadoTheme] 下。 */
    @Composable
    protected abstract fun content()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, 0)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setContent {
            LegadoTheme {
                content()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 默认内容自适应；需全宽的弹窗可覆写本方法自行 setLayout
        dialog?.window?.setLayout(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }
}
