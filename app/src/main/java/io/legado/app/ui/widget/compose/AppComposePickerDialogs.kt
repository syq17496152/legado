package io.legado.app.ui.widget.compose

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.lib.theme.composeActionRadius
import kotlin.math.roundToInt
import io.legado.app.ui.theme.LegadoTheme

class ComposeNumberPickerDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Confirm

    private var onPositive: ((Int) -> Unit)? = null
    private var onCustom: (() -> Unit)? = null

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
                    val style = rememberAppDialogStyle()
                    val minValue = args.getInt(ARG_MIN_VALUE)
                    val maxValue = args.getInt(ARG_MAX_VALUE)
                    val safeMin = minOf(minValue, maxValue)
                    val safeMax = maxOf(minValue, maxValue)
                    val isDecimalMode = args.getBoolean(ARG_DECIMAL_MODE)
                    val positiveText = args.getString(ARG_POSITIVE_TEXT)
                        .orEmpty()
                        .ifBlank { stringResource(R.string.ok) }
                    val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                        .orEmpty()
                        .ifBlank { stringResource(R.string.cancel) }
                    val customText = args.getString(ARG_CUSTOM_TEXT)?.takeIf { it.isNotBlank() }
                    var currentValue by rememberSaveable {
                        mutableIntStateOf(args.getInt(ARG_VALUE).coerceIn(safeMin, safeMax))
                    }
                    var inputText by rememberSaveable(currentValue, isDecimalMode) {
                        mutableStateOf(formatPickerValue(currentValue, isDecimalMode))
                    }
                    val usePercentSlider = !isDecimalMode &&
                        safeMax == 100 &&
                        safeMin in 0..100 &&
                        safeMin < safeMax

                    fun inputValueOrCurrent(): Int {
                        return parsePickerValue(inputText, isDecimalMode)
                            ?.coerceIn(safeMin, safeMax)
                            ?: currentValue
                    }

                    fun commitValue(): Int {
                        val next = inputValueOrCurrent()
                        currentValue = next
                        inputText = formatPickerValue(next, isDecimalMode)
                        return next
                    }

                    AppDialogFrame(
                        title = args.getString(ARG_TITLE).orEmpty(),
                        scrollContent = true,
                        content = {
                            val palette = style.toMiuixPalette()
                            if (usePercentSlider) {
                                LegadoMiuixCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = style.fieldSurface,
                                    contentColor = style.primaryText,
                                    cornerRadius = style.actionRadius,
                                    insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                                ) {
                                    Text(
                                        text = "${currentValue.coerceIn(safeMin, safeMax)}%",
                                        color = style.primaryText,
                                        fontSize = MaterialTheme.typography.headlineSmall.fontSize,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    AppThemedStepperSlider(
                                        value = currentValue.coerceIn(safeMin, safeMax),
                                        range = safeMin..safeMax,
                                        onValueChange = {
                                            currentValue = it.coerceIn(safeMin, safeMax)
                                            inputText = formatPickerValue(currentValue, isDecimalMode)
                                        },
                                        palette = palette
                                    )
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    NumberPickerStepButton(
                                        text = "-",
                                        enabled = inputValueOrCurrent() > safeMin,
                                        palette = palette,
                                        onClick = {
                                            currentValue = (inputValueOrCurrent() - 1).coerceAtLeast(safeMin)
                                            inputText = formatPickerValue(currentValue, isDecimalMode)
                                        }
                                    )
                                    OutlinedTextField(
                                        value = inputText,
                                        onValueChange = { inputText = it },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = when {
                                                safeMin < 0 -> KeyboardType.Text
                                                isDecimalMode -> KeyboardType.Decimal
                                                else -> KeyboardType.Number
                                            }
                                        ),
                                        textStyle = MaterialTheme.typography.titleLarge.copy(
                                            color = style.primaryText,
                                            fontFamily = style.bodyFontFamily,
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Center
                                        ),
                                        shape = RoundedCornerShape(style.actionRadius),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = style.primaryText,
                                            unfocusedTextColor = style.primaryText,
                                            focusedContainerColor = style.fieldSurface,
                                            unfocusedContainerColor = style.fieldSurface,
                                            focusedBorderColor = style.accent.copy(alpha = 0.55f),
                                            unfocusedBorderColor = style.stroke,
                                            cursorColor = style.accent
                                        )
                                    )
                                    NumberPickerStepButton(
                                        text = "+",
                                        enabled = inputValueOrCurrent() < safeMax,
                                        palette = palette,
                                        onClick = {
                                            currentValue = (inputValueOrCurrent() + 1).coerceAtMost(safeMax)
                                            inputText = formatPickerValue(currentValue, isDecimalMode)
                                        }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (usePercentSlider) {
                                    "$safeMin% - $safeMax%"
                                } else {
                                    "${formatPickerValue(safeMin, isDecimalMode)} - ${formatPickerValue(safeMax, isDecimalMode)}"
                                },
                                color = style.secondaryText,
                                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        },
                        actions = {
                            val palette = style.toMiuixPalette()
                            val customCallback = onCustom
                            if (customText != null && customCallback != null) {
                                LegadoMiuixActionButton(
                                    text = customText,
                                    palette = palette,
                                    onClick = {
                                        dismissAllowingStateLoss()
                                        customCallback.invoke()
                                    },
                                    cornerRadius = style.actionRadius
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            LegadoMiuixActionButton(
                                text = negativeText,
                                palette = palette,
                                onClick = { dismissAllowingStateLoss() },
                                cornerRadius = style.actionRadius
                            )
                            onPositive?.let { callback ->
                                Spacer(modifier = Modifier.width(8.dp))
                                LegadoMiuixActionButton(
                                    text = positiveText,
                                    palette = palette,
                                    onClick = {
                                        dismissAllowingStateLoss()
                                        callback.invoke(commitValue())
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
            value: Int,
            minValue: Int,
            maxValue: Int,
            isDecimalMode: Boolean = false,
            positiveText: String,
            negativeText: String,
            customText: String? = null,
            onPositive: (Int) -> Unit,
            onCustom: (() -> Unit)? = null
        ): ComposeNumberPickerDialog {
            return ComposeNumberPickerDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putInt(ARG_VALUE, value)
                    putInt(ARG_MIN_VALUE, minValue)
                    putInt(ARG_MAX_VALUE, maxValue)
                    putBoolean(ARG_DECIMAL_MODE, isDecimalMode)
                    putString(ARG_POSITIVE_TEXT, positiveText)
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                    putString(ARG_CUSTOM_TEXT, customText)
                }
                this.onPositive = onPositive
                this.onCustom = onCustom
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_VALUE = "value"
        private const val ARG_MIN_VALUE = "minValue"
        private const val ARG_MAX_VALUE = "maxValue"
        private const val ARG_DECIMAL_MODE = "decimalMode"
        private const val ARG_POSITIVE_TEXT = "positiveText"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
        private const val ARG_CUSTOM_TEXT = "customText"
    }
}

@Composable
private fun NumberPickerStepButton(
    text: String,
    enabled: Boolean,
    palette: LegadoMiuixPalette,
    onClick: () -> Unit
) {
    val actionRadius = palette.actionRadius ?: LocalContext.current.composeActionRadius()
    LegadoMiuixCard(
        modifier = Modifier
            .width(48.dp)
            .height(52.dp)
            .clickable(enabled = enabled, onClick = onClick),
        color = if (enabled) palette.surfaceVariant else palette.surfaceVariant.copy(alpha = 0.42f),
        contentColor = if (enabled) palette.primaryText else palette.secondaryText.copy(alpha = 0.52f),
        cornerRadius = actionRadius,
        insidePadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (enabled) palette.primaryText else palette.secondaryText.copy(alpha = 0.52f),
                fontSize = MaterialTheme.typography.headlineSmall.fontSize,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun formatPickerValue(value: Int, decimalMode: Boolean): String {
    return if (decimalMode) {
        (value / 10.0).toString()
    } else {
        value.toString()
    }
}

private fun parsePickerValue(value: String, decimalMode: Boolean): Int? {
    val normalized = value.trim()
    if (normalized.isEmpty()) return null
    return if (decimalMode) {
        normalized.toDoubleOrNull()
            ?.takeIf { java.lang.Double.isFinite(it) }
            ?.let { (it * 10).roundToInt() }
    } else {
        normalized.toIntOrNull()
    }
}
