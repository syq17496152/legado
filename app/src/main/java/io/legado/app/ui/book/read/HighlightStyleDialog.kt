package io.legado.app.ui.book.read

import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.legado.app.R
import io.legado.app.databinding.DialogHighlightStyleBinding
import io.legado.app.databinding.ItemHighlightChannelBinding
import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightStyle.Deco
import io.legado.app.help.HighlightStyle.Kind
import io.legado.app.help.HighlightStyle.Underline
import io.legado.app.help.HighlightStyles
import io.legado.app.lib.theme.ThemeStore

/**
 * F-P1-2 高亮规则系统（借鉴阅读T）
 * 高亮样式底部面板。逐通道开关 + 取色 + 下划线线型 + 预设。
 * 编辑宿主 [StyleHost] 提供的当前样式;改即回调 [StyleHost.onHighlightStyleChanged] 应用(实时预览)。
 * 取色委托 [StyleHost.pickHighlightColor],宿主写回后调 [refresh]。
 *
 * 适配说明：原阅读T 调用 applyAppSheetBackground()扩展函数,当前项目无此函数,
 * 改为直接用 ThemeStore.backgroundColor() 动态设置 sheet 圆角背景,适配应用级暗色主题
 * 已知上限：sheet 圆角固定值 | 升级路径：后续抽取 applyAppSheetBackground 扩展函数
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

    private var _binding: DialogHighlightStyleBinding? = null
    private val binding get() = _binding!!

    private val styleHost get() = resolveStyleHost(parentFragment, activity)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogHighlightStyleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        // 修复：应用级暗色主题不激活 values-night 资源, 需动态设置 sheet 背景色
        // 原版用 applyAppSheetBackground 扩展函数（AppColorScheme.current.surfaceContainerLow）
        // 当前项目无此扩展, 改用 ThemeStore.backgroundColor() + 圆角背景
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.let { sheet ->
                val radius = resources.getDimension(R.dimen.corner_large)
                sheet.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
                    setColor(ThemeStore.backgroundColor())
                }
                sheet.clipToOutline = true
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (styleHost == null) {   // 宿主丢失(如配置变更), 关闭以免空面板
            dismiss()
            return
        }
        buildPresets()
        buildChannels()
        bindFontRow()
        // 修复：应用级暗色主题下 @color/primaryText/@color/secondaryText 返回浅色值
        // 在动态 view 添加完成后, 递归设置文字颜色跟随主题
        applyThemeColors(view)
        refresh()
    }

    /** 递归遍历 view 树, 给 TextView/CheckBox 设置主题文字颜色 */
    private fun applyThemeColors(view: View) {
        val primaryColor = ThemeStore.textColorPrimary(requireContext())
        val secondaryColor = ThemeStore.textColorSecondary(requireContext())
        if (view is TextView) {
            // tv_extra 保留 accent 色（点击提示色）, 其他用 primary
            if (view.id != R.id.tv_extra) {
                view.setTextColor(primaryColor)
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyThemeColors(view.getChildAt(i))
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun cur(): HighlightStyle = styleHost?.currentHighlightStyle() ?: HighlightStyle()

    private fun apply(s: HighlightStyle) {
        styleHost?.onHighlightStyleChanged(s)
        refresh()
    }

    private fun buildPresets() {
        val ctx = requireContext()
        HighlightStyles.presets.forEach { preset ->
            val tv = TextView(ctx).apply {
                text = "●"
                textSize = 20f
                setPadding(18, 8, 18, 8)
                setTextColor(
                    preset.fill.takeIf { it != 0 }
                        ?: preset.textColor.takeIf { it != 0 }
                        ?: preset.underline?.color?.takeIf { it != 0 }
                        ?: preset.strike?.color?.takeIf { it != 0 }
                        ?: preset.box?.color?.takeIf { it != 0 }
                        ?: preset.emphasis?.color?.takeIf { it != 0 }
                        ?: 0xFF888888.toInt()
                )
                setOnClickListener { apply(preset) }
            }
            binding.flPresets.addView(tv)
        }
    }

    private data class Channel(
        val labelRes: Int,
        val dialogId: Int,
        val withAlpha: Boolean,
        val isOn: (HighlightStyle) -> Boolean,
        val color: (HighlightStyle) -> Int,
        val toggle: (HighlightStyle, Boolean) -> HighlightStyle,
        val extra: ((HighlightStyle) -> String)? = null,
        val onExtra: ((HighlightStyle) -> HighlightStyle)? = null
    )

    private val channels by lazy {
        listOf(
            Channel(R.string.highlight_bg_color, HighlightActionMenu.HL_FILL, true,
                { it.fill != 0 }, { it.fill },
                { s, on -> s.copy(fill = if (on) (if (s.fill != 0) s.fill else 0x80FFF176.toInt()) else 0) }),
            Channel(R.string.highlight_text_color, HighlightActionMenu.HL_TEXT, false,
                { it.textColor != 0 }, { it.textColor },
                { s, on -> s.copy(textColor = if (on) (if (s.textColor != 0) s.textColor else 0xFFE53935.toInt()) else 0) }),
            Channel(R.string.highlight_bold, -1, false,
                { it.bold }, { 0 }, { s, on -> s.copy(bold = on) }),
            Channel(R.string.highlight_italic, -1, false,
                { it.italic }, { 0 }, { s, on -> s.copy(italic = on) }),
            Channel(R.string.highlight_underline, HighlightActionMenu.HL_UNDERLINE, false,
                { it.underline != null }, { it.underline?.color ?: 0 },
                { s, on -> s.copy(underline = if (on) (s.underline ?: Underline()) else null) },
                extra = { s -> kindLabel(s.underline?.kind) },
                onExtra = { s -> s.copy(underline = (s.underline ?: Underline()).let { it.copy(kind = nextKind(it.kind)) }) }),
            Channel(R.string.highlight_strike, HighlightActionMenu.HL_STRIKE, false,
                { it.strike != null }, { it.strike?.color ?: 0 },
                { s, on -> s.copy(strike = if (on) (s.strike ?: Deco()) else null) }),
            Channel(R.string.highlight_box, HighlightActionMenu.HL_BOX, false,
                { it.box != null }, { it.box?.color ?: 0 },
                { s, on -> s.copy(box = if (on) (s.box ?: Deco()) else null) }),
            Channel(R.string.highlight_emphasis, HighlightActionMenu.HL_EMPHASIS, false,
                { it.emphasis != null }, { it.emphasis?.color ?: 0 },
                { s, on -> s.copy(emphasis = if (on) (s.emphasis ?: Deco()) else null) })
        )
    }

    private val rows = arrayListOf<ItemHighlightChannelBinding>()

    private fun buildChannels() {
        val inflater = layoutInflater
        channels.forEach { ch ->
            val rb = ItemHighlightChannelBinding.inflate(inflater, binding.llChannels, false)
            rb.cbChannel.setText(ch.labelRes)
            rb.cbChannel.setOnClickListener {
                apply(ch.toggle(cur(), rb.cbChannel.isChecked))
            }
            rb.vSwatch.setOnClickListener {
                if (ch.dialogId != -1) styleHost?.pickHighlightColor(ch.dialogId, ch.color(cur()), ch.withAlpha)
            }
            rb.tvExtra.setOnClickListener {
                ch.onExtra?.let { apply(it(cur())) }
            }
            binding.llChannels.addView(rb.root)
            rows.add(rb)
        }
    }

    private fun bindFontRow() {
        binding.llHighlightFont.setOnClickListener {
            styleHost?.pickHighlightFont(cur().fontPath)
        }
        binding.llHighlightFont.setOnLongClickListener {
            if (cur().fontPath.isNotEmpty()) apply(cur().copy(fontPath = ""))
            true
        }
    }

    /** 把字体路径转成可读名(content uri 解码后取末段文件名);空=默认 */
    private fun fontDisplayName(path: String): String {
        if (path.isEmpty()) return getString(R.string.highlight_font_default)
        return Uri.decode(path)?.substringAfterLast('/')?.ifBlank { path } ?: path
    }

    fun refresh() {
        if (_binding == null) return
        val s = cur()
        channels.forEachIndexed { i, ch ->
            val rb = rows[i]
            rb.cbChannel.isChecked = ch.isOn(s)
            rb.vSwatch.visibility = if (ch.dialogId != -1 && ch.isOn(s)) View.VISIBLE else View.GONE
            if (ch.dialogId != -1 && ch.isOn(s)) {
                val c = ch.color(s)
                rb.vSwatch.setBackgroundColor(if (c != 0) c else 0xFF888888.toInt())
            }
            val ex = ch.extra?.invoke(s)
            if (ex != null && ch.isOn(s)) {
                rb.tvExtra.visibility = View.VISIBLE
                rb.tvExtra.text = ex
            } else {
                rb.tvExtra.visibility = View.GONE
            }
        }
        binding.tvHighlightFontValue.text = fontDisplayName(s.fontPath)
    }

    private fun kindLabel(kind: Kind?): String = when (kind) {
        Kind.WAVY -> getString(R.string.highlight_underline_wavy)
        Kind.DASHED -> getString(R.string.highlight_underline_dashed)
        Kind.DOTTED -> getString(R.string.highlight_underline_dotted)
        Kind.DOUBLE -> getString(R.string.highlight_underline_double)
        else -> getString(R.string.highlight_underline_solid)
    }

    private fun nextKind(kind: Kind): Kind {
        val all = Kind.entries
        return all[(all.indexOf(kind) + 1) % all.size]
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
