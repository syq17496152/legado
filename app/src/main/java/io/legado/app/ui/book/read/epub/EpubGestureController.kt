package io.legado.app.ui.book.read.epub

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.legado.app.help.config.AppConfig
import kotlin.math.abs

class EpubGestureController(
    view: View,
    private val onDragStart: () -> Unit,
    private val onDrag: (Float, Float) -> Unit,
    private val onDragEnd: () -> Unit,
    private val onDragCancel: () -> Unit,
    private val listenerProvider: () -> Listener?
) {

    interface Listener {
        fun onCenterTap(x: Float, y: Float) = Unit
        fun onPreviousPage() = Unit
        fun onNextPage() = Unit
        fun onPageClick(x: Float, y: Float) = Unit
        fun onSwipe(deltaX: Float, deltaY: Float) = Unit
        fun onTapAction(action: Int, x: Float, y: Float) {
            when (action) {
                -1 -> Unit
                0 -> onCenterTap(x, y)
                1 -> onNextPage()
                2 -> onPreviousPage()
                else -> onCenterTap(x, y)
            }
        }
    }

    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = downX
                lastY = downY
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.x - downX
                val deltaY = event.y - downY
                if (!moved && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) {
                    moved = true
                    onDragStart()
                }
                if (moved) {
                    onDrag(event.x - lastX, event.y - lastY)
                    lastX = event.x
                    lastY = event.y
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (moved) {
                    onDragEnd()
                } else {
                    handleTap(event.x, event.y)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                moved = false
                onDragCancel()
                return true
            }
        }
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        val listener = listenerProvider() ?: return
        listener.onPageClick(x, y)
        val width = 1f.coerceAtLeast(lastViewWidth.toFloat())
        val height = 1f.coerceAtLeast(lastViewHeight.toFloat())
        val column = when {
            x < width / 3f -> 0
            x < width * 2f / 3f -> 1
            else -> 2
        }
        val row = when {
            y < height / 3f -> 0
            y < height * 2f / 3f -> 1
            else -> 2
        }
        when (row * 3 + column) {
            0 -> click(listener, AppConfig.clickActionTL, x, y)
            1 -> click(listener, AppConfig.clickActionTC, x, y)
            2 -> click(listener, AppConfig.clickActionTR, x, y)
            3 -> click(listener, AppConfig.clickActionML, x, y)
            4 -> click(listener, AppConfig.clickActionMC, x, y)
            5 -> click(listener, AppConfig.clickActionMR, x, y)
            6 -> click(listener, AppConfig.clickActionBL, x, y)
            7 -> click(listener, AppConfig.clickActionBC, x, y)
            8 -> click(listener, AppConfig.clickActionBR, x, y)
        }
    }

    private var lastViewWidth: Int = 0
    private var lastViewHeight: Int = 0

    fun onSizeChanged(width: Int) {
        lastViewWidth = width
    }

    fun onSizeChanged(width: Int, height: Int) {
        lastViewWidth = width
        lastViewHeight = height
    }

    private fun click(listener: Listener, action: Int, x: Float, y: Float) {
        listener.onTapAction(action, x, y)
    }
}
