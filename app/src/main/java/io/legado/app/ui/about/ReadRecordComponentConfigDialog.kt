package io.legado.app.ui.about

import android.content.Context
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.utils.applyModernWindowStyle
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setLayout
import kotlin.math.min

object ReadRecordComponentConfigDialog {

    fun show(
        context: Context,
        initialItems: List<ReadRecordComponentItem>,
        onSaved: (List<ReadRecordComponentItem>) -> Unit
    ) {
        lateinit var dialog: AlertDialog
        val fixedListHeight = min(
            420.dpToPx(),
            (context.resources.displayMetrics.heightPixels * 0.48f).toInt()
        ).coerceAtLeast(260.dpToPx())
        val composeView = ComposeView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                (context.resources.displayMetrics.widthPixels * 0.9f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                LegadoComposeTheme {
                    ReadRecordComponentConfigContent(
                        initialItems = initialItems,
                        listHeightDp = fixedListHeight / context.resources.displayMetrics.density,
                        onCancel = { dialog.dismiss() },
                        onSave = { items ->
                            val normalized = items.map { it.copy() }.toMutableList()
                            if (normalized.none { it.enabled }) {
                                normalized.firstOrNull()?.enabled = true
                            }
                            onSaved(normalized)
                            dialog.dismiss()
                        }
                    )
                }
            }
        }
        dialog = AlertDialog.Builder(context)
            .setView(composeView)
            .create()
        dialog.setOnShowListener {
            dialog.setLayout(0.9f, 0.68f)
        }
        dialog.applyModernWindowStyle()
        // AppDialogFrame 自带圆角面板背景，清掉 AlertDialog 自身窗口背景，避免双层背景。
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        dialog.show()
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
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = titleFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(item.type.hintRes),
                    color = palette.secondaryText,
                    fontSize = 12.sp,
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
