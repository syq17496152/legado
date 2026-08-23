package io.legado.app.ui.association

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.toMiuixPalette

/**
 * Load state for import dialogs.
 */
enum class ImportLoadState {
    LOADING,
    SUCCESS,
    ERROR
}

/**
 * Common source item row used by all import dialogs.
 */
@Composable
fun ImportSourceItemRow(
    name: String,
    isChecked: Boolean,
    stateText: String,
    style: AppDialogStyle,
    comment: String? = null,
    onCodeView: (() -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    val palette = style.toMiuixPalette()
    var commentExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!isChecked) }
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = palette.accent,
                uncheckedColor = palette.secondaryText,
                checkmarkColor = palette.surface
            )
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
        ) {
            Text(
                text = name,
                color = style.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (comment != null) {
                Text(
                    text = comment,
                    color = style.secondaryText,
                    fontSize = 12.sp,
                    maxLines = if (commentExpanded) 39 else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clickable { commentExpanded = !commentExpanded }
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stateText,
            color = style.secondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        if (onCodeView != null) {
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = "查看",
                palette = palette,
                onClick = onCodeView,
                cornerRadius = style.actionRadius,
                minWidth = 48.dp,
                minHeight = 32.dp
            )
        }
    }
}