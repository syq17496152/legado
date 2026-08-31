package io.legado.app.ui.about

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.bodySecondary
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.dpToPx
import kotlin.math.min

/**
 * 阅读记录组件配置（deep-fix D2 批 B：AlertDialog+ComposeView 换壳 ComposeDialogFragment，
 * 组件列表走 args 序列化，保存回调运行时持有，进程重建后自动关闭）
 */
class ReadRecordComponentConfigDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Confirm

    private var onSaved: ((List<ReadRecordComponentItem>) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        val typeNames = args.getStringArrayList(ARG_TYPES).orEmpty()
        val enabledFlags = args.getBooleanArray(ARG_ENABLED)
            ?: BooleanArray(typeNames.size) { true }
        val initialItems = typeNames.mapIndexed { index, name ->
            ReadRecordComponentItem(
                type = ReadRecordComponentType.fromKey(name) ?: ReadRecordComponentType.OVERVIEW,
                enabled = enabledFlags.getOrElse(index) { true }
            )
        }
        val metrics = resources.displayMetrics
        val listHeightDp = min(
            420.dpToPx(),
            (metrics.heightPixels * 0.48f).toInt()
        ).coerceAtLeast(260.dpToPx()) / metrics.density
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LaunchedEffect(onSaved == null) {
                    if (onSaved == null) {
                        dismissAllowingStateLoss()
                    }
                }
                LegadoTheme {
                    ReadRecordComponentConfigContent(
                        initialItems = initialItems,
                        listHeightDp = listHeightDp,
                        onCancel = { dismissAllowingStateLoss() },
                        onSave = { items ->
                            val normalized = items.map { it.copy() }.toMutableList()
                            if (normalized.none { it.enabled }) {
                                normalized.firstOrNull()?.enabled = true
                            }
                            onSaved?.invoke(normalized)
                            dismissAllowingStateLoss()
                        }
                    )
                }
            }
        }
    }

    companion object {
        fun create(
            initialItems: List<ReadRecordComponentItem>,
            onSaved: (List<ReadRecordComponentItem>) -> Unit
        ): ReadRecordComponentConfigDialog {
            return ReadRecordComponentConfigDialog().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_TYPES, ArrayList(initialItems.map { it.type.name }))
                    putBooleanArray(ARG_ENABLED, BooleanArray(initialItems.size) { initialItems[it].enabled })
                }
                this.onSaved = onSaved
            }
        }

        private const val ARG_TYPES = "typeNames"
        private const val ARG_ENABLED = "enabledFlags"
    }
}

@Composable
private fun ReadRecordComponentConfigContent(
    initialItems: List<ReadRecordComponentItem>,
    listHeightDp: Float,
    onCancel: () -> Unit,
    onSave: (List<ReadRecordComponentItem>) -> Unit
) {
    val items = remember(initialItems) {
        mutableStateListOf<ReadRecordComponentItem>().apply {
            addAll(initialItems.map { it.copy() })
        }
    }
    val dialogStyle = rememberAppDialogStyle()
    val palette = dialogStyle.toMiuixPalette()
    AppDialogFrame(
        title = stringResource(R.string.read_record_customize_components),
        message = stringResource(R.string.read_record_components_hint),
        scrollContent = false,
        content = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 260.dp, max = listHeightDp.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                itemsIndexed(
                    items = items,
                    key = { _, item -> item.type.name }
                ) { index, item ->
                    ReadRecordComponentConfigRow(
                        item = item,
                        canMoveUp = index > 0,
                        canMoveDown = index < items.lastIndex,
                        onToggle = { checked ->
                            items[index] = item.copy(enabled = checked)
                        },
                        onMoveUp = {
                            if (index > 0) {
                                items.move(index, index - 1)
                            }
                        },
                        onMoveDown = {
                            if (index < items.lastIndex) {
                                items.move(index, index + 1)
                            }
                        }
                    )
                }
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = stringResource(android.R.string.cancel),
                palette = palette,
                onClick = onCancel
            )
            Spacer(modifier = Modifier.width(10.dp))
            LegadoMiuixActionButton(
                text = stringResource(android.R.string.ok),
                palette = palette,
                primary = true,
                onClick = { onSave(items) }
            )
        }
    )
}

@Composable
private fun ReadRecordComponentConfigRow(
    item: ReadRecordComponentItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val context = LocalContext.current
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val titleFont = FontFamily(context.titleTypeface())
    val bodyFont = FontFamily(context.uiTypeface())
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!item.enabled) },
        color = palette.surfaceVariant,
        contentColor = palette.primaryText,
        cornerRadius = style.panelRadius,
        insidePadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(item.type.titleRes),
                    color = palette.primaryText,
                    fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = titleFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(item.type.hintRes),
                    color = palette.secondaryText,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    fontFamily = bodyFont,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            LegadoMiuixSwitch(
                checked = item.enabled,
                palette = palette,
                onCheckedChange = onToggle
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.End
        ) {
            LegadoMiuixActionButton(
                text = stringResource(R.string.move_up),
                palette = palette,
                onClick = onMoveUp,
                minWidth = 60.dp,
                minHeight = 34.dp,
                insidePadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = stringResource(R.string.move_down),
                palette = palette,
                onClick = onMoveDown,
                minWidth = 60.dp,
                minHeight = 34.dp,
                insidePadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

private fun MutableList<ReadRecordComponentItem>.move(from: Int, to: Int) {
    if (from !in indices || to !in indices || from == to) return
    val item = removeAt(from)
    add(to, item)
}
