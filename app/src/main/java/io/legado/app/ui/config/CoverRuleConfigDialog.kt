package io.legado.app.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.model.BookCover
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.AppDialogSwitchRow
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.toastOnUi

class CoverRuleConfigDialog : ComposeDialogFragment() {

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
                    CoverRuleConfigContent(style = style)
                }
            }
        }
    }

    @Composable
    private fun CoverRuleConfigContent(style: AppDialogStyle) {
        val initialRule = remember { BookCover.getCoverRule() }
        var enable by rememberSaveable { mutableStateOf(initialRule.enable) }
        var searchUrl by rememberSaveable { mutableStateOf(initialRule.searchUrl) }
        var coverRule by rememberSaveable { mutableStateOf(initialRule.coverRule) }
        val palette = style.toMiuixPalette()

        AppDialogFrame(
            title = stringResource(R.string.cover_config),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppDialogSwitchRow(
                        text = stringResource(R.string.enable),
                        checked = enable,
                        onCheckedChange = { enable = it }
                    )
                    CoverRuleTextField(
                        value = searchUrl,
                        onValueChange = { searchUrl = it },
                        label = stringResource(R.string.r_search_url),
                        style = style
                    )
                    CoverRuleTextField(
                        value = coverRule,
                        onValueChange = { coverRule = it },
                        label = stringResource(R.string.rule_cover_url),
                        style = style
                    )
                }
            },
            actions = {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.btn_default_s),
                    palette = palette,
                    onClick = {
                        BookCover.delCoverRule()
                        dismissAllowingStateLoss()
                    },
                    cornerRadius = style.actionRadius
                )
                Spacer(modifier = Modifier.width(8.dp))
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
                        saveCoverRule(
                            enable = enable,
                            searchUrl = searchUrl,
                            coverRule = coverRule
                        )
                    },
                    cornerRadius = style.actionRadius
                )
            }
        )
    }

    @Composable
    private fun CoverRuleTextField(
        value: String,
        onValueChange: (String) -> Unit,
        label: String,
        style: AppDialogStyle
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            label = { Text(label) },
            shape = RoundedCornerShape(style.actionRadius),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = style.primaryText,
                unfocusedTextColor = style.primaryText,
                focusedContainerColor = style.fieldSurface,
                unfocusedContainerColor = style.fieldSurface,
                cursorColor = style.accent,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedLabelColor = style.accent,
                unfocusedLabelColor = style.secondaryText
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = style.primaryText,
                fontFamily = style.bodyFontFamily
            )
        )
    }

    private fun saveCoverRule(
        enable: Boolean,
        searchUrl: String,
        coverRule: String
    ) {
        if (searchUrl.isBlank() || coverRule.isBlank()) {
            toastOnUi(R.string.cover_rule_empty)
            return
        }
        BookCover.CoverRule(enable, searchUrl, coverRule).let { config ->
            BookCover.saveCoverRule(config)
        }
        dismissAllowingStateLoss()
    }
}
