package io.legado.app.ui.highlight

import android.os.Bundle
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.HighlightRulePreview
import io.legado.app.ui.book.read.config.HighlightRule
import io.legado.app.ui.book.read.config.HighlightRuleGroupStore
import io.legado.app.ui.book.read.config.HighlightRuleStore
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.labelXSmall
import io.legado.app.ui.theme.bodyTertiary

/**
 * 高亮预设规则弹框（Compose 化，底部弹出）。
 * 原 View 版继承 BaseDialogFragment + dialog_highlight_preset_rule 布局；
 * 迁移后继承 [ComposeDialogFragment]，LazyColumn 渲染 `defaultPresetRules`，
 * 预览描线由 `HighlightRulePreview.build` 的 Span 转为 [AnnotatedString] 保持等价。
 * T-B4：保留内置 id（避免新 id 落入 ViewModel.update 的静默丢弃分支），
 * 重复添加走 replace 刷新防副本堆积。
 */
class HighlightPresetRuleDialog @JvmOverloads constructor(
    private val defaultGroup: String? = null,
    private val onAddRule: (HighlightRule) -> Unit = {},
) : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management
    override val dialogGravity: Int = Gravity.BOTTOM

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                val context = LocalContext.current
                val presetRules = remember { HighlightRuleStore.defaultPresetRules(context) }
                AppDialogFrame(
                    title = stringResource(R.string.highlight_rule_preset),
                    message = stringResource(R.string.highlight_rule_preset_subtitle),
                    // content 内 LazyColumn 自身滚动，外层禁用 verticalScroll（嵌套会收到无限高度约束崩溃）
                    scrollContent = false,
                    content = {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(presetRules, key = { it.id }) { item ->
                                PresetRuleCard(
                                    rule = item,
                                    accent = style.accent,
                                    primaryText = style.primaryText,
                                    secondaryText = style.secondaryText,
                                    fieldSurface = style.fieldSurface,
                                    stroke = style.stroke,
                                    panelRadius = style.panelRadius,
                                    onAdd = {
                                        val groupToUse = defaultGroup
                                            ?: HighlightRuleGroupStore.DEFAULT_GROUP
                                        onAddRule(item.copy(group = groupToUse))
                                        dismiss()
                                    }
                                )
                            }
                        }
                    },
                    actions = {
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.close),
                            palette = style.toMiuixPalette(),
                            onClick = { dismissAllowingStateLoss() }
                        )
                    }
                )
            }
        }
    }

    @Composable
    private fun PresetRuleCard(
        rule: HighlightRule,
        accent: Color,
        primaryText: Color,
        secondaryText: Color,
        fieldSurface: Color,
        stroke: Color,
        panelRadius: Dp,
        onAdd: () -> Unit
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            shape = RoundedCornerShape(panelRadius),
            color = fieldSurface,
            contentColor = primaryText
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = rule.name,
                            color = primaryText,
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = rule.displayPattern(),
                            color = secondaryText,
                            fontSize = MaterialTheme.typography.labelSmall.fontSize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clickable { onAdd() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = stringResource(R.string.add),
                            tint = accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.preview),
                    color = secondaryText,
                    fontSize = MaterialTheme.typography.labelXSmall.fontSize
                )
                Spacer(modifier = Modifier.height(2.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(panelRadius),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, stroke)
                ) {
                    Text(
                        text = rule.toPreviewAnnotatedString(),
                        color = primaryText,
                        fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

/** 将 [HighlightRulePreview.build] 的 Foreground/Background Span 转为 Compose AnnotatedString 描线预览 */
private fun HighlightRule.toPreviewAnnotatedString(): AnnotatedString {
    val preview = HighlightRulePreview.build(this)
    val text = preview.toString()
    val spanned = preview as? Spanned
    return buildAnnotatedString {
        append(text)
        if (spanned != null) {
            spanned.getSpans(0, text.length, ForegroundColorSpan::class.java).forEach { span ->
                addStyle(
                    SpanStyle(color = Color(span.foregroundColor)),
                    spanned.getSpanStart(span),
                    spanned.getSpanEnd(span)
                )
            }
            spanned.getSpans(0, text.length, BackgroundColorSpan::class.java).forEach { span ->
                addStyle(
                    SpanStyle(background = Color(span.backgroundColor)),
                    spanned.getSpanStart(span),
                    spanned.getSpanEnd(span)
                )
            }
        }
    }
}
