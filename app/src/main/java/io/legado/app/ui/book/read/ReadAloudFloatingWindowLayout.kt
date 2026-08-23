package io.legado.app.ui.book.read

import kotlin.math.roundToInt

internal data class ReadAloudFloatingWindowBounds(
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int
)

internal object ReadAloudFloatingWindowLayout {

    fun bounds(
        screenWidth: Int,
        screenHeight: Int,
        insetLeft: Int,
        insetTop: Int,
        insetRight: Int,
        insetBottom: Int,
        windowWidth: Int,
        windowHeight: Int,
        sideMargin: Int,
        bottomMargin: Int
    ): ReadAloudFloatingWindowBounds {
        val minX = (insetLeft + sideMargin).coerceAtLeast(0)
        val maxX = (screenWidth - insetRight - windowWidth - sideMargin).coerceAtLeast(minX)
        val minY = (insetTop + sideMargin).coerceAtLeast(0)
        val maxY = (screenHeight - insetBottom - windowHeight - bottomMargin).coerceAtLeast(minY)
        return ReadAloudFloatingWindowBounds(minX, maxX, minY, maxY)
    }

    fun xForSide(side: Int, bounds: ReadAloudFloatingWindowBounds): Int {
        return if (side.coerceIn(0, 1) == 0) bounds.minX else bounds.maxX
    }

    fun sideForX(x: Int, bounds: ReadAloudFloatingWindowBounds): Int {
        val center = bounds.minX + (bounds.maxX - bounds.minX) / 2f
        return if (x < center) 0 else 1
    }

    fun yForPercent(percent: Int, bounds: ReadAloudFloatingWindowBounds): Int {
        val range = bounds.maxY - bounds.minY
        if (range <= 0) return bounds.minY
        return bounds.minY + (range * percent.coerceIn(0, 100) / 100f).roundToInt()
    }

    fun percentForY(y: Int, bounds: ReadAloudFloatingWindowBounds): Int {
        val range = bounds.maxY - bounds.minY
        if (range <= 0) return 0
        return (((y.coerceIn(bounds.minY, bounds.maxY) - bounds.minY) * 100f) / range)
            .roundToInt()
            .coerceIn(0, 100)
    }

    fun readerHeight(availableHeight: Int, minHeight: Int, heightPercent: Int): Int {
        if (availableHeight <= 0) return 0
        val safeMinHeight = minHeight.coerceIn(0, availableHeight)
        return (availableHeight * heightPercent.coerceIn(0, 100) / 100f)
            .roundToInt()
            .coerceIn(safeMinHeight, availableHeight)
    }

    fun edgeBallX(side: Int, screenWidth: Int, windowSize: Int): Int {
        val halfWindow = windowSize.coerceAtLeast(0) / 2
        return if (side.coerceIn(0, 1) == 0) {
            -halfWindow
        } else {
            screenWidth.coerceAtLeast(halfWindow) - halfWindow
        }
    }

    fun shouldSuppress(panelVisible: Boolean, appForeground: Boolean): Boolean {
        return panelVisible && appForeground
    }
}
