package io.legado.app.ui.widget.compose

import android.content.Context
import android.content.ContextWrapper
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.utils.showDialogFragment

fun Fragment.showComposeConfirmDialog(
    title: CharSequence,
    message: CharSequence? = null,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    neutralText: CharSequence? = null,
    dangerPositive: Boolean = false,
    showNegative: Boolean = true,
    messageInContent: Boolean = false,
    onPositive: () -> Unit,
    onNegative: (() -> Unit)? = null,
    onNeutral: (() -> Unit)? = null,
    onDismissAction: (() -> Unit)? = null
) {
    showDialogFragment(
        ComposeConfirmDialog.create(
            title = title.toString(),
            message = message?.toString(),
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            neutralText = neutralText?.toString(),
            dangerPositive = dangerPositive,
            positiveRequiresCallback = true,
            negativeRequiresCallback = false,
            messageInContent = messageInContent,
            showNegative = showNegative,
            onPositive = onPositive,
            onNegative = onNegative,
            onNeutral = onNeutral,
            onDismissAction = onDismissAction
        )
    )
}

fun Fragment.showComposeActionListDialog(
    title: CharSequence,
    labels: List<CharSequence>,
    message: CharSequence? = null,
    descriptions: List<CharSequence> = emptyList(),
    dangerIndices: Set<Int> = emptySet(),
    negativeText: CharSequence = getString(R.string.cancel),
    onSelected: (Int) -> Unit
) {
    showDialogFragment(
        ComposeActionListDialog.create(
            title = title.toString(),
            labels = labels.map { it.toString() },
            message = message?.toString(),
            descriptions = descriptions.map { it.toString() },
            dangerIndices = dangerIndices,
            negativeText = negativeText.toString(),
            onSelected = onSelected
        )
    )
}

fun Fragment.showComposeSingleChoiceDialog(
    title: CharSequence,
    labels: List<CharSequence>,
    selectedIndex: Int,
    message: CharSequence? = null,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    allowNoSelection: Boolean = false,
    onPositive: (Int) -> Unit
) {
    showDialogFragment(
        ComposeSingleChoiceDialog.create(
            title = title.toString(),
            labels = labels.map { it.toString() },
            selectedIndex = selectedIndex,
            message = message?.toString(),
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            allowNoSelection = allowNoSelection,
            onPositive = onPositive
        )
    )
}

fun Fragment.showComposeChoiceListDialog(
    title: CharSequence,
    labels: List<CharSequence>,
    selectedIndex: Int = -1,
    message: CharSequence? = null,
    descriptions: List<CharSequence> = emptyList(),
    iconNames: List<String> = emptyList(),
    negativeText: CharSequence = getString(R.string.cancel),
    onSelected: (Int) -> Unit
) {
    showDialogFragment(
        ComposeChoiceListDialog.create(
            title = title.toString(),
            labels = labels.map { it.toString() },
            selectedIndex = selectedIndex,
            message = message?.toString(),
            descriptions = descriptions.map { it.toString() },
            iconNames = iconNames,
            negativeText = negativeText.toString(),
            onSelected = onSelected
        )
    )
}

fun Context.showComposeChoiceListDialog(
    title: CharSequence,
    labels: List<CharSequence>,
    selectedIndex: Int = -1,
    message: CharSequence? = null,
    descriptions: List<CharSequence> = emptyList(),
    iconNames: List<String> = emptyList(),
    negativeText: CharSequence = getString(R.string.cancel),
    onSelected: (Int) -> Unit
) {
    findAppCompatActivity()?.showComposeChoiceListDialog(
        title = title,
        labels = labels,
        selectedIndex = selectedIndex,
        message = message,
        descriptions = descriptions,
        iconNames = iconNames,
        negativeText = negativeText,
        onSelected = onSelected
    )
}

fun Fragment.showComposeMultiChoiceDialog(
    title: CharSequence,
    labels: List<CharSequence>,
    checkedIndices: Set<Int>,
    message: CharSequence? = null,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    onItemCheckedChange: ((Int, Boolean) -> Unit)? = null,
    onDismissAction: (() -> Unit)? = null,
    onPositive: ((BooleanArray) -> Unit)? = null
) {
    showDialogFragment(
        ComposeMultiChoiceDialog.create(
            title = title.toString(),
            labels = labels.map { it.toString() },
            checked = BooleanArray(labels.size) { index -> index in checkedIndices },
            message = message?.toString(),
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            onItemCheckedChange = onItemCheckedChange,
            onDismissAction = onDismissAction,
            onPositive = onPositive
        )
    )
}

fun Fragment.showComposeTextInputDialog(
    title: CharSequence,
    hint: CharSequence = "",
    initialValue: CharSequence = "",
    message: CharSequence? = null,
    readOnly: Boolean = false,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    neutralText: CharSequence? = null,
    minLines: Int = if (readOnly) 2 else 1,
    maxLines: Int = if (readOnly) 6 else 4,
    validateInput: ((String) -> Boolean)? = null,
    onPositive: (String) -> Unit,
    onNeutral: (() -> Unit)? = null
) {
    showDialogFragment(
        ComposeTextInputDialog.create(
            title = title.toString(),
            hint = hint.toString(),
            initialValue = initialValue.toString(),
            message = message?.toString(),
            readOnly = readOnly,
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            neutralText = neutralText?.toString(),
            minLines = minLines,
            maxLines = maxLines,
            validateInput = validateInput,
            onPositive = onPositive,
            onNeutral = onNeutral
        )
    )
}

fun Fragment.showComposeTextFormDialog(
    title: CharSequence,
    labels: List<CharSequence>,
    initialValues: List<CharSequence>,
    passwordFields: Set<Int> = emptySet(),
    message: CharSequence? = null,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    validateInput: ((List<String>) -> Boolean)? = null,
    onPositive: (List<String>) -> Unit
) {
    showDialogFragment(
        ComposeTextFormDialog.create(
            title = title.toString(),
            labels = labels.map { it.toString() },
            initialValues = initialValues.map { it.toString() },
            passwordFields = passwordFields,
            message = message?.toString(),
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            validateInput = validateInput,
            onPositive = onPositive
        )
    )
}

fun Fragment.showComposeTextFormDialogWithChecks(
    title: CharSequence,
    labels: List<CharSequence>,
    initialValues: List<CharSequence>,
    passwordFields: Set<Int> = emptySet(),
    checkboxLabels: List<CharSequence>,
    checkedIndices: Set<Int>,
    message: CharSequence? = null,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    validateInput: ((List<String>) -> Boolean)? = null,
    onPositive: (List<String>, BooleanArray) -> Unit
) {
    showDialogFragment(
        ComposeTextFormDialog.createWithChecks(
            title = title.toString(),
            labels = labels.map { it.toString() },
            initialValues = initialValues.map { it.toString() },
            passwordFields = passwordFields,
            checkboxLabels = checkboxLabels.map { it.toString() },
            checkedIndices = checkedIndices,
            message = message?.toString(),
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            validateInput = validateInput,
            onPositive = onPositive
        )
    )
}

fun Fragment.showComposeNumberPickerDialog(
    title: CharSequence,
    value: Int,
    minValue: Int,
    maxValue: Int,
    isDecimalMode: Boolean = false,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    customText: CharSequence? = null,
    onValue: (Int) -> Unit,
    onCustom: (() -> Unit)? = null
) {
    showDialogFragment(
        ComposeNumberPickerDialog.create(
            title = title.toString(),
            value = value,
            minValue = minValue,
            maxValue = maxValue,
            isDecimalMode = isDecimalMode,
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            customText = customText?.toString(),
            onPositive = onValue,
            onCustom = onCustom
        )
    )
}

fun AppCompatActivity.showComposeConfirmDialog(
    title: CharSequence,
    message: CharSequence? = null,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    neutralText: CharSequence? = null,
    dangerPositive: Boolean = false,
    showNegative: Boolean = true,
    messageInContent: Boolean = false,
    onPositive: () -> Unit,
    onNegative: (() -> Unit)? = null,
    onNeutral: (() -> Unit)? = null,
    onDismissAction: (() -> Unit)? = null
) {
    showDialogFragment(
        ComposeConfirmDialog.create(
            title = title.toString(),
            message = message?.toString(),
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            neutralText = neutralText?.toString(),
            dangerPositive = dangerPositive,
            positiveRequiresCallback = true,
            negativeRequiresCallback = false,
            messageInContent = messageInContent,
            showNegative = showNegative,
            onPositive = onPositive,
            onNegative = onNegative,
            onNeutral = onNeutral,
            onDismissAction = onDismissAction
        )
    )
}

fun AppCompatActivity.showComposeActionListDialog(
    title: CharSequence,
    labels: List<CharSequence>,
    message: CharSequence? = null,
    descriptions: List<CharSequence> = emptyList(),
    dangerIndices: Set<Int> = emptySet(),
    negativeText: CharSequence = getString(R.string.cancel),
    onSelected: (Int) -> Unit
) {
    showDialogFragment(
        ComposeActionListDialog.create(
            title = title.toString(),
            labels = labels.map { it.toString() },
            message = message?.toString(),
            descriptions = descriptions.map { it.toString() },
            dangerIndices = dangerIndices,
            negativeText = negativeText.toString(),
            onSelected = onSelected
        )
    )
}

fun AppCompatActivity.showComposeSingleChoiceDialog(
    title: CharSequence,
    labels: List<CharSequence>,
    selectedIndex: Int,
    message: CharSequence? = null,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    allowNoSelection: Boolean = false,
    onPositive: (Int) -> Unit
) {
    showDialogFragment(
        ComposeSingleChoiceDialog.create(
            title = title.toString(),
            labels = labels.map { it.toString() },
            selectedIndex = selectedIndex,
            message = message?.toString(),
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            allowNoSelection = allowNoSelection,
            onPositive = onPositive
        )
    )
}

fun AppCompatActivity.showComposeChoiceListDialog(
    title: CharSequence,
    labels: List<CharSequence>,
    selectedIndex: Int = -1,
    message: CharSequence? = null,
    descriptions: List<CharSequence> = emptyList(),
    iconNames: List<String> = emptyList(),
    negativeText: CharSequence = getString(R.string.cancel),
    onSelected: (Int) -> Unit
) {
    showDialogFragment(
        ComposeChoiceListDialog.create(
            title = title.toString(),
            labels = labels.map { it.toString() },
            selectedIndex = selectedIndex,
            message = message?.toString(),
            descriptions = descriptions.map { it.toString() },
            iconNames = iconNames,
            negativeText = negativeText.toString(),
            onSelected = onSelected
        )
    )
}

fun AppCompatActivity.showComposeMultiChoiceDialog(
    title: CharSequence,
    labels: List<CharSequence>,
    checkedIndices: Set<Int>,
    message: CharSequence? = null,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    onItemCheckedChange: ((Int, Boolean) -> Unit)? = null,
    onDismissAction: (() -> Unit)? = null,
    onPositive: ((BooleanArray) -> Unit)? = null
) {
    showDialogFragment(
        ComposeMultiChoiceDialog.create(
            title = title.toString(),
            labels = labels.map { it.toString() },
            checked = BooleanArray(labels.size) { index -> index in checkedIndices },
            message = message?.toString(),
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            onItemCheckedChange = onItemCheckedChange,
            onDismissAction = onDismissAction,
            onPositive = onPositive
        )
    )
}

fun AppCompatActivity.showComposeTextInputDialog(
    title: CharSequence,
    hint: CharSequence = "",
    initialValue: CharSequence = "",
    message: CharSequence? = null,
    readOnly: Boolean = false,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    neutralText: CharSequence? = null,
    minLines: Int = if (readOnly) 2 else 1,
    maxLines: Int = if (readOnly) 6 else 4,
    validateInput: ((String) -> Boolean)? = null,
    onPositive: (String) -> Unit,
    onNeutral: (() -> Unit)? = null
) {
    showDialogFragment(
        ComposeTextInputDialog.create(
            title = title.toString(),
            hint = hint.toString(),
            initialValue = initialValue.toString(),
            message = message?.toString(),
            readOnly = readOnly,
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            neutralText = neutralText?.toString(),
            minLines = minLines,
            maxLines = maxLines,
            validateInput = validateInput,
            onPositive = onPositive,
            onNeutral = onNeutral
        )
    )
}

fun AppCompatActivity.showComposeSuggestionTextInputDialog(
    title: CharSequence,
    hint: CharSequence = "",
    initialValue: CharSequence = "",
    suggestions: List<CharSequence> = emptyList(),
    message: CharSequence? = null,
    deletable: Boolean = false,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    neutralText: CharSequence? = null,
    validateInput: ((String) -> Boolean)? = null,
    onPositive: (String) -> Unit,
    onNeutral: (() -> Unit)? = null,
    onSuggestionDeleted: ((String) -> Unit)? = null
) {
    showDialogFragment(
        ComposeSuggestionTextInputDialog.create(
            title = title.toString(),
            hint = hint.toString(),
            initialValue = initialValue.toString(),
            suggestions = suggestions.map { it.toString() },
            message = message?.toString(),
            deletable = deletable,
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            neutralText = neutralText?.toString(),
            validateInput = validateInput,
            onPositive = onPositive,
            onNeutral = onNeutral,
            onSuggestionDeleted = onSuggestionDeleted
        )
    )
}

fun AppCompatActivity.showComposeTextFormDialog(
    title: CharSequence,
    labels: List<CharSequence>,
    initialValues: List<CharSequence>,
    passwordFields: Set<Int> = emptySet(),
    message: CharSequence? = null,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    validateInput: ((List<String>) -> Boolean)? = null,
    onPositive: (List<String>) -> Unit
) {
    showDialogFragment(
        ComposeTextFormDialog.create(
            title = title.toString(),
            labels = labels.map { it.toString() },
            initialValues = initialValues.map { it.toString() },
            passwordFields = passwordFields,
            message = message?.toString(),
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            validateInput = validateInput,
            onPositive = onPositive
        )
    )
}

fun AppCompatActivity.showComposeTextFormDialogWithChecks(
    title: CharSequence,
    labels: List<CharSequence>,
    initialValues: List<CharSequence>,
    passwordFields: Set<Int> = emptySet(),
    checkboxLabels: List<CharSequence>,
    checkedIndices: Set<Int>,
    message: CharSequence? = null,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    validateInput: ((List<String>) -> Boolean)? = null,
    onPositive: (List<String>, BooleanArray) -> Unit
) {
    showDialogFragment(
        ComposeTextFormDialog.createWithChecks(
            title = title.toString(),
            labels = labels.map { it.toString() },
            initialValues = initialValues.map { it.toString() },
            passwordFields = passwordFields,
            checkboxLabels = checkboxLabels.map { it.toString() },
            checkedIndices = checkedIndices,
            message = message?.toString(),
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            validateInput = validateInput,
            onPositive = onPositive
        )
    )
}

fun AppCompatActivity.showComposeNumberPickerDialog(
    title: CharSequence,
    value: Int,
    minValue: Int,
    maxValue: Int,
    isDecimalMode: Boolean = false,
    positiveText: CharSequence = getString(android.R.string.ok),
    negativeText: CharSequence = getString(android.R.string.cancel),
    customText: CharSequence? = null,
    onValue: (Int) -> Unit,
    onCustom: (() -> Unit)? = null
) {
    showDialogFragment(
        ComposeNumberPickerDialog.create(
            title = title.toString(),
            value = value,
            minValue = minValue,
            maxValue = maxValue,
            isDecimalMode = isDecimalMode,
            positiveText = positiveText.toString(),
            negativeText = negativeText.toString(),
            customText = customText?.toString(),
            onPositive = onValue,
            onCustom = onCustom
        )
    )
}

private tailrec fun Context.findAppCompatActivity(): AppCompatActivity? {
    return when (this) {
        is AppCompatActivity -> this
        is androidx.appcompat.view.ContextThemeWrapper -> baseContext.findAppCompatActivity()
        is android.view.ContextThemeWrapper -> baseContext.findAppCompatActivity()
        is ContextWrapper -> baseContext.findAppCompatActivity()
        else -> null
    }
}
