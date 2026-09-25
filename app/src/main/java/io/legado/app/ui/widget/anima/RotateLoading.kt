package io.legado.app.ui.widget.anima

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.dpToPx

/**
 * RotateLoading
 * Created by Victor on 2015/4/28.
 */
@Suppress("MemberVisibilityCanBePrivate")
class RotateLoading @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var mPaint: Paint

    private var loadingRectF: RectF? = null
    private var shadowRectF: RectF? = null

    private var topDegree = 10
    private var bottomDegree = 190

    private var arc: Float = 0.toFloat()

    private var thisWidth: Int = 0

    private var changeBigger = true

    private var shadowPosition: Int = 0

    private var hideMode = GONE

    var isStarted = false
        private set

    var loadingColor: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    private var speedOfDegree: Int = 0

    private var speedOfArc: Float = 0.toFloat()

    private val shown = Runnable { this.startInternal() }

    private val hidden = Runnable { this.stopInternal() }

    init {
        loadingColor = context.accentColor
        thisWidth = DEFAULT_WIDTH.dpToPx()
        shadowPosition = DEFAULT_SHADOW_POSITION.dpToPx()
        speedOfDegree = DEFAULT_SPEED_OF_DEGREE

        if (null != attrs) {
            val typedArray = context.obtainStyledAttributes(attrs, R.styleable.RotateLoading)
            loadingColor =
                typedArray.getColor(R.styleable.RotateLoading_loading_color, loadingColor)
            thisWidth = typedArray.getDimensionPixelSize(
                R.styleable.RotateLoading_loading_width,
                DEFAULT_WIDTH.dpToPx()
            )
            shadowPosition = typedArray.getInt(
                R.styleable.RotateLoading_shadow_position,
                DEFAULT_SHADOW_POSITION
            )
            speedOfDegree =
                typedArray.getInt(R.styleable.RotateLoading_loading_speed, DEFAULT_SPEED_OF_DEGREE)
            typedArray.recycle()
        }
        speedOfArc = (speedOfDegree / 4).toFloat()
        mPaint = Paint()
        mPaint.color = loadingColor
        mPaint.isAntiAlias = true
        mPaint.style = Paint.Style.STROKE
        mPaint.strokeWidth = thisWidth.toFloat()
        mPaint.strokeCap = Paint.Cap.ROUND
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        arc = 10f
        refreshRects(w, h)
    }

    /**
     * 程序化构造的等价入口（CE-b）：原 XML 用 `app:loading_width="2dp"` 设定笔宽/内缩基准，
     * 而类默认是 `DEFAULT_WIDTH`(6dp) ⇒ 透明壳页（`activity_translucence` 5 个宿主）改为程序化构造后
     * 必须能显式设定，否则笔宽由 2dp 变 6dp（视觉不等价）。
     */
    fun setLoadingWidthDp(widthDp: Int) {
        thisWidth = widthDp.dpToPx()
        mPaint.strokeWidth = thisWidth.toFloat()
        refreshRects(width, height)
        invalidate()
    }

    /** 依据当前笔宽与视图尺寸重算两个绘制矩形（`onSizeChanged` 与 [setLoadingWidthDp] 共用单源） */
    private fun refreshRects(w: Int, h: Int) {
        loadingRectF =
            RectF(
                (2 * thisWidth).toFloat(),
                (2 * thisWidth).toFloat(),
                (w - 2 * thisWidth).toFloat(),
                (h - 2 * thisWidth).toFloat()
            )
        shadowRectF = RectF(
            (2 * thisWidth + shadowPosition).toFloat(),
            (2 * thisWidth + shadowPosition).toFloat(),
            (w - 2 * thisWidth + shadowPosition).toFloat(),
            (h - 2 * thisWidth + shadowPosition).toFloat()
        )
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isStarted) {
            return
        }

        mPaint.color = ColorUtils.setAlphaComponent(loadingColor, 26)
        shadowRectF?.let {
            canvas.drawArc(it, topDegree.toFloat(), arc, false, mPaint)
            canvas.drawArc(it, bottomDegree.toFloat(), arc, false, mPaint)
        }

        mPaint.color = loadingColor
        loadingRectF?.let {
            canvas.drawArc(it, topDegree.toFloat(), arc, false, mPaint)
            canvas.drawArc(it, bottomDegree.toFloat(), arc, false, mPaint)
        }

        topDegree += speedOfDegree
        bottomDegree += speedOfDegree
        if (topDegree > 360) {
            topDegree -= 360
        }
        if (bottomDegree > 360) {
            bottomDegree -= 360
        }

        if (changeBigger) {
            if (arc < 160) {
                arc += speedOfArc
                invalidate()
            }
        } else {
            if (arc > speedOfDegree) {
                arc -= 2 * speedOfArc
                invalidate()
            }
        }
        if (arc >= 160 || arc <= 10) {
            changeBigger = !changeBigger
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) {
            startInternal()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isStarted = false
        animate().cancel()
        removeCallbacks(shown)
        removeCallbacks(hidden)
    }

    fun visible() {
        removeCallbacks(shown)
        removeCallbacks(hidden)
        post(shown)
    }

    fun inVisible() {
        hideMode = INVISIBLE
        removeCallbacks(shown)
        removeCallbacks(hidden)
        stopInternal()
    }

    fun gone() {
        hideMode = GONE
        removeCallbacks(shown)
        removeCallbacks(hidden)
        stopInternal()
    }

    private fun startInternal() {
        startAnimator()
        isStarted = true
        invalidate()
    }

    private fun stopInternal() {
        stopAnimator()
        invalidate()
    }

    private fun startAnimator() {
        animate().cancel()
        animate().scaleX(1.0f)
            .scaleY(1.0f)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    visibility = VISIBLE
                }
            })
            .start()
    }

    private fun stopAnimator() {
        animate().cancel()
        isStarted = false
        this.visibility = hideMode
    }

    companion object {
        private const val DEFAULT_WIDTH = 6
        private const val DEFAULT_SHADOW_POSITION = 2
        private const val DEFAULT_SPEED_OF_DEGREE = 10
    }

}