package io.legado.app.ui.association

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.sendToClip

/**
 * F355：在线导入确认弹窗（形态统一）。
 *
 * 替换原 `ParagraphRuleOnlineImportDialog`（只在段落规则包用、预览是**一整块拼接文本**）：
 * 同一条预览结构（kv 行 + URL 单行省略/展开/复制 + 摘要块）同时服务段落规则包与气泡包，
 * 冲突策略区仅在 `conflictCount >= 0` 时出现 ⇒ 气泡包复用同一弹窗而不出现无关选项。
 * 背景：原 O6 正文 5 行拼接文本里 URL 过长会撑破弹窗且不可复制（修复 2）。
 */
class OnlineImportConfirmDialog : ComposeDialogFragment() {

    interface Callback {
        fun onOnlineImportConfirmed(strategy: ParagraphRuleConflictStrategy)
        fun onOnlineImportCancelled()
    }

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private var confirmed = false

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!confirmed) (activity as? Callback)?.onOnlineImportCancelled()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                val palette = style.toMiuixPalette()
                val conflictCount = args.getInt(ARG_CONFLICT_COUNT, NO_CONFLICT_SECTION)
                var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
                var expandedFinalUrl by rememberSaveable { mutableStateOf(false) }
                val strategies = listOf(
                    ParagraphRuleConflictStrategy.RENAME,
                    ParagraphRuleConflictStrategy.OVERWRITE,
                    ParagraphRuleConflictStrategy.SKIP
                )

                AppDialogFrame(
                    title = stringResource(R.string.online_import_confirm_title),
                    messageInContent = true,
                    content = {
                        OnlineImportPreviewContent(
                            style = style,
                            typeName = args.getString(ARG_TYPE_NAME).orEmpty(),
                            sourceUrl = args.getString(ARG_SOURCE_URL).orEmpty(),
                            finalUrl = args.getString(ARG_FINAL_URL).orEmpty(),
                            sizeText = args.getString(ARG_SIZE_TEXT).orEmpty(),
                            privateNetwork = args.getBoolean(ARG_PRIVATE_NETWORK),
                            summary = args.getString(ARG_SUMMARY),
                            warning = args.getString(ARG_WARNING),
                            expandedFinalUrl = expandedFinalUrl,
                            onToggleFinalUrl = { expandedFinalUrl = !expandedFinalUrl }
                        )
                        if (conflictCount > 0) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(
                                    R.string.paragraph_import_conflict_message,
                                    conflictCount
                                ),
                                color = style.primaryText,
                                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                val labels = listOf(
                                    stringResource(R.string.paragraph_import_conflict_rename),
                                    stringResource(R.string.paragraph_import_conflict_overwrite),
                                    stringResource(R.string.paragraph_import_conflict_skip)
                                )
                                val descriptions = listOf(
                                    stringResource(R.string.paragraph_import_conflict_rename_description),
                                    stringResource(R.string.paragraph_import_conflict_overwrite_description),
                                    stringResource(R.string.paragraph_import_conflict_skip_description)
                                )
                                labels.forEachIndexed { index, label ->
                                    LegadoMiuixChoiceRow(
                                        text = label,
                                        description = descriptions[index],
                                        selected = selectedIndex == index,
                                        palette = palette,
                                        onClick = { selectedIndex = index }
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.cancel),
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                        if (activity is Callback) {
                            Spacer(modifier = Modifier.width(8.dp))
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.import_),
                                palette = palette,
                                onClick = {
                                    val strategy = if (conflictCount > 0) {
                                        strategies[selectedIndex]
                                    } else {
                                        ParagraphRuleConflictStrategy.RENAME
                                    }
                                    confirmed = true
                                    (activity as? Callback)
                                        ?.onOnlineImportConfirmed(strategy)
                                    dismissAllowingStateLoss()
                                },
                                primary = true,
                                cornerRadius = style.actionRadius
                            )
                        }
                    }
                )
            }
        }
    }

    companion object {
        const val NO_CONFLICT_SECTION = -1

        fun create(
            typeName: String,
            sourceUrl: String,
            finalUrl: String,
            sizeText: String,
            privateNetwork: Boolean,
            summary: String? = null,
            warning: String? = null,
            conflictCount: Int = NO_CONFLICT_SECTION
        ) = OnlineImportConfirmDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_TYPE_NAME, typeName)
                putString(ARG_SOURCE_URL, sourceUrl)
                putString(ARG_FINAL_URL, finalUrl)
                putString(ARG_SIZE_TEXT, sizeText)
                putBoolean(ARG_PRIVATE_NETWORK, privateNetwork)
                putString(ARG_SUMMARY, summary)
                putString(ARG_WARNING, warning)
                putInt(ARG_CONFLICT_COUNT, conflictCount)
            }
        }

        private const val ARG_TYPE_NAME = "typeName"
        private const val ARG_SOURCE_URL = "sourceUrl"
        private const val ARG_FINAL_URL = "finalUrl"
        private const val ARG_SIZE_TEXT = "sizeText"
        private const val ARG_PRIVATE_NETWORK = "privateNetwork"
        private const val ARG_SUMMARY = "summary"
        private const val ARG_WARNING = "warning"
        private const val ARG_CONFLICT_COUNT = "conflictCount"
    }
}

/**
 * 修复 2：预览 kv 结构 + URL 单行省略/展开/复制。
 *
 * 原始来源只给「复制」（不展开：它是用户自己给的链接，最长也一屏可读）；
 * 最终地址可能被 CDN 重写带签名参数 ⇒ 给「展开 ▾」中缀展开 + 复制。
 */
@Composable
private fun OnlineImportPreviewContent(
    style: AppDialogStyle,
    typeName: String,
    sourceUrl: String,
    finalUrl: String,
    sizeText: String,
    privateNetwork: Boolean,
    summary: String?,
    warning: String?,
    expandedFinalUrl: Boolean,
    onToggleFinalUrl: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val context = LocalContext.current
        PreviewKeyValueRow(
            style = style,
            label = stringResource(R.string.online_import_field_type),
            value = typeName
        )
        PreviewUrlRow(
            style = style,
            label = stringResource(R.string.online_import_field_source),
            url = sourceUrl,
            actionText = stringResource(R.string.online_import_copy),
            onAction = { context.sendToClip(sourceUrl) }
        )
        PreviewUrlRow(
            style = style,
            label = stringResource(R.string.online_import_field_final),
            url = finalUrl,
            actionText = stringResource(
                if (expandedFinalUrl) R.string.online_import_collapse else R.string.online_import_expand
            ),
            onAction = onToggleFinalUrl
        )
        if (expandedFinalUrl && finalUrl.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = finalUrl,
                color = style.primaryText,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shapeOf(style))
                    .background(style.fieldSurface)
                    .padding(horizontal = 9.dp, vertical = 7.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        PreviewKeyValueRow(
            style = style,
            label = stringResource(R.string.online_import_field_size),
            value = sizeText
        )
        PreviewKeyValueRow(
            style = style,
            label = stringResource(R.string.online_import_field_private),
            value = stringResource(if (privateNetwork) R.string.yes else R.string.no)
        )
        if (!summary.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = summary,
                color = style.primaryText,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shapeOf(style))
                    .background(style.fieldSurface)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
        if (!warning.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = warning,
                color = style.danger,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                lineHeight = MaterialTheme.typography.bodySmall.lineHeight
            )
        }
    }
}

@Composable
private fun PreviewKeyValueRow(style: AppDialogStyle, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = style.secondaryText,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            modifier = Modifier.width(66.dp)
        )
        Text(
            text = value,
            color = style.primaryText,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PreviewUrlRow(
    style: AppDialogStyle,
    label: String,
    url: String,
    actionText: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = style.secondaryText,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            modifier = Modifier.width(66.dp)
        )
        Text(
            text = url,
            color = style.primaryText,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = actionText,
            color = style.accent,
            fontSize = MaterialTheme.typography.labelSmall.fontSize,
            modifier = Modifier
                .clip(shapeOf(style))
                .clickable(onClick = onAction)
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

private fun shapeOf(style: AppDialogStyle) = RoundedCornerShape(style.panelRadius)

/**
 * F356：在线导入错误弹窗（修复 3 + 优化 5 的承载件）。
 *
 * - 失败态（[allowRetry]）：语义原因 + 「重试」（主）+「复制错误详情」+「关闭」——此前失败只有单按钮即 finish，
 *   用户无法留在原地排查/重试（A3-3 失败给重试入口）；
 * - Invalid 路由：原因 + **原始链接块**（单行省略 + 复制链接），让排障信息可带离（此前链接只存在于 intent）。
 */
class OnlineImportErrorDialog : ComposeDialogFragment() {

    interface Callback {
        fun onOnlineImportRetry()
    }

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                val palette = style.toMiuixPalette()
                val reason = args.getString(ARG_REASON).orEmpty()
                val rawLink = args.getString(ARG_RAW_LINK)
                val copyDetail = args.getString(ARG_COPY_DETAIL)
                val allowRetry = args.getBoolean(ARG_ALLOW_RETRY)

                AppDialogFrame(
                    title = stringResource(R.string.error),
                    messageInContent = true,
                    content = {
                        Text(
                            text = reason,
                            color = style.primaryText,
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
                        )
                        if (!rawLink.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(shapeOf(style))
                                    .background(style.fieldSurface)
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.online_import_raw_link_label),
                                    color = style.secondaryText,
                                    fontSize = MaterialTheme.typography.labelSmall.fontSize
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = rawLink,
                                        color = style.primaryText,
                                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.online_import_copy_link),
                                        color = style.accent,
                                        fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                        modifier = Modifier
                                            .clip(shapeOf(style))
                                            .clickable { requireContext().sendToClip(rawLink) }
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        // 「复制错误详情」在有可复制内容时都给（修复 3：失败此前只有单按钮即 finish，排障信息带不走）
                        if (!copyDetail.isNullOrBlank()) {
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.online_import_copy_error),
                                palette = palette,
                                onClick = { requireContext().sendToClip(copyDetail) },
                                cornerRadius = style.actionRadius
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        if (allowRetry) {
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.cancel),
                                palette = palette,
                                onClick = { dismissAllowingStateLoss() },
                                cornerRadius = style.actionRadius
                            )
                            if (activity is Callback) {
                                Spacer(modifier = Modifier.width(8.dp))
                                LegadoMiuixActionButton(
                                    text = stringResource(R.string.online_import_retry),
                                    palette = palette,
                                    onClick = {
                                        (activity as? Callback)?.onOnlineImportRetry()
                                        dismissAllowingStateLoss()
                                    },
                                    primary = true,
                                    cornerRadius = style.actionRadius
                                )
                            }
                        } else {
                            LegadoMiuixActionButton(
                                text = stringResource(android.R.string.ok),
                                palette = palette,
                                onClick = { dismissAllowingStateLoss() },
                                primary = true,
                                cornerRadius = style.actionRadius
                            )
                        }
                    }
                )
            }
        }
    }

    companion object {
        fun create(
            reason: String,
            rawLink: String? = null,
            copyDetail: String? = null,
            allowRetry: Boolean = false
        ) = OnlineImportErrorDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_REASON, reason)
                putString(ARG_RAW_LINK, rawLink)
                putString(ARG_COPY_DETAIL, copyDetail)
                putBoolean(ARG_ALLOW_RETRY, allowRetry)
            }
        }

        private const val ARG_REASON = "reason"
        private const val ARG_RAW_LINK = "rawLink"
        private const val ARG_COPY_DETAIL = "copyDetail"
        private const val ARG_ALLOW_RETRY = "allowRetry"
    }
}