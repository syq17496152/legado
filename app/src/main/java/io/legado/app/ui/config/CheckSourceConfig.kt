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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.model.CheckSource
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.putPrefString
import io.legado.app.utils.toastOnUi

class CheckSourceConfig : ComposeDialogFragment() {

    //允许的最小超时时间，秒
    private val minTimeout = 0L

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
                    CheckSourceConfigContent(style = style)
                }
            }
        }
    }

    @Composable
    private fun CheckSourceConfigContent(style: AppDialogStyle) {
        var timeoutText by rememberSaveable { mutableStateOf((CheckSource.timeout / 1000).toString()) }
        var writeSourceComment by rememberSaveable { mutableStateOf(CheckSource.wSourceComment) }
        var checkDomain by rememberSaveable { mutableStateOf(CheckSource.checkDomain) }
        // 域名校验方式：0=Socket快速检测, 1=AnalyzeUrl真实请求(默认)
        var domainCheckMode by rememberSaveable { mutableStateOf(CheckSource.domainCheckMode) }
        var checkSearch by rememberSaveable { mutableStateOf(CheckSource.checkSearch) }
        var checkDiscovery by rememberSaveable { mutableStateOf(CheckSource.checkDiscovery) }
        var checkInfo by rememberSaveable { mutableStateOf(CheckSource.checkInfo) }
        var checkCategory by rememberSaveable { mutableStateOf(CheckSource.checkCategory) }
        var checkContent by rememberSaveable { mutableStateOf(CheckSource.checkContent) }

        fun refreshCheckItemState() {
            val infoEnabled = checkSearch || checkDiscovery
            if (!infoEnabled || !checkInfo) {
                checkCategory = false
                checkContent = false
            } else if (!checkCategory) {
                checkContent = false
            }
        }

        fun disableInfoSection() {
            checkInfo = false
            checkCategory = false
            checkContent = false
        }

        val infoEnabled = checkSearch || checkDiscovery
        val categoryEnabled = infoEnabled && checkInfo
        val contentEnabled = categoryEnabled && checkCategory
        val palette = style.toMiuixPalette()

        AppDialogFrame(
            title = stringResource(R.string.check_source_config),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CheckSourceNumberField(
                        value = timeoutText,
                        onValueChange = { timeoutText = it.filterAsciiDigits() },
                        label = stringResource(R.string.check_source_timeout),
                        style = style
                    )
                    CheckSourceChoiceRow(
                        text = stringResource(R.string.write_source_comment),
                        selected = writeSourceComment,
                        style = style,
                        onClick = { writeSourceComment = !writeSourceComment }
                    )
                    Text(
                        text = stringResource(R.string.check_source_item),
                        color = style.accent,
                        fontSize = 14.sp
                    )
                    CheckSourceChoiceRow(
                        text = stringResource(R.string.domain),
                        selected = checkDomain,
                        style = style,
                        onClick = {
                            checkDomain = !checkDomain
                            if (!checkSearch && !checkDiscovery && !checkDomain) {
                                checkSearch = true
                            }
                            refreshCheckItemState()
                        }
                    )
                    if (checkDomain) {
                        DomainCheckModeRow(
                            text = stringResource(R.string.domain_check_socket),
                            selected = domainCheckMode == 0,
                            style = style,
                            onClick = { domainCheckMode = 0 }
                        )
                        DomainCheckModeRow(
                            text = stringResource(R.string.domain_check_analyze_url),
                            selected = domainCheckMode == 1,
                            style = style,
                            onClick = { domainCheckMode = 1 }
                        )
                    }
                    CheckSourceChoiceRow(
                        text = stringResource(R.string.search),
                        selected = checkSearch,
                        style = style,
                        onClick = {
                            checkSearch = !checkSearch
                            if (!checkSearch && !checkDiscovery) {
                                disableInfoSection()
                                if (!checkDomain) {
                                    checkDiscovery = true
                                }
                            }
                            refreshCheckItemState()
                        }
                    )
                    CheckSourceChoiceRow(
                        text = stringResource(R.string.discovery),
                        selected = checkDiscovery,
                        style = style,
                        onClick = {
                            checkDiscovery = !checkDiscovery
                            if (!checkSearch && !checkDiscovery) {
                                disableInfoSection()
                                if (!checkDomain) {
                                    checkSearch = true
                                }
                            }
                            refreshCheckItemState()
                        }
                    )
                    CheckSourceChoiceRow(
                        text = stringResource(R.string.source_tab_info),
                        selected = checkInfo,
                        enabled = infoEnabled,
                        style = style,
                        onClick = {
                            checkInfo = !checkInfo
                            refreshCheckItemState()
                        }
                    )
                    CheckSourceChoiceRow(
                        text = stringResource(R.string.chapter_list),
                        selected = checkCategory,
                        enabled = categoryEnabled,
                        style = style,
                        onClick = {
                            checkCategory = !checkCategory
                            refreshCheckItemState()
                        }
                    )
                    CheckSourceChoiceRow(
                        text = stringResource(R.string.main_body),
                        selected = checkContent,
                        enabled = contentEnabled,
                        style = style,
                        onClick = {
                            checkContent = !checkContent
                            refreshCheckItemState()
                        }
                    )
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
                            timeoutText = timeoutText,
                            writeSourceComment = writeSourceComment,
                            checkDomain = checkDomain,
                            domainCheckMode = domainCheckMode,
                            checkSearch = checkSearch,
                            checkDiscovery = checkDiscovery,
                            checkInfo = checkInfo,
                            checkCategory = checkCategory,
                            checkContent = checkContent
                        )
                    },
                    cornerRadius = style.actionRadius
                )
            }
        )
    }

    @Composable
    private fun CheckSourceNumberField(
        value: String,
        onValueChange: (String) -> Unit,
        label: String,
        style: AppDialogStyle
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(label) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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

    @Composable
    private fun CheckSourceChoiceRow(
        text: String,
        selected: Boolean,
        style: AppDialogStyle,
        onClick: () -> Unit,
        enabled: Boolean = true
    ) {
        LegadoMiuixChoiceRow(
            text = text,
            selected = selected,
            palette = style.toMiuixPalette(),
            onClick = onClick,
            minHeight = 40.dp,
            compact = true,
            enabled = enabled
        )
    }

    @Composable
    private fun DomainCheckModeRow(
        text: String,
        selected: Boolean,
        style: AppDialogStyle,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp)
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onClick
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                color = style.primaryText,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = style.accent,
                    unselectedColor = style.secondaryText
                )
            )
        }
    }

    private fun saveConfig(
        timeoutText: String,
        writeSourceComment: Boolean,
        checkDomain: Boolean,
        domainCheckMode: Int,
        checkSearch: Boolean,
        checkDiscovery: Boolean,
        checkInfo: Boolean,
        checkCategory: Boolean,
        checkContent: Boolean
    ) {
        val timeoutSeconds = timeoutText.toLongOrNull()
        when {
            timeoutText.isBlank() || timeoutSeconds == null -> {
                toastOnUi("${getString(R.string.timeout)}${getString(R.string.cannot_empty)}")
                return
            }
            timeoutSeconds <= minTimeout -> {
                toastOnUi(
                    "${getString(R.string.timeout)}${getString(R.string.less_than)}$minTimeout${
                        getString(R.string.seconds)
                    }"
                )
                return
            }
            else -> CheckSource.timeout = timeoutSeconds * 1000
        }
        CheckSource.wSourceComment = writeSourceComment
        CheckSource.checkDomain = checkDomain
        // 保存域名校验方式：Socket=0, AnalyzeUrl=1
        CheckSource.domainCheckMode = domainCheckMode
        CheckSource.checkSearch = checkSearch
        CheckSource.checkDiscovery = checkDiscovery
        CheckSource.checkInfo = checkInfo
        CheckSource.checkCategory = checkCategory
        CheckSource.checkContent = checkContent
        CheckSource.putConfig()
        requireContext().putPrefString(PreferKey.checkSource, CheckSource.summary)
        dismissAllowingStateLoss()
    }

    private fun String.filterAsciiDigits(): String {
        return filter { it in '0'..'9' }
    }
}
