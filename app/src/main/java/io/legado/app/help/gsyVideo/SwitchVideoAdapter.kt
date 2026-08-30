package io.legado.app.help.gsyVideo

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.themeDividerColorOrDefault
import io.legado.app.utils.ColorUtils

class SwitchVideoAdapter<T>(
    context: Context,
    private val dataList: List<T>,
    private val titleProvider: (T) -> String = { it.toString() },
    // video-player-theme-unify：当前选中集索引，用于高亮当前集（同 ChoiceSpeedDialog 的 currentSpeed）
    private val currentSelection: Int = -1
) : ArrayAdapter<T>(context, 0, dataList) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.switch_video_dialog_item, parent, false)
        val textView = view.findViewById<TextView>(R.id.text1)
        val item = dataList[position]
        textView.text = titleProvider(item)
        if (position == currentSelection) {
            // video-player-theme-unify：当前集高亮动态取 ThemeStore.accentColor + 20% 透明圆角背景
            val accentColor = ThemeStore.accentColor(context)
            textView.setTextColor(accentColor)
            textView.setTypeface(textView.typeface, Typeface.BOLD)
            val radius = UiCorner.panelRadius(context).toFloat()
            textView.background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(
                    Color.argb(
                        0x33,
                        Color.red(accentColor),
                        Color.green(accentColor),
                        Color.blue(accentColor)
                    )
                )
            }
        } else {
            // 未选中文字用 ThemeStore.textColorPrimary（日/夜自适应）
            textView.setTextColor(ThemeStore.textColorPrimary(context))
            textView.setTypeface(textView.typeface, Typeface.NORMAL)
            // video-player-image-enhance 样式专项：未选中背景动态取色（替换静态 card_video_background
            // water 色板），fieldSurface=背景色混 accent + UiCorner.actionRadius + divider 描边，主题联动
            val surface = ThemeStore.backgroundColor(context)
            val accent = ThemeStore.accentColor(context)
            val field = ColorUtils.blendColors(
                surface,
                accent,
                if (AppConfig.isNightTheme) 0.10f else 0.05f
            )
            val radius = UiCorner.actionRadius(context).toFloat()
            textView.background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(field)
                setStroke(
                    context.resources.displayMetrics.density.toInt().coerceAtLeast(1),
                    context.themeDividerColorOrDefault()
                )
            }
        }
        return view
    }
}