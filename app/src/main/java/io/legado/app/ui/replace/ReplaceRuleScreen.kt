package io.legado.app.ui.replace

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.widget.compose.AppManagementLazyColumn
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun ReplaceRuleScreen(
    rules: List<ReplaceRule>,
    selected: Set<Long>,
    isSelectMode: Boolean,
    reorderEnabled: Boolean,
    onReorder: (List<ReplaceRule>) -> Unit,
    onSelectToggle: (ReplaceRule) -> Unit,
    onToggleEnabled: (ReplaceRule, Boolean) -> Unit,
    onEdit: (ReplaceRule) -> Unit,
    ruleMenuActions: (ReplaceRule) -> List<AppManagementMenuAction>
) {
    val palette = rememberAppManagementPalette()
    val lazyListState = rememberLazyListState()
    val rulesSnapshot = rules.toList()
    val rulesSignature = rulesSnapshot.joinToString(separator = "\u001F") { it.toString() }
    var orderedRules by remember {
        mutableStateOf(rulesSnapshot, referentialEqualityPolicy())
    }
    LaunchedEffect(reorderEnabled, rulesSignature) {
        orderedRules = rulesSnapshot
    }
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        orderedRules = orderedRules.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    }

    @Composable
    fun itemRow(rule: ReplaceRule, dragHandle: (@Composable () -> Unit)? = null) {
        ReplaceRuleItemRow(
            title = rule.getDisplayNameGroup(),
            enabled = rule.isEnabled,
            isSelected = rule.id in selected,
            isSelectMode = isSelectMode,
            palette = palette,
            onSelectToggle = { onSelectToggle(rule) },
            onToggleEnabled = { onToggleEnabled(rule, it) },
            onEdit = { onEdit(rule) },
            moreActions = ruleMenuActions(rule),
            dragHandle = dragHandle
        )
    }

    AppManagementLazyColumn(
        palette = palette,
        state = lazyListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        val displayedRules = if (reorderEnabled) orderedRules else rulesSnapshot
        items(
            items = displayedRules,
            key = { rule -> rule.id },
            contentType = { "replaceRule" }
        ) { rule ->
            if (reorderEnabled) {
                ReorderableItem(reorderState, key = rule.id) {
                    itemRow(rule) {
                        Icon(
                            painter = painterResource(R.drawable.ic_drag_handle),
                            contentDescription = stringResource(R.string.sort),
                            tint = palette.settings.secondaryText,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(22.dp)
                                .draggableHandle(onDragStopped = { onReorder(orderedRules) })
                        )
                    }
                }
            } else {
                itemRow(rule)
            }
        }
    }
}

@Composable
private fun ReplaceRuleItemRow(
    title: String,
    enabled: Boolean,
    isSelected: Boolean,
    isSelectMode: Boolean,
    palette: AppManagementPalette,
    onSelectToggle: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    moreActions: List<AppManagementMenuAction>,
    dragHandle: (@Composable () -> Unit)? = null
) {
    AppManagementListRow(
        title = title,
        palette = palette,
        selected = isSelected,
        selectionVisible = isSelectMode,
        animatedSelection = true,
        reserveSelectionSlot = false,
        onToggleSelection = onSelectToggle,
        switchChecked = enabled,
        onSwitchChange = onToggleEnabled,
        titleMaxLines = 1,
        minHeight = 56.dp,
        drawPanelImage = false,
        onClick = {
            if (isSelectMode) onSelectToggle() else onEdit()
        },
        onLongClick = onSelectToggle,
        onEdit = onEdit,
        moreActions = moreActions,
        leadingContent = dragHandle
    )
}
