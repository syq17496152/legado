package io.legado.app.ui.book.read.textweb

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 文本富渲染承载容器（epub-md-rich-rendering 阶段 3.1/3.2 接线配套；模板模式共用同一承载位）。
 *
 * **为什么需要它**：WebView 自己会消费触摸事件做滚动，宿主因此拿不到"点击"——
 * 而阅读器必须能通过**点击**调出菜单/翻页（否则用户在富渲染模式下被"困住"，只能靠返回键退出）。
 *
 * 做法（保留 WebView 原生滚动手感，只截"点击"）：
 * - 拖动/惯性滚动全程**不拦截**（`dispatchTouchEvent` 直接交子 View）⇒ 滚动手感与原生一致；
 * - 只在"整段手势未越触摸阈值"（即点击）时，在 `ACTION_UP` 处**先给子 View 补一个 CANCEL**，
 *   再回调 [onTap] 并消费该 UP（子 View 因此不会误触发它自己的点击）。
 *
 * ⚠️ 为什么**不能**用"在 `ACTION_UP` 处 `onInterceptTouchEvent` 返回 true"这种常见写法：
 * 父容器在**最后一个**事件才开始拦截时，框架只把 `CANCEL` 派发给子 View，
 * **不会回调父容器自己的 `onTouchEvent`**（UP 之后没有后续事件再触发自身派发路径）⇒
 * 表现为"点了完全没反应"（真机铁证 2026-10-10：模板/富渲染模式下点屏幕既不出菜单也不翻页，
 * 中部点击同样无响应，故能确定是"点击没到宿主"而非某一分支写错）。
 *
 * 已知取舍：长按不动也按"点击"处理（本模式暂不支持长按选区），与"困住用户"相比是更安全的默认。
 */
class TextRichReadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** 点击回调（参数为容器内坐标与容器尺寸）。 */
    var onTap: ((x: Float, y: Float, width: Int, height: Int) -> Unit)? = null

    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging && !isTap(ev.x - downX, ev.y - downY, touchSlop)) {
                    dragging = true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    // 子 View 已收到 DOWN ⇒ 必须先补 CANCEL（不能只吞掉 UP），再判为"点击"
                    cancelChildGesture(ev)
                    onTap?.invoke(ev.x, ev.y, width, height)
                    return true
                }
            }

            MotionEvent.ACTION_CANCEL -> dragging = false

            else -> Unit
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 给仍在跟踪本次手势的子 View 补发 `ACTION_CANCEL`（否则它会停在"按下未抬起"的中间态）。 */
    private fun cancelChildGesture(ev: MotionEvent) {
        runCatching {
            val cancel = MotionEvent.obtain(ev)
            cancel.setAction(MotionEvent.ACTION_CANCEL)
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
    }

    companion object {

        /**
         * 单击判定：位移未越阈值即视为"点击"（纯函数 ⇒ 可在 JVM 逐值断言，无需真机）。
         *
         * @param dx 相对按下点的横向位移
         * @param dy 相对按下点的纵向位移
         * @param touchSlop 触摸阈值（`ViewConfiguration.scaledTouchSlop`）
         */
        fun isTap(dx: Float, dy: Float, touchSlop: Float): Boolean =
            abs(dx) <= touchSlop && abs(dy) <= touchSlop
    }
}