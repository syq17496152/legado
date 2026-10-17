package io.legado.app.ui.widget.compose

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.config.ReadTipTemplate
import io.legado.app.ui.theme.LegadoTheme

/**
 * 页眉页脚「自定义模板」编辑器（R12）。
 *
 * 交互要点（spec R12-1 硬要求）：**可视化插入占位符** —— 变量以 chip 按钮呈现，
 * 点选即插入到**光标处**（非手写 `\${...}` 串）；下方实时预览按样本值渲染，
 * 未知占位符原样保留（与阅读页同一渲染引擎 `ReadTipTemplate.render`）。
 *
 * 编辑框用 `TextFieldValue` 承载（保留光标），插入语义本身是纯函数
 * `ReadTipTemplate.insertPlaceholder` ⇒ 可单测、可回退（用户手动改串不被回写）。
 */
class ComposeTipTemplateDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private var onPositive: ((String) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    DismissWhenCallbackMissing(
                        missing = onPositive == null,
                        dismiss = ::dismissAllowingStateLoss
                    )
                    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
                        mutableStateOf(TextFieldValue(args.getString(ARG_INITIAL_TEXT).orEmpty()))
                    }
                    val style = rememberAppDialogStyle()
                    AppDialogFrame(
                        title = args.getString(ARG_TITLE).orEmpty(),
                        message = stringResource(R.string.tip_template_help),
                        content = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                AppDialogValueTextField(
                                    value = field,
                                    onValueChange = { field = it },
                                    label = stringResource(R.string.tip_template_label),
                                    minLines = 2,
                                    maxLines = 4
                                )
                                TipTemplatePlaceholderChips(
                                    style = style,
                                    onInsert = { name ->
                                        val (text, cursor) = ReadTipTemplate.insertPlaceholder(
                                            text = field.text,
                                            selectionStart = field.selection.start,
                                            selectionEnd = field.selection.end,
                                            name = name
                                        )
                                        field = TextFieldValue(text, TextRange(cursor))
                                    }
                                )
                                TipTemplatePreview(text = field.text, style = style)
                            }
                        },
                        actions = {
                            val palette = style.toMiuixPalette()
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.cancel),
                                palette = palette,
                                onClick = { dismissAllowingStateLoss() },
                                cornerRadius = style.actionRadius
                            )
                            onPositive?.let { callback ->
                                Spacer(modifier = Modifier.width(8.dp))
                                LegadoMiuixActionButton(
                                    text = stringResource(R.string.ok),
                                    palette = palette,
                                    onClick = {
                                        val current = field.text
                                        dismissAllowingStateLoss()
                                        callback.invoke(current)
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
    }

    companion object {
        fun create(
            title: String,
            initialValue: String,
            onPositive: (String) -> Unit
        ): ComposeTipTemplateDialog {
            return ComposeTipTemplateDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_INITIAL_TEXT, initialValue)
                }
                this.onPositive = onPositive
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_INITIAL_TEXT = "initialText"
    }
}

/** 占位符 chip 行（点选插入；变量白名单单源 = `ReadTipTemplate.variables`） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TipTemplatePlaceholderChips(
    style: AppDialogStyle,
    onInsert: (String) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ReadTipTemplate.variables.forEach { name ->
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(style.actionRadius))
                    .background(style.fieldSurface)
                    .clickable { onInsert(name) },
                shape = RoundedCornerShape(style.actionRadius),
                color = style.fieldSurface,
                contentColor = style.primaryText,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Text(
                    text = name,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    color = style.primaryText,
                    fontSize = MaterialTheme.typography.labelSmall.fontSize,
                    fontFamily = style.bodyFontFamily,
                    maxLines = 1
                )
            }
        }
    }
}

/** 实时预览（样本值渲染；空模板给出明确回执，避免"什么都没显示"被当成故障） */
@Composable
private fun TipTemplatePreview(text: String, style: AppDialogStyle) {
    val rendered = ReadTipTemplate.render(text, ReadTipTemplate.previewValues())
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.tip_template_preview),
            color = style.secondaryText,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(style.actionRadius),
            color = style.fieldSurface,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Text(
                text = rendered.ifBlank { stringResource(R.string.tip_template_empty) },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                color = if (rendered.isBlank()) style.secondaryText else style.accent,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                fontFamily = style.bodyFontFamily,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
