package io.legado.app.ui.rss.subscription

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.RuleSub
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.toastOnUi

class RuleSubEditComposeDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private val callback: Callback?
        get() = (parentFragment as? Callback) ?: activity as? Callback

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val initial = requireArguments().toRuleSub()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val typeLabels = stringArrayResource(R.array.rule_type).toList()
                var type by rememberSaveable(initial.id) {
                    mutableIntStateOf(initial.type.coerceIn(0, typeLabels.lastIndex))
                }
                var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
                var url by rememberSaveable(initial.id) { mutableStateOf(initial.url) }
                var autoUpdate by rememberSaveable(initial.id) {
                    mutableStateOf(initial.autoUpdate || initial.updateInterval > 0)
                }
                var silentUpdate by rememberSaveable(initial.id) {
                    mutableStateOf(initial.silentUpdate && (initial.autoUpdate || initial.updateInterval > 0))
                }
                var updateInterval by rememberSaveable(initial.id) {
                    mutableStateOf(initial.updateInterval.coerceAtLeast(0).toString())
                }

                RuleSubEditContent(
                    typeLabels = typeLabels,
                    selectedType = type,
                    onTypeSelected = { type = it },
                    name = name,
                    onNameChange = { name = it },
                    url = url,
                    onUrlChange = { url = it },
                    autoUpdate = autoUpdate,
                    onAutoUpdateChange = { checked ->
                        autoUpdate = checked
                        if (checked && updateInterval.toIntOrNull().orZero() == 0) {
                            updateInterval = "24"
                        }
                        if (!checked) {
                            silentUpdate = false
                            updateInterval = "0"
                        }
                    },
                    silentUpdate = silentUpdate,
                    onSilentUpdateChange = { checked ->
                        if (autoUpdate) {
                            silentUpdate = checked
                        }
                    },
                    updateInterval = updateInterval,
                    onUpdateIntervalChange = { text ->
                        val normalized = text.filter { it.isDigit() }.take(4)
                        updateInterval = normalized
                        val interval = normalized.toIntOrNull().orZero()
                        if (interval == 0) {
                            autoUpdate = false
                            silentUpdate = false
                        } else {
                            autoUpdate = true
                        }
                    },
                    onDismiss = { dismissAllowingStateLoss() },
                    onSave = {
                        if (url.isBlank()) {
                            requireContext().toastOnUi(R.string.null_url)
                            return@RuleSubEditContent
                        }
                        val next = initial.copy(
                            type = type,
                            name = name,
                            url = url,
                            autoUpdate = autoUpdate,
                            silentUpdate = silentUpdate && autoUpdate,
                            updateInterval = if (autoUpdate) {
                                updateInterval.toIntOrNull().orZero()
                            } else {
                                0
                            }
                        )
                        callback?.saveRuleSub(next) {
                            dismissAllowingStateLoss()
                        }
                    }
                )
            }
        }
    }

    companion object {
        private const val ARG_ID = "id"
        private const val ARG_NAME = "name"
        private const val ARG_URL = "url"
        private const val ARG_TYPE = "type"
        private const val ARG_CUSTOM_ORDER = "customOrder"
        private const val ARG_AUTO_UPDATE = "autoUpdate"
        private const val ARG_UPDATE = "update"
        private const val ARG_UPDATE_INTERVAL = "updateInterval"
        private const val ARG_SILENT_UPDATE = "silentUpdate"
        private const val ARG_JS = "js"
        private const val ARG_SHOW_RULE = "showRule"
        private const val ARG_SOURCE_URL = "sourceUrl"

        fun create(ruleSub: RuleSub): RuleSubEditComposeDialog {
            return RuleSubEditComposeDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ID, ruleSub.id)
                    putString(ARG_NAME, ruleSub.name)
                    putString(ARG_URL, ruleSub.url)
                    putInt(ARG_TYPE, ruleSub.type)
                    putInt(ARG_CUSTOM_ORDER, ruleSub.customOrder)
                    putBoolean(ARG_AUTO_UPDATE, ruleSub.autoUpdate)
                    putLong(ARG_UPDATE, ruleSub.update)
                    putInt(ARG_UPDATE_INTERVAL, ruleSub.updateInterval)
                    putBoolean(ARG_SILENT_UPDATE, ruleSub.silentUpdate)
                    putString(ARG_JS, ruleSub.js)
                    putString(ARG_SHOW_RULE, ruleSub.showRule)
                    putString(ARG_SOURCE_URL, ruleSub.sourceUrl)
                }
            }
        }

        private fun Bundle.toRuleSub(): RuleSub {
            return RuleSub(
                id = getLong(ARG_ID),
                name = getString(ARG_NAME).orEmpty(),
                url = getString(ARG_URL).orEmpty(),
                type = getInt(ARG_TYPE),
                customOrder = getInt(ARG_CUSTOM_ORDER),
                autoUpdate = getBoolean(ARG_AUTO_UPDATE),
                update = getLong(ARG_UPDATE),
                updateInterval = getInt(ARG_UPDATE_INTERVAL),
                silentUpdate = getBoolean(ARG_SILENT_UPDATE),
                js = getString(ARG_JS),
                showRule = getString(ARG_SHOW_RULE),
                sourceUrl = getString(ARG_SOURCE_URL)
            )
        }
    }

    interface Callback {
        fun saveRuleSub(ruleSub: RuleSub, onSaved: () -> Unit)
    }
}

@Composable
private fun RuleSubEditContent(
    typeLabels: List<String>,
    selectedType: Int,
    onTypeSelected: (Int) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    url: String,
    onUrlChange: (String) -> Unit,
    autoUpdate: Boolean,
    onAutoUpdateChange: (Boolean) -> Unit,
    silentUpdate: Boolean,
    onSilentUpdateChange: (Boolean) -> Unit,
    updateInterval: String,
    onUpdateIntervalChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    AppDialogFrame(
        title = stringResource(R.string.rule_subscription),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RuleSubTypeRow(
                    labels = typeLabels,
                    selectedType = selectedType,
                    onTypeSelected = onTypeSelected,
                    style = style
                )
                RuleSubTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = stringResource(R.string.name),
                    singleLine = true,
                    style = style
                )
                RuleSubTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    label = "Url",
                    singleLine = true,
                    style = style
                )
                RuleSubSwitchRow(
                    text = stringResource(R.string.auto_update),
                    checked = autoUpdate,
                    enabled = true,
                    onCheckedChange = onAutoUpdateChange,
                    style = style
                )
                RuleSubSwitchRow(
                    text = stringResource(R.string.silent_update),
                    checked = silentUpdate,
                    enabled = autoUpdate,
                    onCheckedChange = onSilentUpdateChange,
                    style = style
                )
                RuleSubIntervalField(
                    value = updateInterval,
                    enabled = autoUpdate,
                    onValueChange = onUpdateIntervalChange,
                    style = style
                )
            }
        },
        actions = {
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
private fun RuleSubTypeRow(
    labels: List<String>,
    selectedType: Int,
    onTypeSelected: (Int) -> Unit,
    style: AppDialogStyle
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.book_type),
            color = style.secondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedType
                LegadoMiuixCard(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onTypeSelected(index) },
                    color = if (selected) {
                        style.accent.copy(alpha = 0.16f)
                    } else {
                        style.fieldSurface
                    },
                    contentColor = if (selected) style.accent else style.primaryText,
                    cornerRadius = style.actionRadius,
                    insidePadding = PaddingValues(horizontal = 8.dp, vertical = 9.dp)
                ) {
                    Text(
                        text = label,
                        color = if (selected) style.accent else style.primaryText,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleSubTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    style: AppDialogStyle,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = label,
            color = style.secondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = style.primaryText,
                fontFamily = style.bodyFontFamily
            ),
            shape = RoundedCornerShape(style.actionRadius),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = style.primaryText,
                unfocusedTextColor = style.primaryText,
                disabledTextColor = style.secondaryText,
                focusedContainerColor = style.fieldSurface,
                unfocusedContainerColor = style.fieldSurface,
                disabledContainerColor = style.fieldSurface.copy(alpha = 0.58f),
                cursorColor = style.accent,
                focusedBorderColor = style.accent.copy(alpha = 0.55f),
                unfocusedBorderColor = style.stroke,
                disabledBorderColor = style.stroke.copy(alpha = 0.38f),
                focusedLabelColor = style.accent,
                unfocusedLabelColor = style.secondaryText,
                focusedPlaceholderColor = style.secondaryText,
                unfocusedPlaceholderColor = style.secondaryText
            )
        )
    }
}

@Composable
private fun RuleSubSwitchRow(
    text: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    style: AppDialogStyle
) {
    val palette = style.toMiuixPalette()
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                color = when {
                    !enabled -> style.secondaryText.copy(alpha = 0.58f)
                    checked -> style.primaryText
                    else -> style.secondaryText
                },
                fontSize = 14.sp,
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(10.dp))
            LegadoMiuixSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                palette = palette
            )
        }
    }
}

@Composable
private fun RuleSubIntervalField(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    style: AppDialogStyle
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RuleSubTextField(
            value = value,
            onValueChange = onValueChange,
            label = stringResource(R.string.update_interval),
            modifier = Modifier.weight(1f),
            singleLine = true,
            enabled = enabled,
            keyboardType = KeyboardType.Number,
            style = style
        )
        Text(
            text = stringResource(R.string.time_hour),
            color = if (enabled) style.primaryText else style.secondaryText.copy(alpha = 0.58f),
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 14.dp)
        )
    }
}

private fun Int?.orZero(): Int = this ?: 0
