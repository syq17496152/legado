package io.legado.app.ui.book.read.config

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.config.AdvancedTitleConfig
import io.legado.app.help.config.AdvancedTitlePackageManager
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.theme.bodySecondary
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.AppDialogSwitchRow
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.toastOnUi

/**
 * 高级标题模板编辑弹框（deep-fix 批 E 迁移：动态 View 表单 → ComposeDialogFrame 表单）。
 * 字段：名称 / 正则开关 / 拆分规则 / 预览示例 / 高度因子 / JSON 模板（外链编辑器）。
 * 保存经 [Host.onAdvancedTitleSaved] 回调宿主，校验与原 View 版等价（名称非空 + JSON 合法）。
 */
class AdvancedTitleConfigDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Confirm

    companion object {
        private const val ARG_ENTRY_ID = "entryId"
        private const val ARG_NAME = "name"
        private const val ARG_SPLIT_MODE = "splitMode"
        private const val ARG_DELIMITER = "delimiter"
        private const val ARG_REGEX = "regex"
        private const val ARG_HEIGHT_FACTOR = "heightFactor"

        fun edit(
            entryId: String,
            name: String,
            json: String,
            splitRule: AdvancedTitleConfig.SplitRule,
            heightFactor: Int
        ) = AdvancedTitleConfigDialog().apply {
            currentJson = json
            arguments = Bundle().apply {
                putString(ARG_ENTRY_ID, entryId)
                putString(ARG_NAME, name)
                putInt(ARG_SPLIT_MODE, splitRule.mode)
                putString(ARG_DELIMITER, splitRule.delimiter)
                putString(ARG_REGEX, splitRule.regex)
                putInt(ARG_HEIGHT_FACTOR, heightFactor.coerceIn(30, 120))
            }
        }
    }

    private var currentJson: String = ""
    private var jsonCursorPosition: Int = 0

    interface Host {
        fun onAdvancedTitleSaved(
            entryId: String,
            name: String,
            json: String,
            splitRule: AdvancedTitleConfig.SplitRule,
            heightFactor: Int
        )
    }

    private val jsonEditor = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val text = result.data?.getStringExtra("text") ?: return@registerForActivityResult
        currentJson = text
        jsonCursorPosition = result.data?.getIntExtra("cursorPosition", text.length) ?: text.length
    }

    private fun openJsonEditor() {
        jsonEditor.launch(Intent(requireContext(), CodeEditActivity::class.java).apply {
            putExtra("text", currentJson)
            putExtra("title", getString(R.string.advanced_title_json_label))
            putExtra("cursorPosition", jsonCursorPosition.coerceIn(0, currentJson.length))
        })
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = requireArguments()
        val entryId = args.getString(ARG_ENTRY_ID).orEmpty()
        val startModeIsRegex = args.getInt(
            ARG_SPLIT_MODE, AdvancedTitleConfig.SPLIT_DELIMITER
        ) == AdvancedTitleConfig.SPLIT_REGEX
        val startDelimiter = args.getString(ARG_DELIMITER) ?: " "
        val startRegex = args.getString(ARG_REGEX) ?: AdvancedTitleConfig.DEFAULT_REGEX
        val initialHeightFactor = args.getInt(
            ARG_HEIGHT_FACTOR, AdvancedTitleConfig.DEFAULT_HEIGHT_FACTOR
        )
        if (currentJson.isBlank()) {
            currentJson = kotlin.runCatching {
                AdvancedTitlePackageManager.readTemplate(entryId)
            }.getOrDefault("")
        }

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AdvancedTitleConfigContent(
                    entryId = entryId,
                    initialName = args.getString(ARG_NAME).orEmpty(),
                    startModeIsRegex = startModeIsRegex,
                    startDelimiter = startDelimiter,
                    startRegex = startRegex,
                    initialHeightFactor = initialHeightFactor
                )
            }
        }
    }

    @Composable
    private fun AdvancedTitleConfigContent(
        entryId: String,
        initialName: String,
        startModeIsRegex: Boolean,
        startDelimiter: String,
        startRegex: String,
        initialHeightFactor: Int
    ) {
        val context = LocalContext.current
        val style = rememberAppDialogStyle()
        val palette = style.toMiuixPalette()
        val defaultSample = stringResource(R.string.advanced_title_sample_default)
        val emptyText = stringResource(R.string.empty)
        var name by rememberSaveable { mutableStateOf(initialName) }
        var useRegex by rememberSaveable { mutableStateOf(startModeIsRegex) }
        var ruleText by rememberSaveable { mutableStateOf(if (startModeIsRegex) startRegex else startDelimiter) }
        var sampleText by rememberSaveable { mutableStateOf(defaultSample) }
        var heightText by rememberSaveable { mutableStateOf(initialHeightFactor.coerceIn(30, 120).toString()) }

        fun buildRule() = AdvancedTitleConfig.SplitRule(
            mode = if (useRegex) {
                AdvancedTitleConfig.SPLIT_REGEX
            } else {
                AdvancedTitleConfig.SPLIT_DELIMITER
            },
            delimiter = if (useRegex) startDelimiter else ruleText,
            regex = if (useRegex) ruleText else startRegex
        )

        val previewText = remember(useRegex, ruleText, sampleText) {
            kotlin.runCatching {
                val parts = AdvancedTitleConfig.split(sampleText, buildRule())
                context.getString(
                    R.string.advanced_title_preview_template,
                    parts.s1.ifBlank { emptyText },
                    parts.s2.ifBlank { emptyText }
                )
            }.getOrElse {
                context.getString(R.string.advanced_title_rule_error, it.localizedMessage.orEmpty())
            }
        }

        fun confirm() {
            val nameVal = name.trim()
            if (nameVal.isEmpty()) {
                context.toastOnUi(context.getString(R.string.advanced_title_name_required))
                return
            }
            val json = currentJson.trim()
            val jsonError = kotlin.runCatching {
                AdvancedTitlePackageManager.validateJson(json)
            }.exceptionOrNull()
            if (jsonError != null) {
                context.toastOnUi(
                    jsonError.localizedMessage
                        ?: context.getString(R.string.advanced_title_invalid_json)
                )
                return
            }
            val heightFactor = heightText.trim().toIntOrNull()?.coerceIn(30, 120)
                ?: AdvancedTitleConfig.DEFAULT_HEIGHT_FACTOR
            dismissAllowingStateLoss()
            (activity as? Host)?.onAdvancedTitleSaved(
                entryId,
                nameVal,
                json,
                buildRule(),
                heightFactor
            )
        }

        AppDialogFrame(
            title = stringResource(R.string.advanced_title_edit_title),
            scrollContent = true,
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AdvancedTitleTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.advanced_title_name),
                        style = style,
                        singleLine = true
                    )
                    AppDialogSwitchRow(
                        text = stringResource(R.string.advanced_title_use_regex),
                        checked = useRegex,
                        onCheckedChange = { next ->
                            useRegex = next
                            ruleText = if (next) startRegex else startDelimiter
                        }
                    )
                    AdvancedTitleTextField(
                        value = ruleText,
                        onValueChange = { ruleText = it },
                        label = stringResource(R.string.advanced_title_rule_label),
                        style = style
                    )
                    AdvancedTitleTextField(
                        value = sampleText,
                        onValueChange = { sampleText = it },
                        label = stringResource(R.string.preview),
                        style = style
                    )
                    Text(
                        text = previewText,
                        color = style.accent,
                        fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                        fontWeight = FontWeight.SemiBold
                    )
                    AdvancedTitleTextField(
                        value = heightText,
                        onValueChange = { heightText = it },
                        label = stringResource(R.string.advanced_title_height_factor_label),
                        style = style,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.advanced_title_json_label),
                            modifier = Modifier.weight(1f),
                            color = style.primaryText,
                            fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.advanced_title_open_editor),
                            palette = palette,
                            onClick = { openJsonEditor() },
                            cornerRadius = style.actionRadius
                        )
                    }
                    Text(
                        text = stringResource(R.string.advanced_title_json_hint),
                        color = style.secondaryText,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    HorizontalDivider(color = style.stroke, thickness = 1.dp)
                }
            },
            actions = {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.cancel),
                    palette = palette,
                    onClick = { dismissAllowingStateLoss() },
                    cornerRadius = style.actionRadius
                )
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = stringResource(R.string.confirm),
                    palette = palette,
                    onClick = { confirm() },
                    primary = true,
                    cornerRadius = style.actionRadius
                )
            }
        )
    }
}

@Composable
private fun AdvancedTitleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    style: AppDialogStyle,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = style.secondaryText,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            shape = RoundedCornerShape(style.actionRadius),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = style.primaryText,
                unfocusedTextColor = style.primaryText,
                focusedContainerColor = style.fieldSurface,
                unfocusedContainerColor = style.fieldSurface,
                cursorColor = style.accent,
                focusedBorderColor = style.accent.copy(alpha = 0.55f),
                unfocusedBorderColor = style.stroke
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = style.primaryText
            )
        )
    }
}
