package io.legado.app.ui.widget.compose

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R

class ComposeChoiceListDialog : ComposeDialogFragment() {

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
                LaunchedEffect(onSelected == null) {
                    if (onSelected == null) {
                        dismissAllowingStateLoss()
                    }
                }
                val style = rememberAppDialogStyle()
                val labels = remember {
                    args.getStringArrayList(ARG_LABELS)?.toList().orEmpty()
                }
                val descriptions = remember {
                    args.getStringArrayList(ARG_DESCRIPTIONS)?.toList().orEmpty()
                }
                val iconNames = remember {
                    args.getStringArrayList(ARG_ICON_NAMES)?.toList().orEmpty()
                }
                val selectedIndex = args.getInt(ARG_SELECTED_INDEX)
                val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.cancel) }
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
                            itemsIndexed(labels) { index, label ->
                                LegadoMiuixChoiceRow(
                                    text = label,
                                    selected = selectedIndex == index,
                                    showSelectedMark = selectedIndex >= 0,
                                    palette = palette,
                                    onClick = {
                                        dismissAllowingStateLoss()
                                        onSelected?.invoke(index)
                                    },
                                    description = descriptions.getOrNull(index),
                                    minHeight = 42.dp,
                                    leadingIconName = iconNames.getOrNull(index)?.takeIf { it.isNotBlank() }
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
                        Spacer(modifier = Modifier.width(0.dp))
                    }
                )
            }
        }
    }

    companion object {
        fun create(
            title: String,
            labels: List<String>,
            selectedIndex: Int = -1,
            message: String? = null,
            descriptions: List<String> = emptyList(),
            iconNames: List<String> = emptyList(),
            negativeText: String,
            onSelected: (Int) -> Unit
        ): ComposeChoiceListDialog {
            val safeLabels = labels.toList()
            val safeDescriptions = List(safeLabels.size) { index ->
                descriptions.getOrNull(index).orEmpty()
            }
            val safeIconNames = List(safeLabels.size) { index ->
                iconNames.getOrNull(index).orEmpty()
            }
            return ComposeChoiceListDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putStringArrayList(ARG_LABELS, ArrayList(safeLabels))
                    putInt(ARG_SELECTED_INDEX, selectedIndex)
                    putString(ARG_MESSAGE, message)
                    putStringArrayList(ARG_DESCRIPTIONS, ArrayList(safeDescriptions))
                    putStringArrayList(ARG_ICON_NAMES, ArrayList(safeIconNames))
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                }
                this.onSelected = onSelected
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_LABELS = "labels"
        private const val ARG_SELECTED_INDEX = "selectedIndex"
        private const val ARG_MESSAGE = "message"
        private const val ARG_DESCRIPTIONS = "descriptions"
        private const val ARG_ICON_NAMES = "iconNames"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
    }
}
