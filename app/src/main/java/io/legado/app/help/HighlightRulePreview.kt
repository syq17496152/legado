package io.legado.app.help

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import io.legado.app.ui.book.read.config.HighlightRule

/**
 * F-P1-2 高亮规则预览生成器（借鉴蛋蛋Max,简化版）
 *
 * R12.2：样式取自 `HighlightRule.toHighlightStyle()`（与阅读页渲染同源，styleJson 优先）。
 * 已支持：背景填充（仅 fill≠0 时绘制）/ 字色 / 加粗 / 斜体 / 下划线 / 删除线。
 * 已知上限：下划线不区分线型（波浪/虚线/双线统一按下划线呈现）、方框/着重号/背景图不预览
 * 升级路径：后续移植 SolidUnderlineSpan/WaveUnderlineSpan/DashUnderlineSpan 等 Span 类后可恢复完整预览
 */
object HighlightRulePreview {

    fun build(rule: HighlightRule): CharSequence {
        val text = rule.normalizedSampleText()
        val spannable = SpannableStringBuilder(text)
        val regex = kotlin.runCatching { Regex(rule.pattern) }.getOrNull() ?: return spannable
        // R12.2：样式与阅读页同源（styleJson 优先，legacy 仅降级输入）
        // R12.4：色板派生样式按当前阅读器态解析，预览与阅读页一致
        val style = HighlightPalette.resolve(rule.toHighlightStyle())
        regex.findAll(text).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            val flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            // 背景：仅在样式确实配置了 fill 时绘制。
            // 修复：原实现恒定叠加 0x33 半透明背景带，导致「无填充的纯下划线规则」预览出现实际渲染中不存在的底色
            if (style.fill != 0) {
                spannable.setSpan(BackgroundColorSpan(style.fill), start, end, flag)
            }
            if (style.textColor != 0) {
                spannable.setSpan(ForegroundColorSpan(style.textColor), start, end, flag)
            }
            if (style.bold) spannable.setSpan(StyleSpan(Typeface.BOLD), start, end, flag)
            if (style.italic) spannable.setSpan(StyleSpan(Typeface.ITALIC), start, end, flag)
            if (style.underline != null) spannable.setSpan(UnderlineSpan(), start, end, flag)
            if (style.strike != null) spannable.setSpan(StrikethroughSpan(), start, end, flag)
        }
        return spannable
    }
}
