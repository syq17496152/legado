package io.legado.app.ui.book.read.config

import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import io.legado.app.R
import io.legado.app.help.config.AppConfig

fun Window.applyReaderBottomSheetWindow(
    height: Int = ViewGroup.LayoutParams.WRAP_CONTENT
) {
    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    setBackgroundDrawableResource(android.R.color.transparent)
    decorView.setPadding(0, 0, 0, 0)
    val attr = attributes
    attr.dimAmount = 0f
    attr.gravity = Gravity.BOTTOM
    attr.windowAnimations = if (AppConfig.isEInkMode) 0 else R.style.AnimDialogBottom
    attributes = attr
    setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height)
}
