package io.legado.app.ui.book.changesource

import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.annotation.ColorInt
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.themeMutedColorOrDefault

@ColorInt
internal fun Int.forceOpaque(): Int {
    return this or 0xFF000000.toInt()
}

@ColorInt
internal fun Context.changeSourceSurfaceColor(): Int {
    return themeCardColorOrDefault().forceOpaque()
}

@ColorInt
internal fun Context.changeSourceMutedColor(): Int {
    return themeMutedColorOrDefault().forceOpaque()
}

internal fun Context.changeSourceDialogBackground(): GradientDrawable {
    return UiCorner.opaqueRounded(changeSourceSurfaceColor(), UiCorner.panelRadius(this))
}
