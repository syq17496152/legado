package io.legado.app.lib.theme

import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Context.composePanelRadius(): Dp {
    return (UiCorner.panelRadius(this) / resources.displayMetrics.density).dp
}

fun Context.composeActionRadius(): Dp {
    return (UiCorner.actionRadius(this) / resources.displayMetrics.density).dp
}

fun Context.composePanelShape(): RoundedCornerShape {
    return RoundedCornerShape(composePanelRadius())
}

fun Context.composeActionShape(): RoundedCornerShape {
    return RoundedCornerShape(composeActionRadius())
}