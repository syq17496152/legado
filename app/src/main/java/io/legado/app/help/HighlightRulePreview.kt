package io.legado.app.help

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import io.legado.app.ui.book.read.config.HighlightRule

/**
 * F-P1-2 高亮规则预览生成器（借鉴蛋蛋Max,简化版）
 *
 * 简化说明：只用 BackgroundColorSpan + ForegroundColorSpan 显示匹配范围和颜色
 * 已知上限：不显示下划线/着重号/背景图等高级样式效果
 * 升级路径：后续移植 SolidUnderlineSpan/WaveUnderlineSpan/DashUnderlineSpan 等 Span 类后可恢复完整预览
 */
object HighlightRulePreview {

    fun build(rule: HighlightRule): CharSequence {
        val text = rule.normalizedSampleText()
        val spannable = SpannableStringBuilder(text)
        val regex = kotlin.runCatching { Regex(rule.pattern) }.getOrNull() ?: return spannable
        regex.findAll(text).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            val textColor = rule.textColor ?: 0xFF111111.toInt()
            // 文字颜色
            spannable.setSpan(
                ForegroundColorSpan(textColor),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            // 背景颜色（半透明,基于 textColor 或 underlineColor）
            val baseColor = rule.underlineColor ?: rule.textColor ?: 0xFF63C37D.toInt()
            spannable.setSpan(
                BackgroundColorSpan((0x33 shl 24) or (baseColor and 0x00FFFFFF)),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }
}
