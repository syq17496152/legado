package io.legado.app.ui.book.toc.rule

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class TxtTocRuleEditComposeDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private val viewModel by viewModels<TxtTocRuleEditDialog.ViewModel>()

    private val callback: Callback?
        get() = (parentFragment as? Callback) ?: activity as? Callback

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ruleId = arguments?.getLong(ARG_ID, 0L)?.takeIf { it != 0L }

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val context = LocalContext.current
                var loaded by remember { mutableStateOf(false) }
                var name by remember { mutableStateOf("") }
                var rule by remember { mutableStateOf("") }
                var replacement by remember { mutableStateOf("") }
                var example by remember { mutableStateOf("") }

                LaunchedEffect(ruleId) {
                    viewModel.initData(ruleId) { tocRule ->
                        if (tocRule != null) {
                            name = tocRule.name
                            rule = tocRule.rule
                            replacement = tocRule.replacement
                            example = tocRule.example.orEmpty()
                        }
                        loaded = true
                    }
                }

                if (loaded) {
                    TxtTocRuleEditContent(
                        name = name,
                        onNameChange = { name = it },
                        rule = rule,
                        onRuleChange = { rule = it },
                        replacement = replacement,
                        onReplacementChange = { replacement = it },
                        example = example,
                        onExampleChange = { example = it },
                        onSave = {
                            val tocRule = viewModel.tocRule ?: TxtTocRule().also {
                                viewModel.tocRule = it
                            }
                            tocRule.name = name
                            tocRule.rule = rule
                            tocRule.replacement = replacement
                            tocRule.example = example
                            if (checkValid(tocRule)) {
                                callback?.saveTxtTocRule(tocRule)
                                dismissAllowingStateLoss()
                            }
                        },
                        onCopyRule = {
                            val tocRule = viewModel.tocRule ?: TxtTocRule().also {
                                viewModel.tocRule = it
                            }
                            tocRule.name = name
                            tocRule.rule = rule
                            tocRule.replacement = replacement
                            tocRule.example = example
                            context.sendToClip(
                                io.legado.app.utils.GSON.toJson(tocRule)
                            )
                        },
                        onPasteRule = {
                            viewModel.pasteRule { pasted ->
                                name = pasted.name
                                rule = pasted.rule
                                replacement = pasted.replacement
                                example = pasted.example.orEmpty()
                            }
                        },
                        onDismiss = { dismissAllowingStateLoss() }
                    )
                }
            }
        }
    }

    private fun checkValid(tocRule: TxtTocRule): Boolean {
        if (tocRule.name.isEmpty()) {
            context?.toastOnUi("名称不能为空")
            return false
        }
        try {
            Pattern.compile(tocRule.rule, Pattern.MULTILINE)
        } catch (ex: PatternSyntaxException) {
            AppLog.put("正则语法错误或不支持(txt)：${ex.localizedMessage}", ex, true)
            return false
        }
        return true
    }

    companion object {
        private const val ARG_ID = "id"

        fun create(id: Long? = null): TxtTocRuleEditComposeDialog {
            return TxtTocRuleEditComposeDialog().apply {
                if (id != null) {
                    arguments = Bundle().apply {
                        putLong(ARG_ID, id)
                    }
                }
            }
        }
    }

    interface Callback {
        fun saveTxtTocRule(txtTocRule: TxtTocRule)
    }
}

@Composable
private fun TxtTocRuleEditContent(
    name: String,
    onNameChange: (String) -> Unit,
    rule: String,
    onRuleChange: (String) -> Unit,
    replacement: String,
    onReplacementChange: (String) -> Unit,
    example: String,
    onExampleChange: (String) -> Unit,
    onSave: () -> Unit,
    onCopyRule: () -> Unit,
    onPasteRule: () -> Unit,
    onDismiss: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()

    AppDialogFrame(
        title = stringResource(R.string.txt_toc_rule),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TxtTocRuleTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = stringResource(R.string.name),
                    singleLine = true,
                    style = style
                )
                Spacer(modifier = Modifier.height(10.dp))
                TxtTocRuleTextField(
                    value = rule,
                    onValueChange = onRuleChange,
                    label = stringResource(R.string.regex),
                    singleLine = true,
                    style = style
                )
                Spacer(modifier = Modifier.height(10.dp))
                TxtTocRuleTextField(
                    value = replacement,
                    onValueChange = onReplacementChange,
                    label = stringResource(R.string.replace_to_js),
                    minLines = 2,
                    maxLines = 4,
                    style = style
                )
                Spacer(modifier = Modifier.height(10.dp))
                TxtTocRuleTextField(
                    value = example,
                    onValueChange = onExampleChange,
                    label = stringResource(R.string.example),
                    singleLine = true,
                    style = style
                )
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = stringResource(R.string.paste_rule),
                palette = palette,
                onClick = onPasteRule,
                cornerRadius = style.actionRadius
            )
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = stringResource(R.string.copy_rule),
                palette = palette,
                onClick = onCopyRule,
                cornerRadius = style.actionRadius
            )
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = stringResource(R.string.cancel),
                palette = palette,
                onClick = onDismiss,
                cornerRadius = style.actionRadius
            )
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = stringResource(R.string.action_save),
                palette = palette,
                onClick = onSave,
                primary = true,
                cornerRadius = style.actionRadius
            )
        }
    )
}

@Composable
private fun TxtTocRuleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    style: AppDialogStyle,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 1
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = style.secondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = style.primaryText,
                fontFamily = style.bodyFontFamily
            ),
            shape = RoundedCornerShape(style.actionRadius),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = style.primaryText,
                unfocusedTextColor = style.primaryText,
                focusedContainerColor = style.fieldSurface,
                unfocusedContainerColor = style.fieldSurface,
                cursorColor = style.accent,
                focusedBorderColor = style.accent.copy(alpha = 0.55f),
                unfocusedBorderColor = style.stroke
            )
        )
    }
}
