package io.legado.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.viewpager.widget.ViewPager
import kotlin.math.abs

/**
 * 横滑切 Tab 容器（ui-theme-governance-followup F2/AD：从零实现轴向优势锁定——
 * 现状仅 swipeEnabled 布尔无位移判定，斜滑略带垂直分量即被拦截切 Tab，
 * 与书架下拉刷新/列表滚动形成双轴竞争）。
 * 策略：onInterceptTouchEvent 记录初始触点，move 累计位移 |dx|/|dy| > 1.2 才交 super 拦截。
 */
class LockableViewPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewPager(context, attrs) {

    var swipeEnabled: Boolean = true

    private var downX = 0f
    private var downY = 0f
    private var axialLocked = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!swipeEnabled) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                axialLocked = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!axialLocked) {
                    val dx = abs(ev.x - downX)
                    val dy = abs(ev.y - downY)
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                    if (dx > slop || dy > slop) {
                        // 轴向优势确认：仅水平位移显著占优时才允许拦截切 Tab
                        axialLocked = true
                        if (dy > dx || dx < dy * AXIAL_RATIO) {
                            return false
                        }
                    }
                    return false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> axialLocked = false
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!swipeEnabled) return false
        return super.onTouchEvent(ev)
    }

    companion object {
        /** 水平优势比例阈值：|dx| 须达 |dy| 的 1.2 倍才判定为横滑 */
        private const val AXIAL_RATIO = 1.2f
    }
}
