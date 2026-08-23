package io.legado.app.ui.widget.compose

import androidx.compose.ui.unit.dp

/**
 * Shared dialog width tiers. Content type chooses the tier while every dialog in that tier
 * keeps the same phone width and tablet cap.
 */
enum class AppDialogSize(
    val widthFraction: Float,
    val maxWidthDp: Int
) {
    Confirm(widthFraction = 0.92f, maxWidthDp = 620),
    Form(widthFraction = 0.94f, maxWidthDp = 660),
    Management(widthFraction = 0.96f, maxWidthDp = 700),
    Wide(widthFraction = 0.98f, maxWidthDp = 760)
}

object AppListSpacing {
    val Compact = 6.dp
    val Normal = 8.dp
    val Section = 12.dp
}
