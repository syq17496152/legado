package io.legado.app.ui.widget.text

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.FrameLayout.LayoutParams
import androidx.appcompat.widget.AppCompatTextView
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.themeTabBackgroundColorOrDefault
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.invisible
import io.legado.app.utils.visible


/**
 * Created by milad heydari on 5/6/2016.
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
class BadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    var isHideOnNull = true
        set(hideOnNull) {
            field = hideOnNull
            text = text
        }
    private var radius: Float = 0.toFloat()
    private var flatangle: Boolean

    /**
     * 角标底色**来源模式**（B7 · R29 余量，2026-09-24）。
     *
     * 背景：改造前 `init` 里一次性捕获 `context.accentColor`，此后再无刷新入口 ⇒
     * 主题变更（不重建页面路径，如 Compose 侧 `ThemeSync` 生效但 View 未重建）后角标底色不跟随。
     * 现按模式在**每次 attach / 显式刷新**时重解析：
     * [BgMode.ACCENT] = 主题强调色；[BgMode.TAB] = chip 面 token；
     * [BgMode.EXPLICIT] = 宿主显式设色（不参与主题刷新，避免覆盖宿主意图）。
     */
    private enum class BgMode { ACCENT, TAB, EXPLICIT }

    private var bgMode = BgMode.ACCENT

    val badgeCount: Int?
        get() {
            if (text == null) {
                return null
            }
            val text = text.toString()
            return kotlin.runCatching {
                Integer.parseInt(text)
            }.getOrNull()
        }

    var badgeGravity: Int
        get() {
            val params = layoutParams as LayoutParams
            return params.gravity
        }
        set(gravity) {
            val params = layoutParams as LayoutParams
            params.gravity = gravity
            layoutParams = params
        }

    val badgeMargin: IntArray
        get() {
            val params = layoutParams as LayoutParams
            return intArrayOf(
                params.leftMargin,
                params.topMargin,
                params.rightMargin,
                params.bottomMargin
            )
        }

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.BadgeView)
        val radios =
            typedArray.getDimensionPixelOffset(R.styleable.BadgeView_radius, 8)
        flatangle =
            typedArray.getBoolean(R.styleable.BadgeView_up_flat_angle, false)
        typedArray.recycle()

        if (layoutParams !is LayoutParams) {
            val layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            setLayoutParams(layoutParams)
        }

        //setTypeface(Typeface.DEFAULT_BOLD);
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_label_small))
        setPadding(dip2Px(5f), dip2Px(1f), dip2Px(5f), dip2Px(1f))
        radius = radios.toFloat()

        // set default background（B7·R29 余量：按模式解析，不再在 init 里一次性捕获强调色）
        bgMode = BgMode.ACCENT
        applyTheme()

        gravity = Gravity.CENTER

        // default values
        isHideOnNull = true
        setBadgeCount(0)
        minWidth = dip2Px(16f)
        minHeight = dip2Px(16f)
    }

    /**
     * 视图重新入窗时按模式重解析底色（B7·R29 余量）。
     *
     * 主题可能在本视图**创建之后**变更（不重建页面路径）⇒ 仅靠 init 的一次取值会留下旧色。
     * 与项目 View 侧既有约定一致（`RoundedTagBarView.onAttachedToWindow` + `applyTopBarStyle`）。
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyTheme()
    }

    /**
     * 按当前模式重解析底色（宿主主题刷新回调可直接调用；[BgMode.EXPLICIT] 为无操作）。
     */
    fun applyTheme() {
        when (bgMode) {
            BgMode.ACCENT -> drawBackground(radius, context.accentColor)
            BgMode.TAB -> drawBackground(radius, context.themeTabBackgroundColorOrDefault())
            BgMode.EXPLICIT -> Unit
        }
    }

    override fun setBackgroundColor(color: Int) {
        // 宿主显式设色 ⇒ 退出主题跟随，避免后续 applyTheme 覆盖宿主意图
        bgMode = BgMode.EXPLICIT
        val background = background
        if (background is ShapeDrawable && background.paint.color == color) {
            return
        }
        drawBackground(radius, color)
    }

    fun setBackground(dipRadius: Float, badgeColor: Int) {
        // 公开入口语义为「宿主显式设色」（原行为不变，仅补模式登记）
        bgMode = BgMode.EXPLICIT
        drawBackground(dipRadius, badgeColor)
    }

    private fun drawBackground(dipRadius: Float, badgeColor: Int) {
        val radius = dip2Px(dipRadius).toFloat()
        val radiusArray =
            floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
        if (flatangle) {
            radiusArray.fill(0f, 0, 3)
        }

        val roundRect = RoundRectShape(radiusArray, null, null)
        val bgDrawable = ShapeDrawable(roundRect)
        bgDrawable.paint.color = badgeColor
        background = bgDrawable
        // R31 单源（2026-09-23）：角标字色走 contrastOnColor（与 Compose 侧 contrastOn、标签栏同真值），
        // 不再自建黑白兜底（改造前为三套并存之一）。
        setTextColor(ColorUtils.contrastOnColor(badgeColor))
    }

    /**
     * @see android.widget.TextView.setText
     */
    override fun setText(text: CharSequence, type: BufferType) {
        if (isHideOnNull && TextUtils.isEmpty(text)) {
            invisible()
        } else {
            visible()
        }
        super.setText(text, type)
    }

    fun setBadgeCount(count: Int) {
        text = if (count == 0) "" else count.toString()
    }

    fun setHighlight(highlight: Boolean) {
        // R28/A3（2026-09-23）：未高亮角标底取 chip 面 token `tabBackgroundColor`
        // （原取静态灰阶资源色，仅日夜两态、换主题色/主题包无效）。
        // B7·R29 余量：登记来源模式（而非直接设色）⇒ 主题变更后仍能跟随
        bgMode = if (highlight) BgMode.ACCENT else BgMode.TAB
        applyTheme()
    }

    fun setBadgeMargin(dipMargin: Int) {
        setBadgeMargin(dipMargin, dipMargin, dipMargin, dipMargin)
    }

    fun setBadgeMargin(
        leftDipMargin: Int,
        topDipMargin: Int,
        rightDipMargin: Int,
        bottomDipMargin: Int
    ) {
        val params = layoutParams as LayoutParams
        params.leftMargin = dip2Px(leftDipMargin.toFloat())
        params.topMargin = dip2Px(topDipMargin.toFloat())
        params.rightMargin = dip2Px(rightDipMargin.toFloat())
        params.bottomMargin = dip2Px(bottomDipMargin.toFloat())
        layoutParams = params
    }

    fun incrementBadgeCount(increment: Int) {
        val count = badgeCount
        if (count == null) {
            setBadgeCount(increment)
        } else {
            setBadgeCount(increment + count)
        }
    }

    fun decrementBadgeCount(decrement: Int) {
        incrementBadgeCount(-decrement)
    }

    /**
     * Attach the BadgeView to the target view
     * @param target the view to attach the BadgeView
     */
    fun setTargetView(target: View?) {
        if (parent != null) {
            (parent as ViewGroup).removeView(this)
        }

        if (target == null) {
            return
        }

        if (target.parent is FrameLayout) {
            (target.parent as FrameLayout).addView(this)

        } else if (target.parent is ViewGroup) {
            // use a new FrameLayout container for adding badge
            val parentContainer = target.parent as ViewGroup
            val groupIndex = parentContainer.indexOfChild(target)
            parentContainer.removeView(target)

            val badgeContainer = FrameLayout(context)
            val parentLayoutParams = target.layoutParams

            badgeContainer.layoutParams = parentLayoutParams
            target.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )

            parentContainer.addView(badgeContainer, groupIndex, parentLayoutParams)
            badgeContainer.addView(target)

            badgeContainer.addView(this)
        }

    }

    /**
     * converts dip to px
     */
    private fun dip2Px(dip: Float): Int {
        return (dip * context.resources.displayMetrics.density + 0.5f).toInt()
    }
}
