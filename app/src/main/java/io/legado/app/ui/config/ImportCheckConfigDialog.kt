package io.legado.app.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.model.CheckDepth
import io.legado.app.model.CheckStrictness
import io.legado.app.model.ImportCheck
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

/**
 * 导入校验设置弹框（P1 收敛：主界面 总开关+深度三选一+严格度三选一，并发/超时/重试为高级折叠区）
 */
class ImportCheckConfigDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                ) {
                    ImportCheckConfigContent(style = style)
                }
            }
        }
    }

    @Composable
    private fun ImportCheckConfigContent(style: AppDialogStyle) {
        var enabled by rememberSaveable { mutableStateOf(ImportCheck.enabled) }
        var depth by rememberSaveable { mutableStateOf(ImportCheck.depth.name) }
        var strictness by rememberSaveable { mutableStateOf(ImportCheck.strictness.name) }
        var concurrencyText by rememberSaveable { mutableStateOf(ImportCheck.concurrency.toString()) }
        var timeoutText by rememberSaveable { mutableStateOf((ImportCheck.timeout / 1000).toString()) }
        var retry by rememberSaveable { mutableStateOf(ImportCheck.retry) }
        val palette = style.toMiuixPalette()

        AppDialogFrame(
            title = stringResource(R.string.import_check_config),
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 总开关
                    LegadoMiuixChoiceRow(
                        text = stringResource(R.string.import_check_enabled),
                        selected = enabled,
                        palette = palette,
                        onClick = { enabled = !enabled }
                    )
                    Text(
                        text = stringResource(R.string.import_check_enabled_summary),
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        color = style.secondaryText,
                        modifier = Modifier.padding(start = 4.dp)
                    )

                    if (enabled) {
                        // 校验深度三选一
                        Text(
                            text = stringResource(R.string.import_check_depth),
                            color = style.accent,
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize
                        )
                        DepthRow(
                            text = stringResource(R.string.import_check_depth_l1),
                            selected = depth == CheckDepth.L1.name,
                            style = style,
                            onClick = { depth = CheckDepth.L1.name }
                        )
                        DepthRow(
                            text = stringResource(R.string.import_check_depth_l2),
                            selected = depth == CheckDepth.L2.name,
                            style = style,
                            onClick = { depth = CheckDepth.L2.name }
                        )
                        DepthRow(
                            text = stringResource(R.string.import_check_depth_l3),
                            selected = depth == CheckDepth.L3.name,
                            style = style,
                            onClick = { depth = CheckDepth.L3.name }
                        )

                        // 严格度三选一（附白话说明 P6）
                        Text(
                            text = stringResource(R.string.import_check_strictness),
                            color = style.accent,
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize
                        )
                        DepthRow(
                            text = stringResource(R.string.import_check_strict_lenient),
                            selected = strictness == CheckStrictness.LENIENT.name,
                            style = style,
                            onClick = { strictness = CheckStrictness.LENIENT.name }
                        )
                        DepthRow(
                            text = stringResource(R.string.import_check_strict_standard),
                            selected = strictness == CheckStrictness.STANDARD.name,
                            style = style,
                            onClick = { strictness = CheckStrictness.STANDARD.name }
                        )
                        DepthRow(
                            text = stringResource(R.string.import_check_strict_strict),
                            selected = strictness == CheckStrictness.STRICT.name,
                            style = style,
                            onClick = { strictness = CheckStrictness.STRICT.name }
                        )

                        // 大集合提示
                        Text(
                            text = stringResource(R.string.import_check_large_hint),
                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                            color = style.secondaryText,
                            modifier = Modifier.padding(start = 4.dp)
                        )

                        // 并发/超时/重试直接平铺（真机反馈：配置项不该折叠隐藏，尊重用户自配置）
                        ImportCheckNumberField(
                            value = concurrencyText,
                            onValueChange = { concurrencyText = it.filter { c -> c.isDigit() } },
                            label = stringResource(R.string.import_check_concurrency),
                            style = style
                        )
                        ImportCheckNumberField(
                            value = timeoutText,
                            onValueChange = { timeoutText = it.filter { c -> c.isDigit() } },
                            label = stringResource(R.string.import_check_timeout),
                            style = style
                        )
                        LegadoMiuixChoiceRow(
                            text = stringResource(R.string.import_check_retry),
                            selected = retry,
                            palette = palette,
                            onClick = { retry = !retry }
                        )
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
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = stringResource(R.string.ok),
                    palette = palette,
                    primary = true,
                    onClick = {
                        saveConfig(
                            enabled = enabled,
                            depth = depth,
                            strictness = strictness,
                            concurrencyText = concurrencyText,
                            timeoutText = timeoutText,
                            retry = retry
                        )
                        dismissAllowingStateLoss()
                    },
                    cornerRadius = style.actionRadius
                )
            }
        )
    }

    private fun saveConfig(
        enabled: Boolean,
        depth: String,
        strictness: String,
        concurrencyText: String,
        timeoutText: String,
        retry: Boolean
    ) {
        ImportCheck.enabled = enabled
        ImportCheck.depth = kotlin.runCatching { CheckDepth.valueOf(depth) }.getOrDefault(CheckDepth.L2)
        ImportCheck.strictness = kotlin.runCatching { CheckStrictness.valueOf(strictness) }
            .getOrDefault(CheckStrictness.LENIENT)
        ImportCheck.concurrency = concurrencyText.toIntOrNull()?.coerceIn(4, 16) ?: 8
        ImportCheck.timeout = (timeoutText.toLongOrNull()?.coerceAtLeast(3) ?: 10) * 1000
        ImportCheck.retry = retry
        ImportCheck.putConfig()
    }

    @Composable
    private fun DepthRow(
        text: String,
        selected: Boolean,
        style: AppDialogStyle,
        onClick: () -> Unit
    ) {
        LegadoMiuixChoiceRow(
            text = text,
            selected = selected,
            palette = style.toMiuixPalette(),
            onClick = onClick
        )
    }

    @Composable
    private fun ImportCheckNumberField(
        value: String,
        onValueChange: (String) -> Unit,
        label: String,
        style: AppDialogStyle
    ) {
        // UI 规范归位（2026-09-13 用户批评：暗色下输入框文字黑色看不清）：
        // M3 OutlinedTextField 默认走 MaterialTheme.colorScheme（弹框宿主无 LegadoTheme 时为 light 黑字），
        // 必须显式指定 AppDialogStyle 直色
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, color = style.secondaryText) },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Number
            ),
            textStyle = TextStyle(fontFamily = style.bodyFontFamily, color = style.primaryText),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = style.primaryText,
                unfocusedTextColor = style.primaryText,
                cursorColor = style.accent,
                focusedBorderColor = style.accent,
                unfocusedBorderColor = style.stroke,
                focusedLabelColor = style.accent,
                unfocusedLabelColor = style.secondaryText
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
