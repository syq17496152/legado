package io.legado.app.ui.book.read.textweb

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 文本富渲染承载容器（epub-md-rich-rendering 阶段 3.1/3.2 接线配套）。
 *
 * **为什么需要它**：WebView 自己会消费触摸事件做滚动，宿主因此拿不到"点击"——
 * 而阅读器必须能通过**点击**调出菜单/翻页（否则用户在富渲染模式下被"困住"，只能靠返回键退出）。
 *
 * 做法（保留 WebView 原生滚动手感，只截"点击"）：
 * - `onInterceptTouchEvent` 全程**不拦截**（返回 false）⇒ 拖动/惯性滚动仍由 WebView 原生处理；
 * - 只有当整个手势**未越触摸阈值**（即"点击"）时，在 `ACTION_UP` 处**拦截**一次 ⇒
 *   子 View 收到 `ACTION_CANCEL`（不会误触发 WebView 的点击），本类拿到 UP 并回调 [onTap]。
 *
 * 这避免了两条常见弯路：① 手动接管滑动导致**丢失惯性**；② 设置透明覆盖层导致 WebView 收不到滚动。
 */
class TextRichReadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** 点击回调（参数为容器内坐标与容器尺寸）。返回 true 表示已消费。 */
    var onTap: ((x: Float, y: Float, width: Int, height: Int) -> Unit)? = null

    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop) {
                    dragging = true
                }
            }

            MotionEvent.ACTION_UP -> {
                // 未越阈值 ⇒ 判定为"点击"：拦截，交给本类处理（子 View 收到 CANCEL）
                if (!dragging) return true
            }

            else -> Unit
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            onTap?.invoke(event.x, event.y, width, height)
            return true
        }
        // 拦截后同一次手势的其余事件（理论上只有 UP）一并消费
        return true
    }
}