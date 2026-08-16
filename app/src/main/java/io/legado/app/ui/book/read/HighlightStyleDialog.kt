package io.legado.app.ui.book.read

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.legado.app.R
import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightStyle.Deco
import io.legado.app.help.HighlightStyle.Underline
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.HighlightStyleSheet

/**
 * F-P1-2 高亮规则系统（借鉴阅读T）
 * 高亮样式底部面板。逐通道开关 + 取色 + 下划线线型 + 预设。
 * 编辑宿主 [StyleHost] 提供的当前样式;改即回调 [StyleHost.onHighlightStyleChanged] 应用(实时预览)。
 * 取色委托 [StyleHost.pickHighlightColor],宿主写回后调 [refresh]。
 *
 * task 12.2E：UI 层 Compose 化（[HighlightStyleSheet]），保留 [StyleHost] 桥接与全部业务逻辑，
 * 宿主(如 HighlightRuleEditDialog)通过 [refresh] 在取色/选字后驱动 Compose 重组。
 */
class HighlightStyleDialog : BottomSheetDialogFragment() {

    interface StyleHost {
        /** 当前正在编辑的样式 */
        fun currentHighlightStyle(): HighlightStyle
        /** 样式被改动(开关/预设/取色后) */
        fun onHighlightStyleChanged(style: HighlightStyle)
        /** 打开某通道取色器(dialogId 用 HL_*) */
        fun pickHighlightColor(dialogId: Int, initial: Int, withAlpha: Boolean)
        /** 打开字体选择器(current 为当前字体路径, 空表示默认) */
        fun pickHighlightFont(current: String)
    }

    private val styleHost get() = resolveStyleHost(parentFragment, activity)

    // Compose 状态镜像：宿主写回后由 refresh() 更新驱动重组
    private var uiStyle by mutableStateOf(HighlightStyle())
    private var uiFontDisplay by mutableStateOf("")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val s = styleHost?.currentHighlightStyle() ?: HighlightStyle()
        uiStyle = s
        uiFontDisplay = fontDisplayName(s.fontPath)
        return ComposeView(requireContext()).apply {
            setContent {
                LegadoTheme {
                    HighlightStyleSheet(
                        style = uiStyle,
                        onStyleChange = { apply(it) },
                        onPickColor = { dialogId, initial, withAlpha ->
                            styleHost?.pickHighlightColor(dialogId, initial, withAlpha)
                        },
                        onPickFont = { current -> styleHost?.pickHighlightFont(current) },
                        fontDisplayName = uiFontDisplay
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (styleHost == null) {   // 宿主丢失(如配置变更), 关闭以免空面板
            dismiss()
        }
    }

    private fun cur(): HighlightStyle = styleHost?.currentHighlightStyle() ?: HighlightStyle()

    private fun apply(s: HighlightStyle) {
        styleHost?.onHighlightStyleChanged(s)
        refresh()
    }

    /** 把字体路径转成可读名(content uri 解码后取末段文件名);空=默认 */
    private fun fontDisplayName(path: String): String {
        if (path.isEmpty()) return getString(R.string.highlight_font_default)
        return Uri.decode(path)?.substringAfterLast('/')?.ifBlank { path } ?: path
    }

    /** 宿主(取色/选字写回)调用: 刷新 Compose 镜像 */
    fun refresh() {
        if (view == null) return
        uiStyle = cur()
        uiFontDisplay = fontDisplayName(uiStyle.fontPath)
    }

    companion object {
        fun resolveStyleHost(parent: Any?, activity: Any?): StyleHost? {
            return (parent as? StyleHost) ?: (activity as? StyleHost)
        }

        /** 把某通道(HL_*)取到的颜色写进样式; 手动/规则两处宿主共用, 避免重复 */
        fun applyChannelColor(s: HighlightStyle, dialogId: Int, color: Int): HighlightStyle = when (dialogId) {
            HighlightActionMenu.HL_FILL -> s.copy(fill = color)
            HighlightActionMenu.HL_TEXT -> s.copy(textColor = color)
            HighlightActionMenu.HL_UNDERLINE -> s.copy(underline = (s.underline ?: Underline()).copy(color = color))
            HighlightActionMenu.HL_STRIKE -> s.copy(strike = Deco(color))
            HighlightActionMenu.HL_BOX -> s.copy(box = Deco(color))
            HighlightActionMenu.HL_EMPHASIS -> s.copy(emphasis = Deco(color))
            else -> s
        }
    }
}
