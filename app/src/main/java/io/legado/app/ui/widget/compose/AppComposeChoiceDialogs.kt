package io.legado.app.ui.widget.compose

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.theme.LegadoTheme

class ComposeMultiChoiceDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private var onPositive: ((BooleanArray) -> Unit)? = null
    private var onItemCheckedChange: ((Int, Boolean) -> Unit)? = null
    private var onDismissAction: (() -> Unit)? = null
    private var onNeutral: (() -> Unit)? = null

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissAction?.invoke()
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
                        missing = onPositive == null && onItemCheckedChange == null,
                        dismiss = ::dismissAllowingStateLoss
                    )
                    val style = rememberAppDialogStyle()
                    val itemLabels = remember {
                        args.getStringArrayList(ARG_LABELS)?.toList().orEmpty()
                    }
                    val initialCheckedValues = remember(itemLabels) {
                        val initialChecked = args.getBooleanArray(ARG_CHECKED) ?: booleanArrayOf()
                        List(itemLabels.size) { index -> initialChecked.getOrNull(index) ?: false }
                    }
                    val saveCheckedState = itemLabels.size <= MAX_SAVEABLE_MULTI_CHOICE_ITEMS
                    val saveableChecked = if (saveCheckedState) {
                        rememberSaveable(itemLabels) { mutableStateOf(initialCheckedValues) }
                    } else {
                        null
                    }
                    val localChecked = if (saveCheckedState) {
                        null
                    } else {
                        remember(itemLabels) {
                            mutableStateListOf<Boolean>().apply { addAll(initialCheckedValues) }
                        }
                    }
                    val positiveText = args.getString(ARG_POSITIVE_TEXT)
                        .orEmpty()
                        .ifBlank { stringResource(R.string.ok) }
                    val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                        .orEmpty()
                        .ifBlank { stringResource(R.string.cancel) }
                    val canSubmit = onPositive != null
                    val neutralText = args.getString(ARG_NEUTRAL_TEXT)?.takeIf { it.isNotBlank() }
                    AppDialogFrame(
                        title = args.getString(ARG_TITLE).orEmpty(),
                        message = args.getString(ARG_MESSAGE),
                        scrollContent = false,
                        content = {
                            val palette = style.toMiuixPalette()
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                itemsIndexed(itemLabels) { index, label ->
                                    LegadoMiuixChoiceRow(
                                        text = label,
                                        selected = saveableChecked?.value?.getOrNull(index)
                                            ?: localChecked?.getOrNull(index)
                                            ?: false,
                                        palette = palette,
                                        onClick = {
                                            if (index in itemLabels.indices) {
                                                val nextChecked = !(saveableChecked?.value?.getOrNull(index)
                                                    ?: localChecked?.getOrNull(index)
                                                    ?: false)
                                                val state = saveableChecked
                                                if (state != null) {
                                                    state.value = state.value.toggleAt(index, itemLabels.size)
                                                } else {
                                                    localChecked?.let { values ->
                                                        if (index in values.indices) {
                                                            values[index] = nextChecked
                                                        }
                                                    }
                                                }
                                                onItemCheckedChange?.invoke(index, nextChecked)
                                            }
                                        },
                                        minHeight = 42.dp
                                    )
                                }
                            }
                        },
                        actions = {
                            val palette = style.toMiuixPalette()
                            if (neutralText != null && onNeutral != null) {
                                LegadoMiuixActionButton(
                                    text = neutralText,
                                    palette = palette,
                                    onClick = {
                                        dismissAllowingStateLoss()
                                        onNeutral?.invoke()
                                    },
                                    cornerRadius = style.actionRadius
                                )
                            }
                            LegadoMiuixActionButton(
                                text = negativeText,
                                palette = palette,
                                onClick = { dismissAllowingStateLoss() },
                                cornerRadius = style.actionRadius
                            )
                            if (canSubmit) {
                                Spacer(modifier = Modifier.width(8.dp))
                                LegadoMiuixActionButton(
                                    text = positiveText,
                                    palette = palette,
                                    onClick = {
                                        val result = BooleanArray(itemLabels.size) { index ->
                                            saveableChecked?.value?.getOrNull(index)
                                                ?: localChecked?.getOrNull(index)
                                                ?: false
                                        }
                                        dismissAllowingStateLoss()
                                        onPositive?.invoke(result)
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
            checked: BooleanArray,
            message: String? = null,
            positiveText: String,
            negativeText: String,
            onItemCheckedChange: ((Int, Boolean) -> Unit)? = null,
            onDismissAction: (() -> Unit)? = null,
            onPositive: ((BooleanArray) -> Unit)? = null,
            neutralText: String? = null,
            onNeutral: (() -> Unit)? = null
        ): ComposeMultiChoiceDialog {
            val safeLabels = labels.toList()
            return ComposeMultiChoiceDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putStringArrayList(ARG_LABELS, ArrayList(safeLabels))
                    putBooleanArray(ARG_CHECKED, checked.copyOf())
                    putString(ARG_MESSAGE, message)
                    putString(ARG_POSITIVE_TEXT, positiveText)
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                }
                this.onPositive = onPositive
                this.onItemCheckedChange = onItemCheckedChange
                this.onDismissAction = onDismissAction
                this.onNeutral = onNeutral
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_LABELS = "labels"
        private const val ARG_CHECKED = "checked"
        private const val ARG_MESSAGE = "message"
        private const val ARG_POSITIVE_TEXT = "positiveText"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
        private const val ARG_NEUTRAL_TEXT = "neutralText"
    }
}

private fun List<Boolean>.toggleAt(index: Int, size: Int): List<Boolean> {
    if (index !in 0 until size) return this
    return MutableList(size) { i -> getOrNull(i) ?: false }.apply {
        this[index] = !this[index]
    }
}

class ComposeSingleChoiceDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private var onPositive: ((Int) -> Unit)? = null

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
                    val itemLabels = remember {
                        args.getStringArrayList(ARG_LABELS)?.toList().orEmpty()
                    }
                    val allowNoSelection = args.getBoolean(ARG_ALLOW_NO_SELECTION)
                    var selectedIndex by rememberSaveable {
                        mutableStateOf(args.getInt(ARG_SELECTED_INDEX).coerceIn(-1, itemLabels.lastIndex))
                    }
                    val positiveText = args.getString(ARG_POSITIVE_TEXT)
                        .orEmpty()
                        .ifBlank { stringResource(R.string.ok) }
                    val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                        .orEmpty()
                        .ifBlank { stringResource(R.string.cancel) }
                    val canSubmit = onPositive != null &&
                        (allowNoSelection || selectedIndex in itemLabels.indices)
                    AppDialogFrame(
                        title = args.getString(ARG_TITLE).orEmpty(),
                        message = args.getString(ARG_MESSAGE),
                        scrollContent = false,
                        content = {
                            val palette = style.toMiuixPalette()
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                itemsIndexed(itemLabels) { index, label ->
                                    LegadoMiuixChoiceRow(
                                        text = label,
                                        selected = selectedIndex == index,
                                        palette = palette,
                                        onClick = { selectedIndex = index },
                                        minHeight = 42.dp
                                    )
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
                            if (canSubmit) {
                                Spacer(modifier = Modifier.width(8.dp))
                                LegadoMiuixActionButton(
                                    text = positiveText,
                                    palette = palette,
                                    onClick = {
                                        dismissAllowingStateLoss()
                                        onPositive?.invoke(selectedIndex)
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
        /**
         * Lambda callbacks are intentionally transient. If Android recreates the dialog,
         * the confirm action is hidden unless a callback is still attached.
         */
        fun create(
            title: String,
            labels: List<String>,
            selectedIndex: Int,
            message: String? = null,
            positiveText: String,
            negativeText: String,
            allowNoSelection: Boolean = false,
            onPositive: (Int) -> Unit
        ): ComposeSingleChoiceDialog {
            val safeLabels = labels.toList()
            return ComposeSingleChoiceDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putStringArrayList(ARG_LABELS, ArrayList(safeLabels))
                    putInt(ARG_SELECTED_INDEX, selectedIndex)
                    putString(ARG_MESSAGE, message)
                    putString(ARG_POSITIVE_TEXT, positiveText)
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                    putBoolean(ARG_ALLOW_NO_SELECTION, allowNoSelection)
                }
                this.onPositive = onPositive
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_LABELS = "labels"
        private const val ARG_SELECTED_INDEX = "selectedIndex"
        private const val ARG_MESSAGE = "message"
        private const val ARG_POSITIVE_TEXT = "positiveText"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
        private const val ARG_ALLOW_NO_SELECTION = "allowNoSelection"
    }
}

class ComposeActionListDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private var onSelected: ((Int) -> Unit)? = null

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
                        missing = onSelected == null,
                        dismiss = ::dismissAllowingStateLoss
                    )
                    val style = rememberAppDialogStyle()
                    val itemLabels = remember {
                        args.getStringArrayList(ARG_LABELS)?.toList().orEmpty()
                    }
                    val itemDescriptions = remember {
                        args.getStringArrayList(ARG_DESCRIPTIONS)?.toList().orEmpty()
                    }
                    val dangerIndices = remember {
                        args.getIntegerArrayList(ARG_DANGER_INDICES)?.toSet().orEmpty()
                    }
                    val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                        .orEmpty()
                        .ifBlank { stringResource(R.string.cancel) }
                    val canSelect = onSelected != null
                    AppDialogFrame(
                        title = args.getString(ARG_TITLE).orEmpty(),
                        message = args.getString(ARG_MESSAGE),
                        scrollContent = false,
                        content = {
                            val palette = style.toMiuixPalette()
                            if (canSelect) {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 420.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    itemsIndexed(itemLabels) { index, label ->
                                        LegadoMiuixActionRow(
                                            text = label,
                                            palette = palette,
                                            onClick = {
                                                dismissAllowingStateLoss()
                                                onSelected?.invoke(index)
                                            },
                                            description = itemDescriptions.getOrNull(index),
                                            danger = index in dangerIndices,
                                            cornerRadius = style.actionRadius
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
            message: String? = null,
            descriptions: List<String> = emptyList(),
            dangerIndices: Set<Int> = emptySet(),
            negativeText: String,
            onSelected: (Int) -> Unit
        ): ComposeActionListDialog {
            require(labels.size <= MAX_ACTION_LIST_ITEMS) {
                "ComposeActionListDialog is for small action menus only."
            }
            val safeLabels = labels.toList()
            val safeDescriptions = List(safeLabels.size) { index ->
                descriptions.getOrNull(index).orEmpty()
            }
            val safeDangerIndices = dangerIndices.filterTo(linkedSetOf()) {
                it in safeLabels.indices
            }
            return ComposeActionListDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putStringArrayList(ARG_LABELS, ArrayList(safeLabels))
                    putString(ARG_MESSAGE, message)
                    putStringArrayList(ARG_DESCRIPTIONS, ArrayList(safeDescriptions))
                    putIntegerArrayList(ARG_DANGER_INDICES, ArrayList(safeDangerIndices))
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                }
                this.onSelected = onSelected
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_LABELS = "labels"
        private const val ARG_MESSAGE = "message"
        private const val ARG_DESCRIPTIONS = "descriptions"
        private const val ARG_DANGER_INDICES = "dangerIndices"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
    }
}
