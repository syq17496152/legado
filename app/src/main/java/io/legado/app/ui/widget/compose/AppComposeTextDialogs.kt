package io.legado.app.ui.widget.compose

import android.content.DialogInterface
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.theme.LegadoTheme

class ComposeTextInputDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private var validateInput: ((String) -> Boolean)? = null
    private var onPositive: ((String) -> Unit)? = null
    private var onNeutral: (() -> Unit)? = null
    // 负向按钮回调（ui-theme-governance-polish P7）：保留旧 View alert"取消→副作用"语义
    private var onNegative: (() -> Unit)? = null
    // dismiss 回调（P7）：旧 View alert 的 onDismiss 等价物，供调用方协程 resume
    private var onDismissed: (() -> Unit)? = null

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissed?.invoke()
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
                LegadoTheme {
                    DismissWhenCallbackMissing(
                        missing = onPositive == null,
                        dismiss = ::dismissAllowingStateLoss
                    )
                    val readOnly = args.getBoolean(ARG_READ_ONLY)
                    val hintText = args.getString(ARG_HINT).orEmpty()
                    val positiveText = args.getString(ARG_POSITIVE_TEXT)
                        .orEmpty()
                        .ifBlank { stringResource(R.string.ok) }
                    val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                        .orEmpty()
                        .ifBlank { stringResource(R.string.cancel) }
                    val neutralText = args.getString(ARG_NEUTRAL_TEXT)?.takeIf { it.isNotBlank() }
                    val minLines = args.getInt(ARG_MIN_LINES)
                    val maxLines = args.getInt(ARG_MAX_LINES)
                    var text by rememberSaveable {
                        mutableStateOf(args.getString(ARG_INITIAL_TEXT).orEmpty())
                    }
                    val style = rememberAppDialogStyle()
                    AppDialogFrame(
                        title = args.getString(ARG_TITLE).orEmpty(),
                        message = args.getString(ARG_MESSAGE),
                        content = {
                            AppDialogTextField(
                                value = text,
                                onValueChange = { if (!readOnly) text = it },
                                label = hintText,
                                minLines = minLines,
                                maxLines = maxLines,
                                readOnly = readOnly
                            )
                        },
                        actions = {
                            val palette = style.toMiuixPalette()
                            val neutralLabel = neutralText
                            val neutralCallback = onNeutral
                            if (neutralLabel != null && neutralCallback != null) {
                                LegadoMiuixActionButton(
                                    text = neutralLabel,
                                    palette = palette,
                                    onClick = {
                                        dismissAllowingStateLoss()
                                        neutralCallback.invoke()
                                    },
                                    cornerRadius = style.actionRadius
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            LegadoMiuixActionButton(
                                text = negativeText,
                                palette = palette,
                                onClick = {
                                    onNegative?.invoke()
                                    dismissAllowingStateLoss()
                                },
                                cornerRadius = style.actionRadius
                            )
                            onPositive?.let { positiveCallback ->
                                Spacer(modifier = Modifier.width(8.dp))
                                LegadoMiuixActionButton(
                                    text = positiveText,
                                    palette = palette,
                                    onClick = {
                                        val current = text
                                        if (validateInput?.invoke(current) != false) {
                                            dismissAllowingStateLoss()
                                            positiveCallback.invoke(current)
                                        }
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
            hint: String = "",
            initialValue: String = "",
            message: String? = null,
            readOnly: Boolean = false,
            positiveText: String,
            negativeText: String,
            neutralText: String? = null,
            minLines: Int = if (readOnly) 2 else 1,
            maxLines: Int = if (readOnly) 6 else 4,
            validateInput: ((String) -> Boolean)? = null,
            onPositive: (String) -> Unit,
            onNeutral: (() -> Unit)? = null,
            onNegative: (() -> Unit)? = null,
            onDismissed: (() -> Unit)? = null
        ): ComposeTextInputDialog {
            return ComposeTextInputDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_HINT, hint)
                    putString(ARG_INITIAL_TEXT, initialValue)
                    putString(ARG_MESSAGE, message)
                    putBoolean(ARG_READ_ONLY, readOnly)
                    putString(ARG_POSITIVE_TEXT, positiveText)
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                    putString(ARG_NEUTRAL_TEXT, neutralText)
                    putInt(ARG_MIN_LINES, minLines.coerceAtLeast(1))
                    putInt(ARG_MAX_LINES, maxLines.coerceAtLeast(minLines.coerceAtLeast(1)))
                }
                this.validateInput = validateInput
                this.onPositive = onPositive
                this.onNeutral = onNeutral
                this.onNegative = onNegative
                this.onDismissed = onDismissed
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_HINT = "hint"
        private const val ARG_INITIAL_TEXT = "initialText"
        private const val ARG_MESSAGE = "message"
        private const val ARG_READ_ONLY = "readOnly"
        private const val ARG_POSITIVE_TEXT = "positiveText"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
        private const val ARG_NEUTRAL_TEXT = "neutralText"
        private const val ARG_MIN_LINES = "minLines"
        private const val ARG_MAX_LINES = "maxLines"
    }
}

class ComposeSuggestionTextInputDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private var validateInput: ((String) -> Boolean)? = null
    private var onPositive: ((String) -> Unit)? = null
    private var onNeutral: (() -> Unit)? = null
    private var onSuggestionDeleted: ((String) -> Unit)? = null

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
                    val hintText = args.getString(ARG_HINT).orEmpty()
                    val positiveText = args.getString(ARG_POSITIVE_TEXT)
                        .orEmpty()
                        .ifBlank { stringResource(R.string.ok) }
                    val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                        .orEmpty()
                        .ifBlank { stringResource(R.string.cancel) }
                    val neutralText = args.getString(ARG_NEUTRAL_TEXT)?.takeIf { it.isNotBlank() }
                    val suggestions = remember {
                        mutableStateListOf<String>().apply {
                            addAll(args.getStringArrayList(ARG_SUGGESTIONS).orEmpty())
                        }
                    }
                    var text by rememberSaveable {
                        mutableStateOf(args.getString(ARG_INITIAL_TEXT).orEmpty())
                    }
                    val style = rememberAppDialogStyle()
                    AppDialogFrame(
                        title = args.getString(ARG_TITLE).orEmpty(),
                        message = args.getString(ARG_MESSAGE),
                        content = {
                            AppDialogTextField(
                                value = text,
                                onValueChange = { text = it },
                                label = hintText,
                                minLines = 1,
                                maxLines = 4
                            )
                            if (suggestions.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 280.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    itemsIndexed(
                                        items = suggestions,
                                        key = { index, item -> "$index:$item" }
                                    ) { index, suggestion ->
                                        SuggestionInputRow(
                                            text = suggestion,
                                            style = style,
                                            deletable = args.getBoolean(ARG_DELETABLE) &&
                                                onSuggestionDeleted != null,
                                            onClick = { text = suggestion },
                                            onDelete = {
                                                suggestions.removeAt(index)
                                                onSuggestionDeleted?.invoke(suggestion)
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        actions = {
                            val palette = style.toMiuixPalette()
                            val neutralLabel = neutralText
                            val neutralCallback = onNeutral
                            if (neutralLabel != null && neutralCallback != null) {
                                LegadoMiuixActionButton(
                                    text = neutralLabel,
                                    palette = palette,
                                    onClick = {
                                        dismissAllowingStateLoss()
                                        neutralCallback.invoke()
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
                            onPositive?.let { positiveCallback ->
                                Spacer(modifier = Modifier.width(8.dp))
                                LegadoMiuixActionButton(
                                    text = positiveText,
                                    palette = palette,
                                    onClick = {
                                        val current = text
                                        if (validateInput?.invoke(current) != false) {
                                            dismissAllowingStateLoss()
                                            positiveCallback.invoke(current)
                                        }
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
            hint: String = "",
            initialValue: String = "",
            suggestions: List<String> = emptyList(),
            message: String? = null,
            deletable: Boolean = false,
            positiveText: String,
            negativeText: String,
            neutralText: String? = null,
            validateInput: ((String) -> Boolean)? = null,
            onPositive: (String) -> Unit,
            onNeutral: (() -> Unit)? = null,
            onSuggestionDeleted: ((String) -> Unit)? = null
        ): ComposeSuggestionTextInputDialog {
            return ComposeSuggestionTextInputDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_HINT, hint)
                    putString(ARG_INITIAL_TEXT, initialValue)
                    putStringArrayList(ARG_SUGGESTIONS, ArrayList(suggestions))
                    putString(ARG_MESSAGE, message)
                    putBoolean(ARG_DELETABLE, deletable)
                    putString(ARG_POSITIVE_TEXT, positiveText)
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                    putString(ARG_NEUTRAL_TEXT, neutralText)
                }
                this.validateInput = validateInput
                this.onPositive = onPositive
                this.onNeutral = onNeutral
                this.onSuggestionDeleted = onSuggestionDeleted
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_HINT = "hint"
        private const val ARG_INITIAL_TEXT = "initialText"
        private const val ARG_SUGGESTIONS = "suggestions"
        private const val ARG_MESSAGE = "message"
        private const val ARG_DELETABLE = "deletable"
        private const val ARG_POSITIVE_TEXT = "positiveText"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
        private const val ARG_NEUTRAL_TEXT = "neutralText"
    }
}

@Composable
private fun SuggestionInputRow(
    text: String,
    style: AppDialogStyle,
    deletable: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val palette = style.toMiuixPalette()
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                color = style.primaryText,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (deletable) {
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = stringResource(R.string.delete),
                    palette = palette,
                    onClick = onDelete,
                    danger = true,
                    cornerRadius = style.actionRadius,
                    minWidth = 58.dp,
                    minHeight = 32.dp,
                    insidePadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

class ComposeTextFormDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Wide

    private var validateInput: ((List<String>) -> Boolean)? = null
    private var onPositive: ((List<String>) -> Unit)? = null
    private var onPositiveWithChecks: ((List<String>, BooleanArray) -> Unit)? = null

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
                        missing = onPositive == null && onPositiveWithChecks == null,
                        dismiss = ::dismissAllowingStateLoss
                    )
                    val labels = remember {
                        args.getStringArrayList(ARG_LABELS)?.toList().orEmpty()
                    }
                    val checkboxLabels = remember {
                        args.getStringArrayList(ARG_CHECKBOX_LABELS)?.toList().orEmpty()
                    }
                    val initialValues = remember(labels) {
                        val rawValues = args.getStringArrayList(ARG_INITIAL_VALUES)?.toList().orEmpty()
                        List(labels.size) { index -> rawValues.getOrNull(index).orEmpty() }
                    }
                    val initialChecked = remember(checkboxLabels) {
                        val rawValues = args.getBooleanArray(ARG_CHECKBOX_VALUES) ?: booleanArrayOf()
                        List(checkboxLabels.size) { index -> rawValues.getOrNull(index) ?: false }
                    }
                    val passwordFlags = remember(labels) {
                        val rawFlags = args.getBooleanArray(ARG_PASSWORD_FLAGS) ?: booleanArrayOf()
                        BooleanArray(labels.size) { index -> rawFlags.getOrNull(index) ?: false }
                    }
                    var values by rememberSaveable(labels) { mutableStateOf(initialValues) }
                    var checkedValues by rememberSaveable(checkboxLabels) { mutableStateOf(initialChecked) }
                    val positiveText = args.getString(ARG_POSITIVE_TEXT)
                        .orEmpty()
                        .ifBlank { stringResource(R.string.ok) }
                    val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                        .orEmpty()
                        .ifBlank { stringResource(R.string.cancel) }
                    val style = rememberAppDialogStyle()
                    AppDialogFrame(
                        title = args.getString(ARG_TITLE).orEmpty(),
                        message = args.getString(ARG_MESSAGE),
                        content = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                labels.forEachIndexed { index, label ->
                                    val isPassword = passwordFlags.getOrNull(index) ?: false
                                    AppDialogTextField(
                                        value = values.getOrNull(index).orEmpty(),
                                        onValueChange = { nextValue ->
                                            values = values.updateAt(index, nextValue, labels.size)
                                        },
                                        label = label,
                                        singleLine = true,
                                        visualTransformation = if (isPassword) {
                                            PasswordVisualTransformation()
                                        } else {
                                            VisualTransformation.None
                                        },
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = if (isPassword) {
                                                KeyboardType.Password
                                            } else {
                                                KeyboardType.Text
                                            }
                                        )
                                    )
                                }
                                checkboxLabels.forEachIndexed { index, label ->
                                    val checked = checkedValues.getOrNull(index) ?: false
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                checkedValues = checkedValues.updateCheckedAt(index, !checked, checkboxLabels.size)
                                            }
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = { next ->
                                                checkedValues = checkedValues.updateCheckedAt(index, next, checkboxLabels.size)
                                            },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = style.accent,
                                                uncheckedColor = style.secondaryText,
                                                checkmarkColor = style.surface
                                            )
                                        )
                                        Text(
                                            text = label,
                                            color = style.primaryText,
                                            fontFamily = style.bodyFontFamily,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        },
                        actions = {
                            val palette = style.toMiuixPalette()
                            LegadoMiuixActionButton(
                                text = negativeText,
                                palette = palette,
                                onClick = { dismissAllowingStateLoss() },
                                cornerRadius = style.actionRadius
                            )
                            if (onPositive != null || onPositiveWithChecks != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                LegadoMiuixActionButton(
                                    text = positiveText,
                                    palette = palette,
                                    onClick = {
                                        val current = values
                                        val currentChecked = checkedValues.toBooleanArray()
                                        if (validateInput?.invoke(current) != false) {
                                            dismissAllowingStateLoss()
                                            onPositive?.invoke(current)
                                            onPositiveWithChecks?.invoke(current, currentChecked)
                                        }
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
            labels: List<String>,
            initialValues: List<String>,
            passwordFields: Set<Int> = emptySet(),
            message: String? = null,
            positiveText: String,
            negativeText: String,
            validateInput: ((List<String>) -> Boolean)? = null,
            onPositive: (List<String>) -> Unit
        ): ComposeTextFormDialog {
            val safeLabels = labels.toList()
            return ComposeTextFormDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putStringArrayList(ARG_LABELS, ArrayList(safeLabels))
                    putStringArrayList(
                        ARG_INITIAL_VALUES,
                        ArrayList(List(safeLabels.size) { index ->
                            initialValues.getOrNull(index).orEmpty()
                        })
                    )
                    putBooleanArray(
                        ARG_PASSWORD_FLAGS,
                        BooleanArray(safeLabels.size) { index -> index in passwordFields }
                    )
                    putString(ARG_MESSAGE, message)
                    putString(ARG_POSITIVE_TEXT, positiveText)
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                }
                this.validateInput = validateInput
                this.onPositive = onPositive
            }
        }

        fun createWithChecks(
            title: String,
            labels: List<String>,
            initialValues: List<String>,
            passwordFields: Set<Int> = emptySet(),
            checkboxLabels: List<String>,
            checkedIndices: Set<Int>,
            message: String? = null,
            positiveText: String,
            negativeText: String,
            validateInput: ((List<String>) -> Boolean)? = null,
            onPositive: (List<String>, BooleanArray) -> Unit
        ): ComposeTextFormDialog {
            val safeLabels = labels.toList()
            val safeCheckboxLabels = checkboxLabels.toList()
            return ComposeTextFormDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putStringArrayList(ARG_LABELS, ArrayList(safeLabels))
                    putStringArrayList(
                        ARG_INITIAL_VALUES,
                        ArrayList(List(safeLabels.size) { index ->
                            initialValues.getOrNull(index).orEmpty()
                        })
                    )
                    putBooleanArray(
                        ARG_PASSWORD_FLAGS,
                        BooleanArray(safeLabels.size) { index -> index in passwordFields }
                    )
                    putStringArrayList(ARG_CHECKBOX_LABELS, ArrayList(safeCheckboxLabels))
                    putBooleanArray(
                        ARG_CHECKBOX_VALUES,
                        BooleanArray(safeCheckboxLabels.size) { index -> index in checkedIndices }
                    )
                    putString(ARG_MESSAGE, message)
                    putString(ARG_POSITIVE_TEXT, positiveText)
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                }
                this.validateInput = validateInput
                this.onPositiveWithChecks = onPositive
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_LABELS = "labels"
        private const val ARG_INITIAL_VALUES = "initialValues"
        private const val ARG_PASSWORD_FLAGS = "passwordFlags"
        private const val ARG_CHECKBOX_LABELS = "checkboxLabels"
        private const val ARG_CHECKBOX_VALUES = "checkboxValues"
        private const val ARG_MESSAGE = "message"
        private const val ARG_POSITIVE_TEXT = "positiveText"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
    }
}

private fun List<String>.updateAt(index: Int, value: String, size: Int): List<String> {
    if (index !in 0 until size) return this
    return MutableList(size) { i -> getOrNull(i).orEmpty() }.apply {
        this[index] = value
    }
}

private fun List<Boolean>.updateCheckedAt(index: Int, value: Boolean, size: Int): List<Boolean> {
    if (index !in 0 until size) return this
    return MutableList(size) { i -> getOrNull(i) ?: false }.apply {
        this[index] = value
    }
}
