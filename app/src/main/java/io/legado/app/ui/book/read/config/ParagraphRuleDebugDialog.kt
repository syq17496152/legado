package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.help.IntentData
import io.legado.app.ui.widget.code.CodeView
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.components.CollapseSectionHeader
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppSemanticColors
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.disableEdit
import io.legado.app.utils.dpToPx
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi

/**
 * F62（段落规则编辑 · 优化 1）：调试结果结构化弹窗。
 *
 * 原实现把 book/chapter/rawUrl/logs/result（截断 4000 字）拼成一整块文本塞进确认框，
 * "规则起没起作用"的关键答案埋在文本流里。本弹窗改为三段：
 * ①**概要**（默认展开）——状态徽标 + 书籍/章节 + 段落数/总长度/图片标签的**前→后对比**；
 * ②**日志**（默认折叠）；③**正文预览**（默认折叠，等宽高亮、**不截断**）；
 * 底部「复制结果」保留原复制路径（正文全文）。
 */
class ParagraphRuleDebugDialog() : ComposeDialogFragment() {

    override val dialogHeight: Int = ViewGroup.LayoutParams.MATCH_PARENT

    constructor(
        success: Boolean,
        bookName: String,
        chapterTitle: String,
        beforeParagraphs: Int,
        afterParagraphs: Int,
        beforeLength: Int,
        afterLength: Int,
        imageTags: Int,
        logs: String,
        content: String,
        errorMessage: String? = null,
    ) : this() {
        arguments = Bundle().apply {
            putBoolean("success", success)
            putString("bookName", bookName)
            putString("chapterTitle", chapterTitle)
            putInt("beforeParagraphs", beforeParagraphs)
            putInt("afterParagraphs", afterParagraphs)
            putInt("beforeLength", beforeLength)
            putInt("afterLength", afterLength)
            putInt("imageTags", imageTags)
            // 长文本走 IntentData（Bundle 有 1MB 上限，正文可能很长且不再截断）
            putString("logs", IntentData.put(logs))
            putString("content", IntentData.put(content))
            putString("errorMessage", errorMessage)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        val success = args.getBoolean("success")
        val bookName = args.getString("bookName").orEmpty()
        val chapterTitle = args.getString("chapterTitle").orEmpty()
        val beforeParagraphs = args.getInt("beforeParagraphs")
        val afterParagraphs = args.getInt("afterParagraphs")
        val beforeLength = args.getInt("beforeLength")
        val afterLength = args.getInt("afterLength")
        val imageTags = args.getInt("imageTags")
        val logs = args.getString("logs")?.let { IntentData.get<String>(it) }.orEmpty()
        val content = args.getString("content")?.let { IntentData.get<String>(it) }.orEmpty()
        val errorMessage = args.getString("errorMessage")
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                var logsExpanded by remember { mutableStateOf(false) }
                var previewExpanded by remember { mutableStateOf(false) }
                AppDialogFrame(
                    title = stringResource(R.string.debug),
                    content = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            DebugStatusLine(success = success, errorMessage = errorMessage)
                            DebugKeyValue(stringResource(R.string.paragraph_rule_debug_book), bookName)
                            DebugKeyValue(
                                stringResource(R.string.paragraph_rule_debug_chapter),
                                chapterTitle,
                            )
                            DebugMetricRow(
                                label = stringResource(R.string.paragraph_rule_debug_paragraphs),
                                value = "$beforeParagraphs → $afterParagraphs",
                            )
                            DebugMetricRow(
                                label = stringResource(R.string.paragraph_rule_debug_length),
                                value = "$beforeLength → $afterLength",
                            )
                            DebugMetricRow(
                                label = stringResource(R.string.paragraph_rule_debug_image_tags),
                                value = imageTags.toString(),
                            )
                            CollapseSectionHeader(
                                title = stringResource(R.string.paragraph_rule_debug_logs),
                                expanded = logsExpanded,
                                onToggle = { logsExpanded = !logsExpanded },
                                hint = logs.lineSequence().count { it.isNotBlank() }.toString(),
                            )
                            if (logsExpanded) {
                                Text(
                                    text = logs.ifBlank {
                                        stringResource(R.string.paragraph_rule_debug_logs_empty)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(start = 24.dp, bottom = 6.dp),
                                )
                            }
                            CollapseSectionHeader(
                                title = stringResource(R.string.paragraph_rule_debug_preview),
                                expanded = previewExpanded,
                                onToggle = { previewExpanded = !previewExpanded },
                                hint = content.length.toString(),
                            )
                            if (previewExpanded) {
                                DebugCodePreview(content = content)
                            }
                        }
                    },
                    actions = {
                        val palette = style.toMiuixPalette()
                        val context = LocalContext.current
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.paragraph_rule_debug_copy),
                            palette = palette,
                            onClick = {
                                context.sendToClip(content)
                                context.toastOnUi(R.string.paragraph_rule_debug_copied)
                            },
                            cornerRadius = style.actionRadius,
                        )
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.close),
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            primary = true,
                            cornerRadius = style.actionRadius,
                        )
                    }
                )
            }
        }
    }

    /** 状态徽标行：✓ 处理成功（success 单源色）/ ✗ 处理失败（danger 单源色）+ 失败原因首行 */
    @Composable
    private fun DebugStatusLine(success: Boolean, errorMessage: String?) {
        val color = if (success) colorResource(R.color.success) else AppSemanticColors.Danger
        Column(modifier = Modifier.padding(bottom = 6.dp)) {
            Text(
                text = if (success) {
                    "✓ " + stringResource(R.string.paragraph_rule_debug_status_ok)
                } else {
                    "✗ " + stringResource(R.string.paragraph_rule_debug_status_fail)
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
            errorMessage?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppSemanticColors.Danger,
                    maxLines = 3,
                )
            }
        }
    }

    /** 键值行（书籍/章节等长文本，值可换行） */
    @Composable
    private fun DebugKeyValue(label: String, value: String) {
        val palette = rememberAppSettingPalette()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = palette.secondaryText,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = palette.primaryText,
            )
        }
    }

    /** 指标行：前→后对比（概要区核心，直接回答"规则起没起作用"） */
    @Composable
    private fun DebugMetricRow(label: String, value: String) {
        val palette = rememberAppSettingPalette()
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = palette.secondaryText,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = palette.primaryText,
            )
        }
    }

    /** 正文预览：只读 CodeView（JS 高亮），限高可滚、不截断内容 */
    @Composable
    private fun DebugCodePreview(content: String) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 360.dp),
            factory = { ctx ->
                CodeView(ctx).apply {
                    val pad = 8.dpToPx()
                    setPadding(pad, pad, pad, pad)
                    disableEdit()
                    addJsPattern()
                    setText(content)
                }
            },
        )
    }
}