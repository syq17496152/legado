package io.legado.app.ui.code.config

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rosemoe.sora.widget.CodeEditor.FLAG_DRAW_LINE_SEPARATOR
import io.github.rosemoe.sora.widget.CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE
import io.github.rosemoe.sora.widget.CodeEditor.FLAG_DRAW_WHITESPACE_INNER
import io.github.rosemoe.sora.widget.CodeEditor.FLAG_DRAW_WHITESPACE_IN_SELECTION
import io.github.rosemoe.sora.widget.CodeEditor.FLAG_DRAW_WHITESPACE_LEADING
import io.github.rosemoe.sora.widget.CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.bodySecondary
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogSwitchRow
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt

/**
 * 代码编辑器设置（迁移：原 BaseDialogFragment(R.layout.dialog_edit_settings) 的
 * 字号行 + 自动补全 Checkbox + 6 个不可见字符 Checkbox 迁移为 AppDialogFrame + AppDialogSwitchRow；
 * 即改即存与 onDismiss 汇总保存 editNonPrintable 的行为保持等价。）
 */
class SettingsDialog() : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    constructor(context: Context, callBack: CallBack) : this() {
        this.callBack = callBack
    }

    private var callBack: CallBack? = null
    private val initialEditNonPrintable = AppConfig.editNonPrintable

    private var fontScale by mutableIntStateOf(AppConfig.editFontScale)
    private var autoComplete by mutableStateOf(AppConfig.editAutoComplete)
    private var flagLeading by mutableStateOf(initialEditNonPrintable and FLAG_DRAW_WHITESPACE_LEADING != 0)
    private var flagInner by mutableStateOf(initialEditNonPrintable and FLAG_DRAW_WHITESPACE_INNER != 0)
    private var flagTrailing by mutableStateOf(initialEditNonPrintable and FLAG_DRAW_WHITESPACE_TRAILING != 0)
    private var flagEmptyLine by mutableStateOf(initialEditNonPrintable and FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE != 0)
    private var flagLineSeparator by mutableStateOf(initialEditNonPrintable and FLAG_DRAW_LINE_SEPARATOR != 0)
    private var flagInSelection by mutableStateOf(initialEditNonPrintable and FLAG_DRAW_WHITESPACE_IN_SELECTION != 0)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    val style = rememberAppDialogStyle()
                    AppDialogFrame(
                        title = stringResource(R.string.config_settings),
                        content = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // 字号
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { pickFontSize() }
                                        .padding(vertical = 6.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.font_scale),
                                        color = style.primaryText,
                                        fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = stringResource(R.string.font_size, fontScale),
                                        color = style.secondaryText,
                                        fontSize = MaterialTheme.typography.bodySecondary.fontSize
                                    )
                                }
                                // 自动补全
                                AppDialogSwitchRow(
                                    text = stringResource(R.string.auto_complete),
                                    checked = autoComplete,
                                    onCheckedChange = {
                                        autoComplete = it
                                        putPrefBoolean(PreferKey.editAutoComplete, it)
                                        callBack?.upEdit(autoComplete = it)
                                    }
                                )
                                // 不可见字符
                                Text(
                                    text = stringResource(R.string.non_printable_set),
                                    color = style.accent,
                                    fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 4.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                AppDialogSwitchRow(
                                    text = stringResource(R.string.whitespace_leading),
                                    checked = flagLeading,
                                    onCheckedChange = { flagLeading = it }
                                )
                                AppDialogSwitchRow(
                                    text = stringResource(R.string.whitespace_inner),
                                    checked = flagInner,
                                    onCheckedChange = { flagInner = it }
                                )
                                AppDialogSwitchRow(
                                    text = stringResource(R.string.whitespace_trailing),
                                    checked = flagTrailing,
                                    onCheckedChange = { flagTrailing = it }
                                )
                                AppDialogSwitchRow(
                                    text = stringResource(R.string.whitespace_empty),
                                    checked = flagEmptyLine,
                                    onCheckedChange = { flagEmptyLine = it }
                                )
                                AppDialogSwitchRow(
                                    text = stringResource(R.string.line_separator),
                                    checked = flagLineSeparator,
                                    onCheckedChange = { flagLineSeparator = it }
                                )
                                AppDialogSwitchRow(
                                    text = stringResource(R.string.whitespace_selection),
                                    checked = flagInSelection,
                                    onCheckedChange = { flagInSelection = it }
                                )
                            }
                        },
                        actions = {
                            // 原对话框即改即存、无确定/取消按钮，保持无 actions
                        }
                    )
                }
            }
        }
    }

    private fun pickFontSize() {
        NumberPickerDialog(requireContext())
            .setTitle(getString(R.string.font_scale))
            .setMaxValue(36)
            .setMinValue(9)
            .setValue(AppConfig.editFontScale)
            .setCustomButton((R.string.btn_default_s)) {
                putPrefInt(PreferKey.editFontScale, 16)
                callBack?.upEdit(fontSize = 16)
                fontScale = 16
            }
            .show {
                putPrefInt(PreferKey.editFontScale, it)
                callBack?.upEdit(fontSize = it)
                fontScale = it
            }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        var editNonPrintable = 0
        if (flagLeading) {
            editNonPrintable = editNonPrintable or FLAG_DRAW_WHITESPACE_LEADING
        }
        if (flagInner) {
            editNonPrintable = editNonPrintable or FLAG_DRAW_WHITESPACE_INNER
        }
        if (flagTrailing) {
            editNonPrintable = editNonPrintable or FLAG_DRAW_WHITESPACE_TRAILING
        }
        if (flagEmptyLine) {
            editNonPrintable = editNonPrintable or FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE
        }
        if (flagLineSeparator) {
            editNonPrintable = editNonPrintable or FLAG_DRAW_LINE_SEPARATOR
        }
        if (flagInSelection) {
            editNonPrintable = editNonPrintable or FLAG_DRAW_WHITESPACE_IN_SELECTION
        }
        if (editNonPrintable != initialEditNonPrintable) {
            putPrefInt(PreferKey.editNonPrintable, editNonPrintable)
            callBack?.upEdit(editNonPrintable = editNonPrintable)
        }
    }

    interface CallBack {
        fun upEdit(fontSize: Int? = null, autoComplete: Boolean? = null, autoWarp: Boolean? = null, editNonPrintable: Int? = null)
    }

}
